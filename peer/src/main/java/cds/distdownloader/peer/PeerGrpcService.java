package cds.distdownloader.peer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.google.protobuf.ByteString;

import cds.distdownloader.proto.ChunkBitmap;
import cds.distdownloader.proto.ChunkRef;
import cds.distdownloader.proto.ChunkRequest;
import cds.distdownloader.proto.ChunkResponse;
import cds.distdownloader.proto.FileManifestEntry;
import cds.distdownloader.proto.FileRequest;
import cds.distdownloader.proto.MultiChunkRequest;
import cds.distdownloader.proto.HeartbeatRequest;
import cds.distdownloader.proto.HeartbeatResponse;
import cds.distdownloader.proto.PeerEndpoint;
import cds.distdownloader.proto.PeerGrpc;
import cds.distdownloader.proto.TrackerGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PreDestroy;

@Service
public class PeerGrpcService extends PeerGrpc.PeerImplBase {

    private static final int DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024;
    private static final String HASH_ALGORITHM = "SHA-256";

    // run with --peer.share-file=/Users/you/Desktop/Test1mb.bin
    @Value("${peer.share-file:}")
    private String shareFilePath;

    // filename -> manifest metadata for files this peer serves
    private final Map<String, FileManifestEntry> localManifest = new HashMap<>();

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

    // public synchronized void seedDemoFiles() throws Exception {
    //     if (fileToChunk.keySet().containsAll(demoFileNames())) {
    //         return;
    //     }

    //     for (DemoFile demoFile : DEMO_FILES) {
    //         seedDemoFile(demoFile);
    //     }
    // }

    public synchronized void seedSharedFile() throws Exception {
        if (shareFilePath == null || shareFilePath.isBlank()) {
            return;
        }
    
        Path filePath = Path.of(shareFilePath);
    
        if (!Files.exists(filePath)) {
            throw new IllegalStateException("Shared file does not exist: " + shareFilePath);
        }
    
        String fileName = filePath.getFileName().toString();
    
        if (fileToChunk.containsKey(fileName)) {
            return;
        }
    
        long fileSize = Files.size(filePath);
    
        int chunkSize = DEFAULT_CHUNK_SIZE;
        int chunkCount = (int) ((fileSize + chunkSize - 1) / chunkSize);
    
        Map<Integer, ByteString> chunkMap = new HashMap<>();
    
        try (InputStream input = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[chunkSize];
    
            for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
                int bytesRead = readChunk(input, buffer);
    
                if (bytesRead == -1) {
                    break;
                }
    
                // Since this peer is sharing the original file, it should have all chunks.
                chunkMap.put(chunkIndex, ByteString.copyFrom(buffer, 0, bytesRead));
            }
        }
    
        fileToChunk.put(fileName, chunkMap);
        fileToChunkCount.put(fileName, chunkCount);
    
        FileManifestEntry manifestEntry = FileManifestEntry.newBuilder()
                .setFilename(fileName)
                .setFilesize(fileSize)
                .setChunkSize(chunkSize)
                .setOrigin(filePath.toString())
                .setHashAlgorithm(HASH_ALGORITHM)
                .setNumChunks(chunkCount)
                .build();
    
        localManifest.put(fileName, manifestEntry);
    
        System.out.println("Seeded " + chunkMap.size() + "/" + chunkCount);
        info("Seeded " + chunkMap.size() + "/" + chunkCount
                + " chunks for " + fileName);
    }

    // private static List<String> demoFileNames() {
    //     return DEMO_FILES.stream()
    //             .map(DemoFile::fileName)
    //             .toList();
    // }

    // private void seedDemoFile(DemoFile demoFile) throws Exception {
    //     String fileName = demoFile.fileName();
    //     if (fileToChunk.containsKey(fileName)) {
    //         return;
    //     }

    //     Path filePath = resolveDemoFilePath(fileName);
    //     long fileSize = Files.size(filePath);
    //     if (fileSize == 0) {
    //         throw new IllegalStateException("Demo file " + fileName + " is empty.");
    //     }

    //     int chunkCount = (int) ((fileSize + demoFile.chunkSize() - 1) / demoFile.chunkSize());
    //     Set<Integer> selectedChunks = selectRandomChunks(chunkCount);
    //     Map<Integer, ByteString> chunkMap = new HashMap<>();

    //     try (InputStream input = Files.newInputStream(filePath)) {
    //         byte[] buffer = new byte[demoFile.chunkSize()];
    //         for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
    //             int bytesRead = readChunk(input, buffer);
    //             if (bytesRead == -1) {
    //                 break;
    //             }
    //             if (selectedChunks.contains(chunkIndex)) {
    //                 chunkMap.put(chunkIndex, ByteString.copyFrom(buffer, 0, bytesRead));
    //             }
    //         }
    //     }

    //     fileToChunk.put(fileName, chunkMap);
    //     fileToChunkCount.put(fileName, chunkCount);
    //     System.out.println("Seeded " + chunkMap.size() + "/" + chunkCount
    //             + " chunks for " + fileName);
    // }

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

    // private Path resolveDemoFilePath(String fileName) {
    //     for (Path candidate : List.of(Path.of(fileName), Path.of("peer", fileName))) {
    //         if (Files.exists(candidate)) {
    //             return candidate;
    //         }
    //     }

    //     throw new IllegalStateException("Could not find " + fileName + " in current directory or peer/");
    // }

    @Override
    // receive FileRequest from client. send back chunkBitmap
    public void getAvailability(FileRequest request, StreamObserver<ChunkBitmap> responseObserver) {
        try {
            seedSharedFile();
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
        int index = chunk.getChunkIndex();
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

        responseObserver.onNext(ChunkResponse.newBuilder()
                .setData(chunkBytes)
                .setChunkIndex(index)
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void getChunks(MultiChunkRequest request, StreamObserver<ChunkResponse> responseObserver) {
        String file = request.getFileId();
        Map<Integer, ByteString> chunkMap = fileToChunk.get(file);
        if (chunkMap == null) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("No chunks tracked for file_id=" + file)
                    .asRuntimeException());
            return;
        }

        for (int index : request.getChunkIndicesList()) {
            ByteString chunkBytes = chunkMap.get(index);
            if (chunkBytes == null) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Chunk not found for file_id=" + file + ", chunk_index=" + index)
                        .asRuntimeException());
                return;
            }
            info("Sending chunk " + index);
            responseObserver.onNext(ChunkResponse.newBuilder()
                    .setData(chunkBytes)
                    .setChunkIndex(index)
                    .build());
        }
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
            seedSharedFile();

            PeerEndpoint peerEndpoint = PeerEndpoint.newBuilder()
                    .setId(id)
                    .setIp(advertiseAddress)
                    .setPort(port)
                    .build();

            HeartbeatRequest heartbeatRequest = HeartbeatRequest.newBuilder()
                    .setEndpoint(peerEndpoint)
                    .addAllFiles(localManifest.values())
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
