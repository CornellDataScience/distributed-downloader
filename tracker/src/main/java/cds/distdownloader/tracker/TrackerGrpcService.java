package cds.distdownloader.tracker;

import cds.distdownloader.proto.*;
import io.grpc.stub.StreamObserver;
import java.util.HashSet;
import org.springframework.stereotype.Service;

import java.util.*;
import java.time.Instant;

@Service
public class TrackerGrpcService extends TrackerGrpc.TrackerImplBase {

    // https://www.geeksforgeeks.org/java/java-time-instant-class-in-java/
    // who is alive; peer -> last seen
    private final Map<String, PeerEndpoint> peerToEndpoint = new HashMap<>();
    private final Map<PeerEndpoint, Instant> peerMap = new HashMap<>();
    private final Map<String, Set<String>> peerToFiles = new HashMap<>();
    private final Map<String, Set<String>> fileToPeers = new HashMap<>();

    //Peer will send a heart beat to tracker(5s) , if no heart beat is seen in the last 10 second delete them
    @Override
    public synchronized void handleHeartbeatRequest(HeartbeatRequest request,
            StreamObserver<HeartbeatResponse> responseObserver) {
        PeerEndpoint peerEndPoint = request.getEndpoint();
        peerMap.put(peerEndPoint, Instant.now());

        String peerId = peerEndPoint.getId();
        Set<String> fileIds = new HashSet<>(request.getFileIdsList());

        // add peer to peerToEndpoint
        peerToEndpoint.put(peerId, peerEndPoint);

        // add files to peerToFiles
        peerToFiles.put(peerId, fileIds);

        // add peer to fileToPeers
        for (String fileId : fileIds) {
            fileToPeers.computeIfAbsent(fileId, k -> new HashSet<>()).add(peerId);
        }

        HeartbeatResponse resp = HeartbeatResponse.newBuilder()
                .setAck(Ack.newBuilder().setOk(true).build()).build();
        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    @Override
    /*
      Loop through all peers in the map, and delete all that were 10+ seconds
      from the current instant.
      Then, we will send out a list version of the keySet
     */
    public synchronized void handleListPeersRequest(ListPeersRequest request,
            StreamObserver<ListPeersResponse> responseObserver) {
        Set<PeerEndpoint> peers = new HashSet<>(peerMap.keySet());

        Instant timeNow = Instant.now();
        Instant cutoff = timeNow.minusSeconds(10);
        for (PeerEndpoint peer : peers) {
            Instant lastSeen = peerMap.get(peer);
            if (lastSeen.isBefore(cutoff)) {
                peerMap.remove(peer);
            }
        }

        List<PeerEndpoint> peerList = new ArrayList<>(peerMap.keySet());
        ListPeersResponse response = ListPeersResponse.newBuilder().addAllUpPeers(peerList).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private synchronized List<String> getPeersByFile(String fileId) {
        List<String> peers = new ArrayList<>();

        Instant timeNow = Instant.now();
        Instant cutoff = timeNow.minusSeconds(10);
        for (String peerId : fileToPeers.getOrDefault(fileId, Collections.emptySet())) {
            PeerEndpoint endpoint = peerToEndpoint.get(peerId);
            Instant lastSeen = peerMap.get(endpoint);
            if (lastSeen != null && !lastSeen.isBefore(cutoff)) {
                peers.add(peerId);
            }
        }

        return peers;
    }
}