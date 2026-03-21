package cds.distdownloader.client;

import cds.distdownloader.proto.ChunkBitmap;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            System.err.println("Download failed");
        }
    }

    public void getFile(String fileId, FileManifest manifest) throws IOException {
        ManagedChannel trackerChannel = ManagedChannelBuilder
                .forAddress(trackerHost, trackerPort)
                .usePlaintext()
                .build();
        TrackerGrpc.TrackerBlockingStub trackerStub = TrackerGrpc.newBlockingStub(trackerChannel);


        ListPeersResponse peersResponse = trackerStub.handleListPeersRequest(
                ListPeersRequest.newBuilder().build()
        );

        List<PeerEndpoint> peers = peersResponse.getUpPeersList();

        Map<Integer, PeerEndpoint> chunkToPeer = new HashMap<>();
        int numChunks = manifest.chunkCount();

        for (PeerEndpoint peer : peers) {
            ManagedChannel peerChannel = ManagedChannelBuilder
                    .forAddress(peer.getIp(), peer.getPort())
                    .usePlaintext()
                    .build();

            PeerGrpc.PeerBlockingStub peerStub = PeerGrpc.newBlockingStub(peerChannel);

            ChunkBitmap bitmap = peerStub.getAvailability(
                    FileRequest.newBuilder().setFileId(fileId).build()
            );

            Set<Integer> availableChunks = parseBitmap(bitmap.getBitset(), bitmap.getNumChunks());
            System.out.println("availableChunks = " + availableChunks);
            for (Integer chunkIdx : availableChunks) {
                chunkToPeer.putIfAbsent(chunkIdx, peer);
            }



            peerChannel.shutdown();
        }

        trackerChannel.shutdown();

        //need to get actual contents of chunks with getChunk
        Map<Integer, byte[]> downloadedChunks = new HashMap<>();

        List<Integer> missingChunks = new ArrayList<>();
        for (int chunkIdx = 0; chunkIdx < numChunks; chunkIdx++) {
            PeerEndpoint peer = chunkToPeer.get(chunkIdx);
            if (peer == null) {
                missingChunks.add(chunkIdx);
                continue;
            }

            ManagedChannel peerChannel = ManagedChannelBuilder
                    .forAddress(peer.getIp(), peer.getPort())
                    .usePlaintext()
                    .build();

            try {
                PeerGrpc.PeerBlockingStub peerStub = PeerGrpc.newBlockingStub(peerChannel);

                ChunkResponse response = peerStub.getChunk(
                        ChunkRequest.newBuilder()
                                .setFileId(fileId)
                                .setChunkIndex(chunkIdx)
                                .build()
                );

                byte[] chunkBytes = response.getData().toByteArray();
                downloadedChunks.put(chunkIdx, chunkBytes);

                System.out.println("Downloaded chunk " + chunkIdx + " from "
                        + peer.getIp() + ":" + peer.getPort());
            } finally {
                peerChannel.shutdown();
            }
        }

        if (!missingChunks.isEmpty()) {
            throw new IllegalStateException("Missing peers for chunks " + missingChunks
                    + ". With randomized peer seeding, one peer may not cover the full file.");
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
                    chunks.add(chunkIndex);
                }
                chunkIndex++;
            }
        }

        return chunks;
    }
}
