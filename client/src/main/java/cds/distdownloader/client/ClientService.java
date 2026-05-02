package cds.distdownloader.client;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.ByteString;

import cds.distdownloader.proto.ChunkBitmap;
import cds.distdownloader.proto.ChunkRef;
import cds.distdownloader.proto.ChunkRequest;
import cds.distdownloader.proto.ChunkResponse;
import cds.distdownloader.proto.FileManifestEntry;
import cds.distdownloader.proto.FileRequest;
import cds.distdownloader.proto.GetFileManifestRequest;
import cds.distdownloader.proto.GetFileManifestResponse;
import cds.distdownloader.proto.ListPeersRequest;
import cds.distdownloader.proto.ListPeersResponse;
import cds.distdownloader.proto.MultiChunkRequest;
import cds.distdownloader.proto.PeerEndpoint;
import cds.distdownloader.proto.PeerGrpc;
import cds.distdownloader.proto.TrackerGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

public class ClientService {
    /** Max missing chunk indices listed in the early availability warning (rest summarized). */
    private static final int MISSING_CHUNK_WARNING_INDEX_CAP = 40;

    private final String trackerHost;
    private final int trackerPort;
    private final String manifestPath;
    private final String requestedFilename;
    private final ClientConcurrencyConfig concurrency;
    private final boolean quiet;

    private record PeerConnection(ManagedChannel channel, PeerGrpc.PeerBlockingStub stub) {}
    private final Map<String, PeerConnection> peerConnectionCache = new ConcurrentHashMap<>();

    public ClientService(String trackerHost, int trackerPort, String manifestPath, String requestedFilename) {
        this(trackerHost, trackerPort, manifestPath, requestedFilename, ClientConcurrencyConfig.DEFAULT, false);
    }

    public ClientService(
            String trackerHost,
            int trackerPort,
            String manifestPath,
            String requestedFilename,
            ClientConcurrencyConfig concurrency
    ) {
        this(trackerHost, trackerPort, manifestPath, requestedFilename, concurrency, false);
    }

    public ClientService(
            String trackerHost,
            int trackerPort,
            String manifestPath,
            String requestedFilename,
            ClientConcurrencyConfig concurrency,
            boolean quiet
    ) {
        this.trackerHost = trackerHost;
        this.trackerPort = trackerPort;
        this.manifestPath = manifestPath;
        this.requestedFilename = requestedFilename;
        this.concurrency = concurrency;
        this.quiet = quiet;
    }

    public void start() {
        info("trackerHost=" + trackerHost + ", trackerPort=" + trackerPort);
        info("manifestPath=" + manifestPath);
        info("requestedFilename=" + (requestedFilename == null ? "<default>" : requestedFilename));

        try {
            ManagedChannel trackerChannel = ManagedChannelBuilder
                    .forAddress(trackerHost, trackerPort)
                    .usePlaintext()
                    .build();

            try {
                TrackerGrpc.TrackerBlockingStub trackerStub = TrackerGrpc.newBlockingStub(trackerChannel);

                GetFileManifestResponse manifestResponse = trackerStub.getFileManifest(
                        GetFileManifestRequest.newBuilder()
                                .setFileId(requestedFilename)
                                .build()
                );

                FileManifestEntry manifest = manifestResponse.getFile();

                getFile(manifest.getFilename(), manifest);
                } finally {
                    trackerChannel.shutdown();
                }
        } catch (Exception e) {
            System.err.println("Download failed, Error: " + e);
            e.printStackTrace();
        }
    }

