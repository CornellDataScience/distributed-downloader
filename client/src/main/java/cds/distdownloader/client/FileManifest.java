package cds.distdownloader.client;

public record FileManifest(
        String filename,
        long filesize,
        int chunkSize,
        Integer chunkCount,
        String origin,
        String hashAlgorithm
) {
    public int resolvedChunkCount() {
        if (chunkCount != null) {
            return chunkCount;
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("Manifest chunkSize must be positive for " + filename);
        }
        if (filesize <= 0) {
            throw new IllegalArgumentException("Manifest filesize must be positive for " + filename);
        }
        return (int) ((filesize + chunkSize - 1L) / chunkSize);
    }
}
