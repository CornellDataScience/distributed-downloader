package cds.distdownloader.peer;

import cds.distdownloader.proto.ChunkBitmap;
import cds.distdownloader.proto.ChunkRequest;
import cds.distdownloader.proto.ChunkResponse;
import cds.distdownloader.proto.FileRequest;
import cds.distdownloader.proto.PeerGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;

@Service
public class PeerGrpcService extends PeerGrpc.PeerImplBase {
    @Override
    public void getAvailability(FileRequest request, StreamObserver<ChunkBitmap> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.asRuntimeException());
    }

    @Override
    public void getChunk(ChunkRequest request, StreamObserver<ChunkResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.asRuntimeException());
    }
}
