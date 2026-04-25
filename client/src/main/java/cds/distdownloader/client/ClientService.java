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
    private final String trackerHost;
    private final int trackerPort;
    private final String manifestPath;
    private final String requestedFilename;

    public ClientService(String trackerHost, int trackerPort, String manifestPath, String requestedFilename) {
        this.trackerHost = trackerHost;
        this.trackerPort = trackerPort;
        this.manifestPath = manifestPath;
        this.requestedFilename = requestedFilename;
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

            Map<Integer, List<PeerEndpoint>> chunkToPeer = new HashMap<>();
            for (PeerEndpoint peer : peers) {
                collectAvailability(fileId, peer, chunkToPeer);
            }

            downloadChunks(fileId, manifest, peers, chunkToPeer);
        } finally {
            trackerChannel.shutdown();
        }
    }

    private void collectAvailability(
            String fileId,
            PeerEndpoint peer,
            Map<Integer, List<PeerEndpoint>> chunkToPeer
    ) {
        ManagedChannel peerChannel = ManagedChannelBuilder
                .forAddress(peer.getIp(), peer.getPort())
                .usePlaintext()
                .build();

        try {
            PeerGrpc.PeerBlockingStub peerStub = PeerGrpc.newBlockingStub(peerChannel);

            ChunkBitmap bitmap = peerStub.getAvailability(
                    FileRequest.newBuilder().setFileId(fileId).build()
            );

            Set<Integer> availableChunks = parseBitmap(bitmap.getBitset(), bitmap.getNumChunks());
            System.out.println("availableChunks = " + availableChunks);

            for (Integer chunkIdx : availableChunks) {
                chunkToPeer.computeIfAbsent(chunkIdx, k -> new ArrayList<>()).add(peer);
                System.out.println("I just got " + chunkIdx + " from " + peer.getIp() + ":" + peer.getPort());
            }
        } finally {
            peerChannel.shutdown();
        }
    }

    private void downloadChunks(
            String fileId,
            FileManifest manifest,
            List<PeerEndpoint> peers,
            Map<Integer, List<PeerEndpoint>> chunkToPeer
    ) throws IOException {
        int numChunks = manifest.chunkCount();
        Map<Integer, byte[]> downloadedChunks = new ConcurrentHashMap<>();
        List<Integer> missingChunks = Collections.synchronizedList(new ArrayList<>());
        List<String> failedChunks = Collections.synchronizedList(new ArrayList<>());

        int threadCount = Math.min(numChunks, peers.size() * 2);
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

            ManagedChannel peerChannel = ManagedChannelBuilder
                    .forAddress(peer.getIp(), peer.getPort())
                    .usePlaintext()
                    .build();

            try {
                PeerGrpc.PeerBlockingStub peerStub = PeerGrpc.newBlockingStub(peerChannel);
                ChunkRef request = ChunkRef.newBuilder()
                        .setFileId(fileId)
                        .setChunkIndex(chunkIdx)
                        .build();

                System.out.println("Requesting chunk " + chunkIdx + " from "
                        + peer.getIp() + ":" + peer.getPort());

                ChunkResponse response = peerStub.getChunk(
                        ChunkRequest.newBuilder()
                                .setChunk(request)
                                .build()
                );

                downloadedChunks.put(chunkIdx, response.getData().toByteArray());
                System.out.println("Downloaded chunk " + chunkIdx + " from "
                        + peer.getIp() + ":" + peer.getPort());
                return;
            } catch (Exception e) {
                lastError = e;
            } finally {
                peerChannel.shutdown();
            }
        }

        failedChunks.add("Chunk " + chunkIdx + " failed from all owners"
                + (lastError != null ? ": " + lastError.getMessage() : ""));
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
