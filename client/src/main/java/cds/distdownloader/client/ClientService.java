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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ClientService {
    /** When {@link ClientConcurrencyConfig#maxDownloadParallelism()} is 0, use at least this many download threads. */
    private static final int DEFAULT_MIN_DOWNLOAD_THREADS = 32;
    /** When max download is 0, use this multiple of peer count (was 2, raised for less restriction). */
    private static final int DEFAULT_DOWNLOAD_THREADS_PER_PEER = 8;
    /** Max missing chunk indices listed in the early availability warning (rest summarized). */
    private static final int MISSING_CHUNK_WARNING_INDEX_CAP = 40;

    private final String trackerHost;
    private final int trackerPort;
    private final String manifestPath;
    private final String requestedFilename;
    private final ClientConcurrencyConfig concurrency;
    private final boolean quiet;
    private final Map<String, ManagedChannel> peerChannelCache = new ConcurrentHashMap<>();
    /** In-flight {@code getChunk} calls per peer for the current file download. */
    private final ConcurrentHashMap<String, AtomicInteger> inflightDownloadsByPeer = new ConcurrentHashMap<>();

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

            int numChunks = manifest.resolvedChunkCount();
            if (numChunks <= 0) {
                throw new IllegalArgumentException("Manifest must contain at least one chunk.");
            }

            int availabilityThreads = resolveAvailabilityThreadCount(peers.size());
            int downloadThreads = resolveDownloadThreadCount(numChunks, peers.size());
            info("Parallelism: availabilityThreads=" + availabilityThreads
                    + ", downloadThreads=" + downloadThreads
                    + " (peers=" + peers.size() + ", chunks=" + numChunks + ")");

            Map<Integer, List<PeerEndpoint>> chunkToPeer = Collections.synchronizedMap(new HashMap<>());
            collectAvailabilityParallel(fileId, peers, chunkToPeer, availabilityThreads);
            warnIfChunksMissingFromAvailability(numChunks, chunkToPeer);

            downloadChunks(fileId, manifest, chunkToPeer, downloadThreads);
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
        ManagedChannel peerChannel = getOrCreateChannel(peer);
        try {
            PeerGrpc.PeerBlockingStub peerStub = PeerGrpc.newBlockingStub(peerChannel);

            ChunkBitmap bitmap = peerStub.getAvailability(
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

    private void downloadChunks(
            String fileId,
            FileManifest manifest,
            Map<Integer, List<PeerEndpoint>> chunkToPeer,
            int threadCount
    ) throws IOException {
        int numChunks = manifest.resolvedChunkCount();
        inflightDownloadsByPeer.clear();
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

        List<PeerEndpoint> ownersSorted = new ArrayList<>(owners);
        ownersSorted.sort(Comparator.comparing(PeerEndpoint::getIp).thenComparingInt(PeerEndpoint::getPort));
        int startIdx = indexOfLeastInFlight(ownersSorted);

        Exception lastError = null;
        for (int attempt = 0; attempt < ownersSorted.size(); attempt++) {
            PeerEndpoint peer = ownersSorted.get((startIdx + attempt) % ownersSorted.size());
            String key = peerKey(peer);
            AtomicInteger inflight = inflightDownloadsByPeer.computeIfAbsent(key, k -> new AtomicInteger(0));
            inflight.incrementAndGet();
            try {
                ManagedChannel peerChannel = getOrCreateChannel(peer);
                PeerGrpc.PeerBlockingStub peerStub = PeerGrpc.newBlockingStub(peerChannel);
                ChunkRef request = ChunkRef.newBuilder()
                        .setFileId(fileId)
                        .setChunkIndex(chunkIdx)
                        .build();

                info("Requesting chunk " + chunkIdx + " from " + key);

                ChunkResponse response = peerStub.getChunk(
                        ChunkRequest.newBuilder()
                                .setChunk(request)
                                .build()
                );

                downloadedChunks.put(chunkIdx, response.getData().toByteArray());
                info("Downloaded chunk " + chunkIdx + " from " + key);
                return;
            } catch (Exception e) {
                lastError = e;
                System.err.println("Failed to get chunk " + chunkIdx + " from " + key + ": " + e.getMessage());
            } finally {
                inflight.decrementAndGet();
            }
        }

        failedChunks.add("Chunk " + chunkIdx + " failed from all owners"
                + (lastError != null ? ": " + lastError.getMessage() : ""));
    }

    private static String peerKey(PeerEndpoint peer) {
        return peer.getIp() + ":" + peer.getPort();
    }

    private int inflightCount(PeerEndpoint peer) {
        AtomicInteger n = inflightDownloadsByPeer.get(peerKey(peer));
        return n == null ? 0 : n.get();
    }

    private int indexOfLeastInFlight(List<PeerEndpoint> sortedByAddress) {
        int bestIdx = 0;
        int minLoad = Integer.MAX_VALUE;
        for (int i = 0; i < sortedByAddress.size(); i++) {
            int load = inflightCount(sortedByAddress.get(i));
            if (load < minLoad) {
                minLoad = load;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private ManagedChannel getOrCreateChannel(PeerEndpoint peer) {
        return peerChannelCache.computeIfAbsent(peerKey(peer), k ->
                ManagedChannelBuilder
                        .forAddress(peer.getIp(), peer.getPort())
                        .usePlaintext()
                        .build()
        );
    }

    private void shutdownChannels() {
        for (ManagedChannel channel : peerChannelCache.values()) {
            shutdownChannelGracefully(channel);
        }
        peerChannelCache.clear();
        inflightDownloadsByPeer.clear();
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

    private void assembleFile(Map<Integer, byte[]> downloadedChunks, FileManifest manifest) throws IOException {
        Path outputPath = Path.of("client", manifest.filename());

        java.nio.file.Files.createDirectories(outputPath.getParent());

        try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(outputPath)) {
            for (int i = 0; i < manifest.resolvedChunkCount(); i++) {
                byte[] chunk = downloadedChunks.get(i);
                if (chunk == null) {
                    throw new IllegalStateException("Missing downloaded chunk " + i);
                }
                out.write(chunk);
            }
        }

        info("File written to " + outputPath);
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

    private void info(String message) {
        if (!quiet) {
            System.out.println(message);
        }
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
