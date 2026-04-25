package cds.distdownloader.client;

import cds.distdownloader.proto.ChunkBitmap;
import cds.distdownloader.proto.ChunkRef;
import cds.distdownloader.proto.ChunkRequest;
import cds.distdownloader.proto.ChunkResponse;
import cds.distdownloader.proto.FileRequest;
import cds.distdownloader.proto.ListPeersRequest;
import cds.distdownloader.proto.ListPeersResponse;
import cds.distdownloader.proto.PeerEndpoint;
import cds.distdownloader.proto.PeerGrpc;
import cds.distdownloader.proto.TrackerGrpc;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ClientService {
    /** When {@link ClientConcurrencyConfig#maxDownloadParallelism()} is 0, use at least this many download threads. */
    private static final int DEFAULT_MIN_DOWNLOAD_THREADS = 32;
    /** When max download is 0, use this multiple of peer count (was 2, raised for less restriction). */
    private static final int DEFAULT_DOWNLOAD_THREADS_PER_PEER = 8;

    private final String trackerHost;
    private final int trackerPort;
    private final String manifestPath;
    private final String requestedFilename;
    private final ClientConcurrencyConfig concurrency;
    private final Map<String, ManagedChannel> peerChannelCache = new ConcurrentHashMap<>();

    public ClientService(String trackerHost, int trackerPort, String manifestPath, String requestedFilename) {
        this(trackerHost, trackerPort, manifestPath, requestedFilename, ClientConcurrencyConfig.DEFAULT);
    }

    public ClientService(
            String trackerHost,
            int trackerPort,
            String manifestPath,
            String requestedFilename,
            ClientConcurrencyConfig concurrency
    ) {
        this.trackerHost = trackerHost;
        this.trackerPort = trackerPort;
        this.manifestPath = manifestPath;
        this.requestedFilename = requestedFilename;
        this.concurrency = concurrency;
    }

    public void start() {
        System.out.println("trackerHost=" + trackerHost + ", trackerPort=" + trackerPort);
        System.out.println("manifestPath=" + manifestPath);
        System.out.println("requestedFilename=" + (requestedFilename == null ? "<default>" : requestedFilename));

        try {
            FileManifest manifest = readManifestCatalog().findByFilename(requestedFilename);
            getFile(manifest.filename(), manifest);
        } catch (Exception e) {
            System.err.println("Download failed, Error: " + e);
            e.printStackTrace();
        }
    }

    public void getFile(String fileId, FileManifest manifest) throws IOException {
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

            int numChunks = manifest.chunkCount();
            if (numChunks <= 0) {
                throw new IllegalArgumentException("Manifest must contain at least one chunk.");
            }

            int availabilityThreads = resolveAvailabilityThreadCount(peers.size());
            int downloadThreads = resolveDownloadThreadCount(numChunks, peers.size());
            System.out.println("Parallelism: availabilityThreads=" + availabilityThreads
                    + ", downloadThreads=" + downloadThreads
                    + " (peers=" + peers.size() + ", chunks=" + numChunks + ")");

            Map<Integer, List<PeerEndpoint>> chunkToPeer = Collections.synchronizedMap(new HashMap<>());
            collectAvailabilityParallel(fileId, peers, chunkToPeer, availabilityThreads);

            downloadChunks(fileId, manifest, chunkToPeer, downloadThreads);
            printSpeedSummary(manifest, startNanos);
        } finally {
            trackerChannel.shutdown();
            shutdownChannels();
        }
    }

    private int resolveAvailabilityThreadCount(int numPeers) {
        if (concurrency.maxAvailabilityParallelism() > 0) {
            return Math.max(1, Math.min(numPeers, concurrency.maxAvailabilityParallelism()));
        }
        // Default: one worker per live peer (no cap at 16).
        return numPeers;
    }

    private int resolveDownloadThreadCount(int numChunks, int numPeers) {
        if (concurrency.maxDownloadParallelism() > 0) {
            return Math.max(1, Math.min(numChunks, concurrency.maxDownloadParallelism()));
        }
        // Default: allow more in-flight work than the old (2 × peers) cap.
        int derived = Math.max(DEFAULT_MIN_DOWNLOAD_THREADS, numPeers * DEFAULT_DOWNLOAD_THREADS_PER_PEER);
        return Math.max(1, Math.min(numChunks, derived));
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
        ManagedChannel peerChannel = getOrCreateChannel(peer);
        try {
            PeerGrpc.PeerBlockingStub peerStub = PeerGrpc.newBlockingStub(peerChannel);

            ChunkBitmap bitmap = peerStub.getAvailability(
                    FileRequest.newBuilder().setFileId(fileId).build()
            );

            Set<Integer> availableChunks = parseBitmap(bitmap.getBitset(), bitmap.getNumChunks());
            System.out.println("Peer " + peer.getIp() + ":" + peer.getPort() + " has chunks: " + availableChunks);

            for (Integer chunkIdx : availableChunks) {
                chunkToPeer.computeIfAbsent(chunkIdx, k -> Collections.synchronizedList(new ArrayList<>())).add(peer);
            }
        } catch (Exception e) {
            System.err.println("Error querying peer " + peer.getIp() + ":" + peer.getPort() + ": " + e.getMessage());
        }
    }

    private void downloadChunks(
            String fileId,
            FileManifest manifest,
            Map<Integer, List<PeerEndpoint>> chunkToPeer,
            int threadCount
    ) throws IOException {
        int numChunks = manifest.chunkCount();
        Map<Integer, byte[]> downloadedChunks = new ConcurrentHashMap<>();
        List<Integer> missingChunks = Collections.synchronizedList(new ArrayList<>());
        List<String> failedChunks = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int chunkIdx = 0; chunkIdx < numChunks; chunkIdx++) {
                final int idx = chunkIdx;
                futures.add(executor.submit(() -> downloadChunk(
                        fileId,
                        idx,
                        chunkToPeer,
                        downloadedChunks,
                        missingChunks,
                        failedChunks
                )));
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

            if (!missingChunks.isEmpty()) {
                throw new IllegalStateException("Missing peers for chunks " + missingChunks);
            }

            if (!failedChunks.isEmpty()) {
                throw new IllegalStateException("Some chunks failed: " + failedChunks);
            }

            assembleFile(downloadedChunks, manifest);
        } finally {
            executor.shutdown();
        }
    }

    private void downloadChunk(
            String fileId,
            int chunkIdx,
            Map<Integer, List<PeerEndpoint>> chunkToPeer,
            Map<Integer, byte[]> downloadedChunks,
            List<Integer> missingChunks,
            List<String> failedChunks
    ) {
        List<PeerEndpoint> owners = chunkToPeer.get(chunkIdx);
        if (owners == null || owners.isEmpty()) {
            missingChunks.add(chunkIdx);
            return;
        }

        int start = chunkIdx % owners.size();
        Exception lastError = null;

        for (int attempt = 0; attempt < owners.size(); attempt++) {
            PeerEndpoint peer = owners.get((start + attempt) % owners.size());
            String peerKey = peer.getIp() + ":" + peer.getPort();

            try {
                ManagedChannel peerChannel = getOrCreateChannel(peer);
                PeerGrpc.PeerBlockingStub peerStub = PeerGrpc.newBlockingStub(peerChannel);
                ChunkRef request = ChunkRef.newBuilder()
                        .setFileId(fileId)
                        .setChunkIndex(chunkIdx)
                        .build();

                System.out.println("Requesting chunk " + chunkIdx + " from " + peerKey);

                ChunkResponse response = peerStub.getChunk(
                        ChunkRequest.newBuilder()
                                .setChunk(request)
                                .build()
                );

                downloadedChunks.put(chunkIdx, response.getData().toByteArray());
                System.out.println("Downloaded chunk " + chunkIdx + " from " + peerKey);
                return;
            } catch (Exception e) {
                lastError = e;
                System.err.println("Failed to get chunk " + chunkIdx + " from " + peerKey + ": " + e.getMessage());
            }
        }

        failedChunks.add("Chunk " + chunkIdx + " failed from all owners"
                + (lastError != null ? ": " + lastError.getMessage() : ""));
    }

    private ManagedChannel getOrCreateChannel(PeerEndpoint peer) {
        String peerKey = peer.getIp() + ":" + peer.getPort();
        return peerChannelCache.computeIfAbsent(peerKey, k ->
                ManagedChannelBuilder
                        .forAddress(peer.getIp(), peer.getPort())
                        .usePlaintext()
                        .build()
        );
    }

    private void shutdownChannels() {
        for (ManagedChannel channel : peerChannelCache.values()) {
            channel.shutdown();
        }
        peerChannelCache.clear();
    }

    private void assembleFile(Map<Integer, byte[]> downloadedChunks, FileManifest manifest) throws IOException {
        Path outputPath = Path.of("client", manifest.filename());

        java.nio.file.Files.createDirectories(outputPath.getParent());

        try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(outputPath)) {
            for (int i = 0; i < manifest.chunkCount(); i++) {
                byte[] chunk = downloadedChunks.get(i);
                if (chunk == null) {
                    throw new IllegalStateException("Missing downloaded chunk " + i);
                }
                out.write(chunk);
            }
        }

        System.out.println("File written to " + outputPath);
    }

    private void printSpeedSummary(FileManifest manifest, long startNanos) {
        long elapsedNanos = System.nanoTime() - startNanos;
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double mib = manifest.filesize() / (1024.0 * 1024.0);
        double mibPerSecond = elapsedSeconds == 0 ? 0 : mib / elapsedSeconds;

        System.out.printf("Downloaded %.2f MiB in %.2f seconds (%.2f MiB/s)%n",
                mib,
                elapsedSeconds,
                mibPerSecond);
    }

    private FileManifestCatalog readManifestCatalog() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.readValue(Path.of(manifestPath).toFile(), FileManifestCatalog.class);
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
