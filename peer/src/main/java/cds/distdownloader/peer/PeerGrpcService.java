package cds.distdownloader.peer;

import cds.distdownloader.proto.ChunkBitmap;
import cds.distdownloader.proto.ChunkRef;
import cds.distdownloader.proto.ChunkRequest;
import cds.distdownloader.proto.ChunkResponse;
import cds.distdownloader.proto.FileRequest;
import cds.distdownloader.proto.PeerGrpc;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;

@Service
public class PeerGrpcService extends PeerGrpc.PeerImplBase { //"Test.bin", 10, 1 2 4 // -> 0000010110
    //filename -> (chunkbit -> bytes)
    Map<String, Map<Integer, ByteString>> fileToChunk = new HashMap<>();

    public void seedTestFile() throws Exception {
        int chunkSize = 10;
        byte[] fileBytes = Files.readAllBytes(Path.of("Test.bin"));
        Map<Integer, ByteString> chunkMap = new HashMap<>();
        int chunkIndex = 0;
        for (int i = 0; i < fileBytes.length; i += chunkSize) {
            int end = Math.min(i + chunkSize, fileBytes.length);
            byte[] chunk = Arrays.copyOfRange(fileBytes, i, end);
            chunkMap.put(chunkIndex, ByteString.copyFrom(chunk));
            chunkIndex++;
        }
        fileToChunk.put("Test.bin", chunkMap);
    }

    @Override
    //receive FileRequest from client. send back chunkBitmap
    public void getAvailability(FileRequest request, StreamObserver<ChunkBitmap> responseObserver) {
        try {
            seedTestFile();
        }
        catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to seed test file: " + e.getMessage())
                    .asRuntimeException());
            return;
        }

        String fileName = request.getFileId();
        ChunkBitmap newBitMap;
        int chunks = 10; //ASSUME EACH FILE HAS 10 Chunks
        //PeerEndpoint newPeer = PeerEndpoint.newBuilder().setId("1").setIp("2").setPort(123).build();
        //For now assume we only have file "Test.bin", any other file request should be rejected
        if (!fileName.startsWith("Test.bin")) {
            ByteString value = ByteString.EMPTY;
            newBitMap = ChunkBitmap.newBuilder().setFileId(fileName).setNumChunks(chunks).setBitset(value).build();
        }
        else{
            Map<Integer, ByteString> chunkMap = fileToChunk.getOrDefault(fileName, Map.of());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            int currentByte = 0;
            int bitCount = 0;

            for (int i = 0; i < chunks; i++) {
                int idx = chunks - i - 1; // chunk (chunks-1) ... chunk 0
                int bit = chunkMap.containsKey(idx) ? 1 : 0;

                currentByte = (currentByte << 1) | bit;  // shift then add bit
                bitCount++;

                if (bitCount == 8) {
                    out.write(currentByte);
                    currentByte = 0;
                    bitCount = 0;
                }
            }
            if (bitCount > 0) {
                currentByte <<= (8 - bitCount); // pad remaining bits on the right
                out.write(currentByte);
            }

            ByteString value = ByteString.copyFrom(out.toByteArray());
            newBitMap = ChunkBitmap.newBuilder().setFileId(fileName).setNumChunks(0).setBitset(value).build();
        }

        responseObserver.onNext(newBitMap);
        responseObserver.onCompleted();
    }

    @Override
    public void getChunk(ChunkRequest request, StreamObserver<ChunkResponse> responseObserver) {
        ChunkRef chunk = request.getChunk();
        String file = chunk.getFileId();
        Integer index = chunk.getChunkIndex();
        Map<Integer, ByteString> chunkMap = fileToChunk.get(file);
        if (chunkMap == null) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("No chunks tracked for file_id=" + file)
                    .asRuntimeException());
            return;
        }

        ByteString chunkBytes = chunkMap.get(index);
        if (chunkBytes == null) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Chunk not found for file_id=" + file + ", chunk_index=" + index)
                    .asRuntimeException());
            return;
        }

        ChunkResponse resp = ChunkResponse.newBuilder()
                .setData(chunkBytes)
                .build();
        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }
}