    public void getFile(String fileId, FileManifestEntry manifest) throws IOException {
        long startNanos = System.nanoTime();

        ManagedChannel trackerChannel = ManagedChannelBuilder
                .forAddress(trackerHost, trackerPort)
                .usePlaintext()
                .build();

        try {
            TrackerGrpc.TrackerBlockingStub trackerStub = TrackerGrpc.newBlockingStub(trackerChannel);
            ListPeersResponse peersResponse = trackerStub.handleListPeersRequest(
                    ListPeersRequest.newBuilder().build()
            );

            List<PeerEndpoint> peers = peersResponse.getUpPeersList();
            if (peers.isEmpty()) {
                throw new IllegalStateException("Tracker returned no live peers.");
            }

            int numChunks = manifest.getNumChunks();
            if (numChunks <= 0) {
                throw new IllegalArgumentException("Manifest must contain at least one chunk.");
            }

            int availabilityThreads = resolveAvailabilityThreadCount(peers.size());
            info("Parallelism: availabilityThreads=" + availabilityThreads
                    + " (peers=" + peers.size() + ", chunks=" + numChunks + ")");

            Map<Integer, List<PeerEndpoint>> chunkToPeer = Collections.synchronizedMap(new HashMap<>());
            collectAvailabilityParallel(fileId, peers, chunkToPeer, availabilityThreads);
            warnIfChunksMissingFromAvailability(numChunks, chunkToPeer);

            long networkDownloadNanos = downloadChunks(fileId, manifest, chunkToPeer);
            printNetworkSpeedSummary(manifest, networkDownloadNanos);
            printSpeedSummary(manifest, startNanos);
        } finally {
            shutdownChannelGracefully(trackerChannel);
            shutdownChannels();
        }
    }

    private int resolveAvailabilityThreadCount(int numPeers) {
        if (concurrency.maxAvailabilityParallelism() > 0) {
            return Math.max(1, Math.min(numPeers, concurrency.maxAvailabilityParallelism()));
        }
        return numPeers;
    }

