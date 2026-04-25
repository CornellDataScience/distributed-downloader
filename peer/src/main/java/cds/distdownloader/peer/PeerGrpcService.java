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
public class PeerGrpcService extends PeerGrpc.PeerImplBase {
    private static final int DEMO_FILE_CHUNK_SIZE = 1024 * 1024;
    private static final List<String> DEMO_FILE_NAMES = List.of(
            "Test.bin",
            "Test1mb.bin",
            "Test100mb.bin"
    );

    private String id = "-1";
    // filename -> (chunkbit -> bytes)
    private final Map<String, Map<Integer, ByteString>> fileToChunk = new HashMap<>();
    private final Map<String, Integer> fileToChunkCount = new HashMap<>();
    private final Random random = new Random();
    private final TrackerGrpc.TrackerBlockingStub trackerStub;

    @Value("${peer.port:6001}")
    private int port = 6001;


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

    public synchronized void seedDemoFiles() throws Exception {
        if (fileToChunk.keySet().containsAll(DEMO_FILE_NAMES)) {
            return;
        }

        for (String fileName : DEMO_FILE_NAMES) {
            seedDemoFile(fileName);
        }
    }

    private void seedDemoFile(String fileName) throws Exception {
        if (fileToChunk.containsKey(fileName)) {
            return;
        }

        byte[] fileBytes = Files.readAllBytes(resolveDemoFilePath(fileName));
        List<byte[]> allChunks = new ArrayList<>();
        for (int i = 0; i < fileBytes.length; i += DEMO_FILE_CHUNK_SIZE) {
            int end = Math.min(i + DEMO_FILE_CHUNK_SIZE, fileBytes.length);
            allChunks.add(Arrays.copyOfRange(fileBytes, i, end));
        }

        if (allChunks.isEmpty()) {
            throw new IllegalStateException("Demo file " + fileName + " is empty.");
        }

        Map<Integer, ByteString> chunkMap = new HashMap<>();
        int seededChunkCount = random.nextInt(allChunks.size()) + 1;
        List<Integer> chunkIndices = new ArrayList<>();
        for (int i = 0; i < allChunks.size(); i++) {
            chunkIndices.add(i);
        }
        Collections.shuffle(chunkIndices, random);
        for (int i = 0; i < seededChunkCount; i++) {
            int chunkIndex = chunkIndices.get(i);
            chunkMap.put(chunkIndex, ByteString.copyFrom(allChunks.get(chunkIndex)));
        }

        fileToChunk.put(fileName, chunkMap);
        fileToChunkCount.put(fileName, allChunks.size());
        System.out.println("Seeded " + seededChunkCount + "/" + allChunks.size()
                + " chunks for " + fileName);
    }

    private Path resolveDemoFilePath(String fileName) {
        for (Path candidate : List.of(Path.of(fileName), Path.of("peer", fileName))) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        throw new IllegalStateException("Could not find " + fileName + " in current directory or peer/");
    }

    @Override
    // receive FileRequest from client. send back chunkBitmap
    public void getAvailability(FileRequest request, StreamObserver<ChunkBitmap> responseObserver) {
        try {
            seedDemoFiles();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to seed demo files: " + e.getMessage())
                    .asRuntimeException());
            return;
        }

        String fileName = request.getFileId();
        Integer chunks = fileToChunkCount.get(fileName);

        if (chunks == null) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("No chunks tracked for file_id=" + fileName)
                    .asRuntimeException());
            return;
        }

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

        ChunkBitmap newBitMap = ChunkBitmap.newBuilder()
                .setFileId(fileName)
                .setNumChunks(chunks)
                .setBitset(ByteString.copyFrom(out.toByteArray()))
                .build();

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
        System.out.println("Sending");
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
            seedDemoFiles();

            PeerEndpoint peerEndpoint = PeerEndpoint.newBuilder()
                    .setId(id)
                    .setIp("127.0.0.1")
                    .setPort(port)
                    .build();

            HeartbeatRequest heartbeatRequest = HeartbeatRequest.newBuilder()
                    .setEndpoint(peerEndpoint)
                    .addAllFileIds(fileToChunk.keySet())
                    .build();

            HeartbeatResponse response = trackerStub.handleHeartbeatRequest(heartbeatRequest);
            if (id.equals("-1")) {
                id = response.getPeerId();
                System.out.println(response.getPeerId());
            }
            System.out.println("Heartbeat sent. Ack = " + response.getAck().getOk() + "; ID: " + id);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
