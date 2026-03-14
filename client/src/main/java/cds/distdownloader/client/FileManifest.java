package cds.distdownloader.client;

public record FileManifest(
        String filename,
        long filesize,
        int chunkSize,
        int chunkCount,
        String origin,
        String hashAlgorithm
) {
}