    private void warnIfChunksMissingFromAvailability(
            int numChunks,
            Map<Integer, List<PeerEndpoint>> chunkToPeer
    ) {
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < numChunks; i++) {
            List<PeerEndpoint> owners = chunkToPeer.get(i);
            if (owners == null || owners.isEmpty()) {
                missing.add(i);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        int n = missing.size();
        StringBuilder detail = new StringBuilder();
        int show = Math.min(MISSING_CHUNK_WARNING_INDEX_CAP, n);
        for (int j = 0; j < show; j++) {
            if (j > 0) {
                detail.append(", ");
            }
            detail.append(missing.get(j));
        }
        if (n > show) {
            detail.append(", ... (").append(n).append(" total)");
        }
        System.err.println("WARNING: missing chunks detected — " + n
                + " chunk(s) have no available peer. Indices: " + detail);
    }

    private void collectAvailabilityParallel(
            String fileId,
            List<PeerEndpoint> peers,
            Map<Integer, List<PeerEndpoint>> chunkToPeer,
            int threadCount
    ) throws IOException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (PeerEndpoint peer : peers) {
                futures.add(executor.submit(() -> collectAvailability(fileId, peer, chunkToPeer)));
            }

            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Availability collection interrupted", e);
                } catch (Exception e) {
                    System.err.println("Failed to collect availability from peer: " + e.getMessage());
                }
            }
        } finally {
            executor.shutdown();
        }
    }

    private void collectAvailability(
            String fileId,
            PeerEndpoint peer,
            Map<Integer, List<PeerEndpoint>> chunkToPeer
    ) {
        try {
            ChunkBitmap bitmap = getOrCreateStub(peer).getAvailability(
                    FileRequest.newBuilder().setFileId(fileId).build()
            );

            Set<Integer> availableChunks = parseBitmap(bitmap.getBitset(), bitmap.getNumChunks());
            info("Peer " + peerKey(peer) + " has chunks: " + availableChunks);

            for (Integer chunkIdx : availableChunks) {
                chunkToPeer.computeIfAbsent(chunkIdx, k -> Collections.synchronizedList(new ArrayList<>())).add(peer);
            }
        } catch (Exception e) {
            System.err.println("Error querying peer " + peerKey(peer) + ": " + e.getMessage());
        }
    }

    /**
     * Distributes chunks across peers (least-assigned-so-far), then fires one
     * streaming GetChunks RPC per peer in parallel — one HTTP/2 stream per peer
     * instead of one stream per chunk.
     */
    private long downloadChunks(
            String fileId,
            FileManifestEntry manifest,
            Map<Integer, List<PeerEndpoint>> chunkToPeer
    ) throws IOException {
        int numChunks = manifest.getNumChunks();
        Map<Integer, ByteString> downloadedChunks = new ConcurrentHashMap<>();
        List<Integer> missingChunks = Collections.synchronizedList(new ArrayList<>());
        List<String> failedChunks = Collections.synchronizedList(new ArrayList<>());

        Map<PeerEndpoint, List<Integer>> peerToChunks = assignChunksToPeers(numChunks, chunkToPeer, missingChunks);

        if (!missingChunks.isEmpty()) {
            throw new IllegalStateException("Missing peers for chunks " + missingChunks);
        }

        info("Streaming from " + peerToChunks.size() + " peer(s)");
        ExecutorService executor = Executors.newFixedThreadPool(peerToChunks.size());

        try {
            long networkStartNanos = System.nanoTime();
            List<Future<?>> futures = new ArrayList<>();
            for (Map.Entry<PeerEndpoint, List<Integer>> entry : peerToChunks.entrySet()) {
                PeerEndpoint peer = entry.getKey();
                List<Integer> chunks = new ArrayList<>(entry.getValue());
                futures.add(executor.submit(() ->
                        downloadChunksFromPeer(fileId, peer, chunks, chunkToPeer, downloadedChunks, failedChunks)));
            }

            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Download interrupted", e);
                } catch (Exception e) {
                    throw new IOException("Parallel download failed", e);
                }
            }
            long networkDownloadNanos = System.nanoTime() - networkStartNanos;

            if (!failedChunks.isEmpty()) {
                throw new IllegalStateException("Some chunks failed: " + failedChunks);
            }

            assembleFile(downloadedChunks, manifest);
            return networkDownloadNanos;
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Greedy least-assigned-so-far assignment: each chunk goes to whichever of
     * its owners has the fewest chunks assigned in this round.
     */
    private Map<PeerEndpoint, List<Integer>> assignChunksToPeers(
            int numChunks,
            Map<Integer, List<PeerEndpoint>> chunkToPeer,
            List<Integer> missingChunks
    ) {
        Map<PeerEndpoint, List<Integer>> peerToChunks = new HashMap<>();
        Map<String, Integer> assignedCount = new HashMap<>();

        for (int i = 0; i < numChunks; i++) {
            List<PeerEndpoint> owners = chunkToPeer.get(i);
            if (owners == null || owners.isEmpty()) {
                missingChunks.add(i);
                continue;
            }
            PeerEndpoint chosen = owners.stream()
                    .min(Comparator.comparingInt(p -> assignedCount.getOrDefault(peerKey(p), 0)))
                    .orElseThrow();
            peerToChunks.computeIfAbsent(chosen, k -> new ArrayList<>()).add(i);
            assignedCount.merge(peerKey(chosen), 1, Integer::sum);
        }
        return peerToChunks;
    }

    /**
     * Opens a single GetChunks streaming RPC for all chunks assigned to this peer.
     * Falls back to individual GetChunk calls on alternate peers for any chunk
     * not received from the stream.
     */
    private void downloadChunksFromPeer(
            String fileId,
            PeerEndpoint peer,
            List<Integer> chunkIndices,
            Map<Integer, List<PeerEndpoint>> chunkToPeer,
            Map<Integer, ByteString> downloadedChunks,
            List<String> failedChunks
    ) {
        String key = peerKey(peer);
        Set<Integer> received = new HashSet<>();

        try {
            MultiChunkRequest request = MultiChunkRequest.newBuilder()
                    .setFileId(fileId)
                    .addAllChunkIndices(chunkIndices)
                    .build();
            Iterator<ChunkResponse> stream = getOrCreateStub(peer).getChunks(request);
            while (stream.hasNext()) {
                ChunkResponse resp = stream.next();
                downloadedChunks.put(resp.getChunkIndex(), resp.getData());
                received.add(resp.getChunkIndex());
                info("Downloaded chunk " + resp.getChunkIndex() + " from " + key);
            }
        } catch (Exception e) {
            System.err.println("Batch stream from " + key + " failed: " + e.getMessage());
        }

        // Fall back to individual GetChunk on other peers for anything not received
        for (int idx : chunkIndices) {
            if (received.contains(idx)) continue;
            boolean recovered = false;
            for (PeerEndpoint fallback : chunkToPeer.getOrDefault(idx, List.of())) {
                if (peerKey(fallback).equals(key)) continue;
                try {
                    ChunkResponse resp = getOrCreateStub(fallback).getChunk(
                            ChunkRequest.newBuilder()
                                    .setChunk(ChunkRef.newBuilder()
                                            .setFileId(fileId)
                                            .setChunkIndex(idx)
                                            .build())
                                    .build());
                    downloadedChunks.put(idx, resp.getData());
                    recovered = true;
                    info("Recovered chunk " + idx + " from fallback " + peerKey(fallback));
                    break;
                } catch (Exception ex) {
                    // try next fallback
                }
            }
            if (!recovered) {
                failedChunks.add("Chunk " + idx + " failed from all peers");
            }
        }
    }

    private static String peerKey(PeerEndpoint peer) {
        return peer.getIp() + ":" + peer.getPort();
    }

    private PeerConnection getOrCreateConnection(PeerEndpoint peer) {
        return peerConnectionCache.computeIfAbsent(peerKey(peer), k -> {
            ManagedChannel ch = ManagedChannelBuilder
                    .forAddress(peer.getIp(), peer.getPort())
                    .usePlaintext()
                    .build();
            return new PeerConnection(ch, PeerGrpc.newBlockingStub(ch));
        });
    }

    private PeerGrpc.PeerBlockingStub getOrCreateStub(PeerEndpoint peer) {
        return getOrCreateConnection(peer).stub();
    }

    private void shutdownChannels() {
        for (PeerConnection conn : peerConnectionCache.values()) {
            shutdownChannelGracefully(conn.channel());
        }
        peerConnectionCache.clear();
    }

    private static void shutdownChannelGracefully(ManagedChannel channel) {
        channel.shutdown();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
                channel.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Write chunks to disk using a large write buffer; ByteString.writeTo avoids an extra copy. */
    private void assembleFile(Map<Integer, ByteString> downloadedChunks, FileManifestEntry manifest) throws IOException {
        Path outputPath = Path.of("client", manifest.getFilename());
        Files.createDirectories(outputPath.getParent());

        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(outputPath), 8 * 1024 * 1024)) {
            for (int i = 0; i < manifest.getNumChunks(); i++) {
                ByteString chunk = downloadedChunks.get(i);
                if (chunk == null) {
                    throw new IllegalStateException("Missing downloaded chunk " + i);
                }
                chunk.writeTo(out);
            }
        }

        info("File written to " + outputPath);
    }

    private void printSpeedSummary(FileManifestEntry manifest, long startNanos) {
        long elapsedNanos = System.nanoTime() - startNanos;
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double mib = manifest.getFilesize() / (1024.0 * 1024.0);
        double mibPerSecond = elapsedSeconds == 0 ? 0 : mib / elapsedSeconds;

        System.out.printf("End-to-end: %.2f MiB in %.2f seconds (%.2f MiB/s)%n",
                mib,
                elapsedSeconds,
                mibPerSecond);
    }

    private void printNetworkSpeedSummary(FileManifestEntry manifest, long elapsedNanos) {
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double mib = manifest.getFilesize() / (1024.0 * 1024.0);
        double mibPerSecond = elapsedSeconds == 0 ? 0 : mib / elapsedSeconds;

        System.out.printf("Network/download engine: %.2f MiB in %.2f seconds (%.2f MiB/s)%n",
                mib,
                elapsedSeconds,
                mibPerSecond);
    }

    private void info(String message) {
        if (!quiet) {
            System.out.println(message);
        }
    }

    private Set<Integer> parseBitmap(ByteString bitset, int numChunks) {
        Set<Integer> chunks = new HashSet<>();
        byte[] bytes = bitset.toByteArray();

        int chunkIndex = 0;
        for (byte b : bytes) {
            for (int bit = 7; bit >= 0 && chunkIndex < numChunks; bit--) {
                int hasChunk = (b >> bit) & 1;
                if (hasChunk == 1) {
                    // Peer currently encodes bitmap in reverse: chunk (numChunks-1) ... chunk 0
                    int actualIndex = numChunks - 1 - chunkIndex;
                    chunks.add(actualIndex);
                }
                chunkIndex++;
            }
        }

        return chunks;
    }

}
