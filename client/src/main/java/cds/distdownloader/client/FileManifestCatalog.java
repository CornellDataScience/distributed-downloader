package cds.distdownloader.client;

import java.util.List;

public record FileManifestCatalog(List<FileManifest> files) {
    public FileManifest defaultFile() {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Manifest catalog must contain at least one file.");
        }
        return files.getFirst();
    }

    public FileManifest findByFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return defaultFile();
        }

        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Manifest catalog must contain at least one file.");
        }

        return files.stream()
                .filter(file -> filename.equals(file.filename()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No manifest entry found for file " + filename));
    }
}
