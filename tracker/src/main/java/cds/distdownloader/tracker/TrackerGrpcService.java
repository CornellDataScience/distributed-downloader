package cds.distdownloader.tracker;

import cds.distdownloader.proto.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;

@Service
public class TrackerGrpcService extends TrackerGrpc.TrackerImplBase {

    @Override
    public void register(RegisterRequest request,
            StreamObserver<RegisterResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.asRuntimeException());
    }

    @Override
    public void heartbeat(HeartbeatRequest request,
            StreamObserver<HeartbeatResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.asRuntimeException());
    }

    @Override
    public void listPeers(ListPeersRequest request,
            StreamObserver<ListPeersResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.asRuntimeException());
    }
}