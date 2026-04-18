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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ClientService {
    private final String trackerHost;
    private final int trackerPort;
    private final String manifestPath;

    public ClientService(String trackerHost, int trackerPort, String manifestPath) {
        this.trackerHost = trackerHost;
        this.trackerPort = trackerPort;
        this.manifestPath = manifestPath;
    }

    public void start() {
        System.out.println("trackerHost=" + trackerHost + ", trackerPort=" + trackerPort);
        System.out.println("manifestPath=" + manifestPath);

        try {
            FileManifest manifest = readManifest();
            getFile(manifest.filename(), manifest);
        } catch (Exception e) {
            System.err.println("Download failed, Error: " + e);
            e.printStackTrace();
        }
    }

    public void getFile(String fileId, FileManifest manifest) throws IOException {
        ManagedChannel trackerChannel = ManagedChannelBuilder
                .forAddress(trackerHost, trackerPort)
                .usePlaintext()
                .build();

        ExecutorService pool = null;
        Map<String, ManagedChannel> peerChannels = new HashMap<>();
        Map<String, PeerGrpc.PeerBlockingStub> peerStubs = new HashMap<>();

        try {
            TrackerGrpc.TrackerBlockingStub trackerStub = TrackerGrpc.newBlockingStub(trackerChannel);

            ListPeersResponse peersResponse = trackerStub.handleListPeersRequest(
                    ListPeersRequest.newBuilder().build()
            );

            List<PeerEndpoint> peers = dedupePeers(peersResponse.getUpPeersList());
            if (peers.isEmpty()) {
                throw new IllegalStateException("Tracker returned no live peers.");
            }

            int numChunks = manifest.chunkCount();

            // chunk index -> all peers that have that chunk
            Map<Integer, List<PeerEndpoint>> chunkToPeers = new HashMap<>();

            // Build reusable channels/stubs per peer
            for (PeerEndpoint peer : peers) {
                String key = endpointKey(peer);
                ManagedChannel peerChannel = ManagedChannelBuilder
                        .forAddress(peer.getIp(), peer.getPort())
                        .usePlaintext()
                        .build();

                peerChannels.put(key, peerChannel);
                peerStubs.put(key, PeerGrpc.newBlockingStub(peerChannel));
            }

            // Ask each peer which chunks it has
            for (PeerEndpoint peer : peers) {
                String key = endpointKey(peer);
                PeerGrpc.PeerBlockingStub peerStub = peerStubs.get(key);

                ChunkBitmap bitmap = peerStub.getAvailability(
                        FileRequest.newBuilder().setFileId(fileId).build()
                );

                Set<Integer> availableChunks = parseBitmap(bitmap.getBitset(), bitmap.getNumChunks());
                System.out.println("Peer " + key + " has chunks " + availableChunks);

                for (Integer chunkIdx : availableChunks) {
                    chunkToPeers.computeIfAbsent(chunkIdx, k -> new ArrayList<>()).add(peer);
                }
            }

            List<Integer> missingChunks = new ArrayList<>();
            for (int chunkIdx = 0; chunkIdx < numChunks; chunkIdx++) {
                List<PeerEndpoint> owners = chunkToPeers.get(chunkIdx);
                if (owners == null || owners.isEmpty()) {
                    missingChunks.add(chunkIdx);
                }
            }

            if (!missingChunks.isEmpty()) {
                throw new IllegalStateException(
                        "No peer advertised chunks " + missingChunks + " for file " + fileId
                );
            }

            Map<Integer, byte[]> downloadedChunks = new ConcurrentHashMap<>();
            List<String> failures = Collections.synchronizedList(new ArrayList<>());

            int threadCount = Math.max(1, Math.min(numChunks, peers.size() * 2));
            pool = Executors.newFixedThreadPool(threadCount);

            List<Future<?>> futures = new ArrayList<>();

            for (int chunkIdx = 0; chunkIdx < numChunks; chunkIdx++) {
                final int idx = chunkIdx;
                futures.add(pool.submit(() -> {
                    List<PeerEndpoint> owners = chunkToPeers.get(idx);
                    if (owners == null || owners.isEmpty()) {
                        failures.add("Chunk " + idx + " has no owners.");
                        return;
                    }

                    Exception lastError = null;
                    int startOwner = idx % owners.size(); // simple round-robin

                    for (int attempt = 0; attempt < owners.size(); attempt++) {
                        PeerEndpoint peer = owners.get((startOwner + attempt) % owners.size());
                        String key = endpointKey(peer);

                        try {
                            PeerGrpc.PeerBlockingStub peerStub = peerStubs.get(key);
                            if (peerStub == null) {
                                throw new IllegalStateException("No stub found for peer " + key);
                            }

                            ChunkRef request = ChunkRef.newBuilder()
                                    .setFileId(fileId)
                                    .setChunkIndex(idx)
                                    .build();

                            ChunkResponse response = peerStub.getChunk(
                                    ChunkRequest.newBuilder()
                                            .setChunk(request)
                                            .build()
                            );

                            byte[] chunkBytes = response.getData().toByteArray();
                            downloadedChunks.put(idx, chunkBytes);

                            System.out.println("Downloaded chunk " + idx + " from "
                                    + peer.getIp() + ":" + peer.getPort());
                            return;
                        } catch (Exception e) {
                            lastError = e;
                            System.err.println("Failed chunk " + idx + " from "
                                    + peer.getIp() + ":" + peer.getPort()
                                    + " -> " + e.getMessage());
                        }
                    }

                    String message = "All owners failed for chunk " + idx;
                    if (lastError != null && lastError.getMessage() != null) {
                        message += " (last error: " + lastError.getMessage() + ")";
                    }
                    failures.add(message);
                }));
            }

            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Download interrupted", e);
                } catch (ExecutionException e) {
                    throw new IOException("Parallel download task failed", e);
                }
            }

            if (!failures.isEmpty()) {
                throw new IllegalStateException("Download errors: " + failures);
            }

            assembleFile(downloadedChunks, manifest);
        } finally {
            if (pool != null) {
                pool.shutdown();
            }

            for (ManagedChannel channel : peerChannels.values()) {
                channel.shutdown();
            }

            trackerChannel.shutdown();
        }
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

    private FileManifest readManifest() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.readValue(Path.of(manifestPath).toFile(), FileManifest.class);
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

    private List<PeerEndpoint> dedupePeers(List<PeerEndpoint> peers) {
        Map<String, PeerEndpoint> unique = new LinkedHashMap<>();
        for (PeerEndpoint peer : peers) {
            unique.put(endpointKey(peer), peer);
        }
        return new ArrayList<>(unique.values());
    }

    private String endpointKey(PeerEndpoint peer) {
        return peer.getIp() + ":" + peer.getPort();
    }
}