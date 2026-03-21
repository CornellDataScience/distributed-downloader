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

    // assigns id:port --> tracker assigned peerID
    private final Map<String, String> endpointToPeerID = new HashMap<>();

    private Integer nextUUID = 0;

    //Peer will send a heart beat to tracker(5s) , if no heart beat is seen in the last 10 second delete them
    @Override
    public synchronized void handleHeartbeatRequest(HeartbeatRequest request,
            StreamObserver<HeartbeatResponse> responseObserver) {
        PeerEndpoint peerEndPoint = request.getEndpoint();
        // assign peerId if new peer, otherwise use existing Id
        String peerId;

        if (peerMap.containsKey(peerEndPoint)){
            peerId = peerEndPoint.getId();
        }
        else {
            // assign new peer a given UUID
            nextUUID += 1;
            peerId = nextUUID.toString();
        }

        peerToEndpoint.put(peerId, peerEndPoint);
        peerMap.put(peerEndPoint, Instant.now());

        Set<String> fileIds = new HashSet<>(request.getFileIdsList());

        // add files to peerToFiles
        peerToFiles.put(peerId, fileIds);

        // add peer to fileToPeers
        for (String fileId : fileIds) {
            fileToPeers.computeIfAbsent(fileId, k -> new HashSet<>()).add(peerId);
        }

        HeartbeatResponse resp = HeartbeatResponse.newBuilder()
                .setAck(Ack.newBuilder().setOk(true).build()).setPeerId(peerId).build();
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