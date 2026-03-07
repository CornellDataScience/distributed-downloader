package cds.distdownloader.tracker;

import cds.distdownloader.proto.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.HashSet;
import org.springframework.stereotype.Service;

import java.util.*;
import java.time.Instant;

@Service
public class TrackerGrpcService extends TrackerGrpc.TrackerImplBase {

    // https://www.geeksforgeeks.org/java/java-time-instant-class-in-java/
    // who is alive; peer -> last seen
    private Map<String, PeerEndpoint> peerToEndpoint = new HashMap<>();
    private Map<PeerEndpoint, Instant> peerMap = new HashMap<>();
    private Map<String, Set<String>> peerToFiles = new HashMap<>();
    private Map<String, Set<String>> fileToPeers = new HashMap<>();

    //Peer will send a heart beat to tracker(5s) , if no heart beat is seen in the last 10 second delete them
    @Override
    public synchronized void heartbeat(HeartbeatRequest request,
            StreamObserver<HeartbeatResponse> responseObserver) {
        PeerEndpoint peerEndPoint = request.getEndpoint();
        peerMap.put(peerEndPoint, Instant.now());

        String peer_id = peerEndPoint.getId();
        Set<String> file_ids = new HashSet<String>(request.getFileIdsList());

        // add peer to peerToEndpoint
        peerToEndpoint.put(peer_id, peerEndPoint);

        // add files to peerToFiles
        peerToFiles.put(peer_id, file_ids);

        // add peer to fileToPeers
        for (String file_id : file_ids) {
            Set<String> filePeers = fileToPeers.getOrDefault(file_id, new HashSet<String>());
            filePeers.add(peer_id);
        }

        HeartbeatResponse resp = HeartbeatResponse.newBuilder()
                .setAck(Ack.newBuilder().setOk(true).build()).build();
        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    @Override
    /**
     * Loop through all peers in the map, and delete all that were 10+ seconds
     * from the current instant.
     * Then, we will send out a list version of the keySet
     */
    public synchronized void listPeers(ListPeersRequest request,
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

    private synchronized List<String> GetPeersByFile(String fileId) {
        List<String> peers = new ArrayList<String>();

        Instant timeNow = Instant.now();
        Instant cutoff = timeNow.minusSeconds(10);
        for (String peerId : fileToPeers.get(fileId)) {
            PeerEndpoint endpoint = peerToEndpoint.get(peerId);
            if (peerMap.get(endpoint).isBefore(cutoff)) {
                peers.add(peerId);
            }
        }

        return peers;
    }
}