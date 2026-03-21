package cds.distdownloader.peer;

import cds.distdownloader.proto.*;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
public class PeerGrpcService extends PeerGrpc.PeerImplBase { // "Test.bin", 10, 1 2 4 // -> 0000010110
    private static final String TEST_FILE_NAME = "Test.bin";
    private static final Path[] TEST_FILE_PATH_CANDIDATES = {
            Path.of(TEST_FILE_NAME),
            Path.of("peer", TEST_FILE_NAME)
    };
    private static final int TEST_FILE_CHUNK_SIZE = 1024 * 1024;
    private static final int TEST_FILE_CHUNK_COUNT = 10;

    // filename -> (chunkbit -> bytes)
    Map<String, Map<Integer, ByteString>> fileToChunk = new HashMap<>();
    private final Random random = new Random();
    private final TrackerGrpc.TrackerBlockingStub trackerStub;

    /**
     * Creates peer that connects to tracker at IP address
     * `trackerAddress`:`trackerPort`. Default address is localhost:50051.
     */
    public PeerGrpcService(@Value("${tracker.address:localhost}") String trackerAddress,
            @Value("${tracker.port:50051}") int trackerPort) {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(trackerAddress, trackerPort)
                .usePlaintext()
                .build();
        this.trackerStub = TrackerGrpc.newBlockingStub(channel);
    }

    public void seedTestFile() throws Exception {
        if (fileToChunk.containsKey(TEST_FILE_NAME)) {
            return;
        }

        byte[] fileBytes = Files.readAllBytes(resolveTestFilePath());
        List<byte[]> allChunks = new ArrayList<>();
        for (int i = 0; i < fileBytes.length; i += TEST_FILE_CHUNK_SIZE) {
            int end = Math.min(i + TEST_FILE_CHUNK_SIZE, fileBytes.length);
            allChunks.add(Arrays.copyOfRange(fileBytes, i, end));
        }

        if (allChunks.size() != TEST_FILE_CHUNK_COUNT) {
            throw new IllegalStateException("Expected " + TEST_FILE_CHUNK_COUNT + " chunks for "
                    + TEST_FILE_NAME + " but found " + allChunks.size());
        }

        Map<Integer, ByteString> chunkMap = new HashMap<>();
        int seededChunkCount = allChunks.size() > 1
                ? random.nextInt(allChunks.size() - 1) + 1
                : 1;
        List<Integer> chunkIndices = new ArrayList<>();
        for (int i = 0; i < allChunks.size(); i++) {
            chunkIndices.add(i);
        }
        Collections.shuffle(chunkIndices, random);

        for (int i = 0; i < seededChunkCount; i++) {
            int chunkIndex = chunkIndices.get(i);
            chunkMap.put(chunkIndex, ByteString.copyFrom(allChunks.get(chunkIndex)));
        }
        fileToChunk.put(TEST_FILE_NAME, chunkMap);
    }

    private Path resolveTestFilePath() {
        for (Path candidate : TEST_FILE_PATH_CANDIDATES) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        throw new IllegalStateException("Could not find " + TEST_FILE_NAME
                + " in " + Arrays.toString(TEST_FILE_PATH_CANDIDATES));
    }

    @Override
    // receive FileRequest from client. send back chunkBitmap
    public void getAvailability(FileRequest request, StreamObserver<ChunkBitmap> responseObserver) {
        try {
            seedTestFile();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to seed test file: " + e.getMessage())
                    .asRuntimeException());
            return;
        }

        String fileName = request.getFileId();
        ChunkBitmap newBitMap;
        int chunks = TEST_FILE_CHUNK_COUNT;

        // For now assume we only have file "Test.bin", any other file request should be
        // rejected
        if (!fileName.equals(TEST_FILE_NAME)) {
            ByteString value = ByteString.EMPTY;
            newBitMap = ChunkBitmap.newBuilder()
                    .setFileId(fileName)
                    .setNumChunks(chunks)
                    .setBitset(value)
                    .build();
        } else {
            Map<Integer, ByteString> chunkMap = fileToChunk.getOrDefault(fileName, Map.of());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            int currentByte = 0;
            int bitCount = 0;

            for (int i = 0; i < chunks; i++) {
                int idx = chunks - i - 1; // chunk (chunks-1) ... chunk 0
                int bit = chunkMap.containsKey(idx) ? 1 : 0;

                currentByte = (currentByte << 1) | bit;
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
            newBitMap = ChunkBitmap.newBuilder()
                    .setFileId(fileName)
                    .setNumChunks(chunks)
                    .setBitset(value)
                    .build();
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

    /*
     * Sends heartbeat every 5 seconds to tracker, so that tracker can keep track of
     * which peers are alive and which are not.
     * If no heartbeat is received from a peer for 10 seconds, tracker will consider
     * that to be a death.
     */
    @Scheduled(fixedRate = 5000)
    public void sendHeartbeat() {
        try {
            PeerEndpoint peerEndpoint = PeerEndpoint.newBuilder()
                    .setId("-1")
                    .setIp("127.0.0.1")
                    .setPort(6001)
                    .build();

            HeartbeatRequest heartbeatRequest = HeartbeatRequest.newBuilder()
                    .setEndpoint(peerEndpoint)
                    .addAllFileIds(fileToChunk.keySet())
                    .build();

            HeartbeatResponse response = trackerStub.handleHeartbeatRequest(heartbeatRequest);
            System.out.println("Heartbeat sent. Ack = " + response.getAck().getOk());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
