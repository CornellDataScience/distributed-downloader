package cds.distdownloader.tracker;

import cds.distdownloader.proto.*;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class TrackerGrpcService extends TrackerGrpc.TrackerImplBase {

    // peerId -> latest endpoint
    private final Map<String, PeerEndpoint> peerToEndpoint = new HashMap<>();

    // peerId -> last heartbeat time
    private final Map<String, Instant> peerLastSeen = new HashMap<>();

    // peerId -> files this peer currently advertises
    private final Map<String, Set<String>> peerToFiles = new HashMap<>();

    // fileId -> peerIds that advertise it
    private final Map<String, Set<String>> fileToPeers = new HashMap<>();

    private int nextUUID = 0;

    @Override
    public synchronized void handleHeartbeatRequest(
            HeartbeatRequest request,
            StreamObserver<HeartbeatResponse> responseObserver
    ) {
        PeerEndpoint incoming = request.getEndpoint();

        String peerId;
        if (!incoming.getId().equals("-1")) {
            peerId = incoming.getId();
        } else {
            nextUUID += 1;
            peerId = Integer.toString(nextUUID);
        }

        // Canonicalize endpoint so tracker always stores the assigned peerId
        PeerEndpoint canonicalEndpoint = PeerEndpoint.newBuilder()
                .setId(peerId)
                .setIp(incoming.getIp())
                .setPort(incoming.getPort())
                .build();

        peerToEndpoint.put(peerId, canonicalEndpoint);
        peerLastSeen.put(peerId, Instant.now());

        Set<String> newFiles = new HashSet<>(request.getFileIdsList());
        Set<String> oldFiles = peerToFiles.getOrDefault(peerId, Collections.emptySet());

        // Remove peer from files it no longer serves
        for (String oldFile : oldFiles) {
            if (!newFiles.contains(oldFile)) {
                Set<String> peersForFile = fileToPeers.get(oldFile);
                if (peersForFile != null) {
                    peersForFile.remove(peerId);
                    if (peersForFile.isEmpty()) {
                        fileToPeers.remove(oldFile);
                    }
                }
            }
        }

        // Add/update files for this peer
        peerToFiles.put(peerId, newFiles);
        for (String fileId : newFiles) {
            fileToPeers.computeIfAbsent(fileId, k -> new HashSet<>()).add(peerId);
        }

        HeartbeatResponse resp = HeartbeatResponse.newBuilder()
                .setAck(Ack.newBuilder().setOk(true).build())
                .setPeerId(peerId)
                .build();

        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    @Override
    public synchronized void handleListPeersRequest(
            ListPeersRequest request,
            StreamObserver<ListPeersResponse> responseObserver
    ) {
        pruneDeadPeers();

        ListPeersResponse response = ListPeersResponse.newBuilder()
                .addAllUpPeers(peerToEndpoint.values())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    // Optional helper if later you add a "list peers for file" RPC
    private synchronized List<PeerEndpoint> getPeersByFile(String fileId) {
        pruneDeadPeers();

        List<PeerEndpoint> peers = new ArrayList<>();
        for (String peerId : fileToPeers.getOrDefault(fileId, Collections.emptySet())) {
            PeerEndpoint endpoint = peerToEndpoint.get(peerId);
            if (endpoint != null) {
                peers.add(endpoint);
            }
        }
        return peers;
    }

    private synchronized void pruneDeadPeers() {
        Instant cutoff = Instant.now().minusSeconds(10);
        List<String> deadPeerIds = new ArrayList<>();

        for (Map.Entry<String, Instant> entry : peerLastSeen.entrySet()) {
            if (entry.getValue().isBefore(cutoff)) {
                deadPeerIds.add(entry.getKey());
            }
        }

        for (String peerId : deadPeerIds) {
            removePeer(peerId);
        }
    }

    public synchronized void removePeer(String peerId) {
        peerLastSeen.remove(peerId);
        peerToEndpoint.remove(peerId);

        Set<String> files = peerToFiles.remove(peerId);
        if (files == null) {
            return;
        }

        for (String fileId : files) {
            Set<String> peersForFile = fileToPeers.get(fileId);
            if (peersForFile != null) {
                peersForFile.remove(peerId);
                if (peersForFile.isEmpty()) {
                    fileToPeers.remove(fileId);
                }
            }
        }
    }
}