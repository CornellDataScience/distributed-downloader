package cds.distdownloader.peer;

import cds.distdownloader.proto.*;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class PeerGrpcService extends PeerGrpc.PeerImplBase {
    private record DemoFile(String fileName, int chunkSize) {
    }

    private static final int ONE_MIB = 1024 * 1024;
    private static final List<DemoFile> DEMO_FILES = List.of(
            new DemoFile("Test.bin", ONE_MIB),
            new DemoFile("Test1mb.bin", ONE_MIB),
            new DemoFile("Test100mb.bin", ONE_MIB),
            new DemoFile("Test1gb.bin", 1_000_000)
    );

    private String id = "-1";
    // filename -> (chunkbit -> bytes)
    private final Map<String, Map<Integer, ByteString>> fileToChunk = new HashMap<>();
    private final Map<String, Integer> fileToChunkCount = new HashMap<>();
    private final Random random = new Random();
    private final ManagedChannel trackerChannel;
    private final TrackerGrpc.TrackerBlockingStub trackerStub;
    private final boolean quiet;

    @Value("${peer.port:6001}")
    private int port = 6001;

    @Value("${peer.advertise-address:127.0.0.1}")
    private String advertiseAddress = "127.0.0.1";


    /**
     * Creates peer that connects to tracker at IP address
     * `trackerAddress`:`trackerPort`. Default address is localhost:50051.
     */
    public PeerGrpcService(
            @Value("${tracker.address:localhost}") String trackerAddress,
            @Value("${tracker.port:50051}") int trackerPort,
            @Value("${cds.distdownloader.quiet:false}") boolean quiet
    ) {
        this.trackerChannel = ManagedChannelBuilder
                .forAddress(trackerAddress, trackerPort)
                .usePlaintext()
                .build();
        this.trackerStub = TrackerGrpc.newBlockingStub(trackerChannel);
        this.quiet = quiet;
    }

    private void info(String message) {
        if (!quiet) {
            System.out.println(message);
        }
    }

    @PreDestroy
    public void shutdownTrackerChannel() {
        shutdownChannelGracefully(trackerChannel);
    }

    private static void shutdownChannelGracefully(ManagedChannel channel) {
        channel.shutdown();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
                channel.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public synchronized void seedDemoFiles() throws Exception {
        if (fileToChunk.keySet().containsAll(demoFileNames())) {
            return;
        }

        for (DemoFile demoFile : DEMO_FILES) {
            seedDemoFile(demoFile);
        }
    }

    private static List<String> demoFileNames() {
        return DEMO_FILES.stream()
                .map(DemoFile::fileName)
                .toList();
    }

    private void seedDemoFile(DemoFile demoFile) throws Exception {
        String fileName = demoFile.fileName();
        if (fileToChunk.containsKey(fileName)) {
            return;
        }

        Path filePath = resolveDemoFilePath(fileName);
        long fileSize = Files.size(filePath);
        if (fileSize == 0) {
            throw new IllegalStateException("Demo file " + fileName + " is empty.");
        }

        int chunkCount = (int) ((fileSize + demoFile.chunkSize() - 1) / demoFile.chunkSize());
        Set<Integer> selectedChunks = selectRandomChunks(chunkCount);
        Map<Integer, ByteString> chunkMap = new HashMap<>();

        try (InputStream input = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[demoFile.chunkSize()];
            for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
                int bytesRead = readChunk(input, buffer);
                if (bytesRead == -1) {
                    break;
                }
                if (selectedChunks.contains(chunkIndex)) {
                    chunkMap.put(chunkIndex, ByteString.copyFrom(buffer, 0, bytesRead));
                }
            }
        }

        fileToChunk.put(fileName, chunkMap);
        fileToChunkCount.put(fileName, chunkCount);
        info("Seeded " + chunkMap.size() + "/" + chunkCount
                + " chunks for " + fileName);
    }

    private Set<Integer> selectRandomChunks(int chunkCount) {
        int seededChunkCount = random.nextInt(chunkCount) + 1;
        List<Integer> chunkIndices = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            chunkIndices.add(i);
        }
        Collections.shuffle(chunkIndices, random);

        Set<Integer> selectedChunks = new HashSet<>();
        for (int i = 0; i < seededChunkCount; i++) {
            selectedChunks.add(chunkIndices.get(i));
        }
        return selectedChunks;
    }

    private int readChunk(InputStream input, byte[] buffer) throws Exception {
        int totalRead = 0;
        while (totalRead < buffer.length) {
            int bytesRead = input.read(buffer, totalRead, buffer.length - totalRead);
            if (bytesRead == -1) {
                return totalRead == 0 ? -1 : totalRead;
            }
            totalRead += bytesRead;
        }
        return totalRead;
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
        info("Sending");
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
                    .setIp(advertiseAddress)
                    .setPort(port)
                    .build();

            HeartbeatRequest heartbeatRequest = HeartbeatRequest.newBuilder()
                    .setEndpoint(peerEndpoint)
                    .addAllFileIds(fileToChunk.keySet())
                    .build();

            HeartbeatResponse response = trackerStub.handleHeartbeatRequest(heartbeatRequest);
            if (id.equals("-1")) {
                id = response.getPeerId();
                info(response.getPeerId());
            }
            info("Heartbeat sent. Ack = " + response.getAck().getOk() + "; ID: " + id);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
