package cds.distdownloader.tracker;

import cds.distdownloader.proto.Ack;
import cds.distdownloader.proto.PeerEndpoint;
import cds.distdownloader.proto.Peer;
import cds.distdownloader.v1.PeerGrpc;
import cds.distdownloader.v1.Tracker.AllPeers;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;

@Service
public class TrackerGrpcService extends PeerGrpc.PeerImplBase {

    @Override
    public void register(PeerEndpoint request, StreamObserver<Ack> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.asRuntimeException());
    }

    @Override
    public void beat(Peer.Heartbeat request, StreamObserver<Ack> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.asRuntimeException());
    }

    @Override
    public void report(Peer.ReportChunk request, StreamObserver<Ack> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.asRuntimeException());
    }

    @Override
    public void sendMessage(PeerEndpoint request, StreamObserver<AllPeers> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.asRuntimeException());
    }
}
