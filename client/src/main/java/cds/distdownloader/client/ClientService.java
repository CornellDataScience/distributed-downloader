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
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
            getFile("Test.bin");
        } catch (Exception e) {
            throw new RuntimeException("Download failed", e);
        }
    }

    public void getFile(String fileId) throws IOException {
        ManagedChannel trackerChannel = ManagedChannelBuilder
                .forAddress(trackerHost, trackerPort)
                .usePlaintext()
                .build();

        TrackerGrpc.TrackerBlockingStub trackerStub = TrackerGrpc.newBlockingStub(trackerChannel);

        ListPeersResponse peersResponse = trackerStub.listPeers(
                ListPeersRequest.newBuilder().build()
        );

        List<PeerEndpoint> peers = peersResponse.getPeersList();

        Map<Integer, PeerEndpoint> chunkToPeer = new HashMap<>();

        //how to get from manifest?
        int numChunks = -1

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
            for (Integer chunkIdx : availableChunks) {
                chunkToPeer.putIfAbsent(chunkIdx, peer);
            }

            peerChannel.shutdown();
        }

        trackerChannel.shutdown();

        //need to get actual contents of chunks with getChunk
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
