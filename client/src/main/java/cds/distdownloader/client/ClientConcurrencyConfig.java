package cds.distdownloader.client;

/**
 * Tuning for parallel availability queries and chunk downloads.
 * A value of {@code 0} for a field means: use the built-in default for that limit.
 */
public record ClientConcurrencyConfig(int maxAvailabilityParallelism, int maxDownloadParallelism) {
    public static final ClientConcurrencyConfig DEFAULT = new ClientConcurrencyConfig(0, 0);

    public ClientConcurrencyConfig {
        if (maxAvailabilityParallelism < 0) {
            throw new IllegalArgumentException("maxAvailabilityParallelism must be >= 0");
        }
        if (maxDownloadParallelism < 0) {
            throw new IllegalArgumentException("maxDownloadParallelism must be >= 0");
        }
    }
}
