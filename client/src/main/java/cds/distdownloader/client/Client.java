package cds.distdownloader.client;

/**
 * {@code [trackerHost] [trackerPort] [manifestPath] [filename]
 *  [maxAvailabilityParallelism] [maxDownloadParallelism] [quiet] }
 * <p>
 * Optional trailing integers (or system properties
 * {@code cds.distdownloader.maxAvailabilityParallelism} /
 * {@code cds.distdownloader.maxDownloadParallelism}): use {@code 0} for
 * built-in defaults (all peers in parallel for availability; higher derived cap for downloads).
 * <p>
 * Optional 7th argument {@code true} or {@code false}: suppresses verbose console output (or use
 * system property {@code cds.distdownloader.quiet}).
 * CLI argument overrides the system property when both are set.
 */
public class Client {
    public static void main(String[] args) {
        String trackerHost = args.length > 0 ? args[0] : "127.0.0.1";
        int trackerPort = args.length > 1 ? Integer.parseInt(args[1]) : 50051;
        String manifestPath = args.length > 2 ? args[2] : "env/manifest.json";
        String requestedFilename = args.length > 3 ? args[3] : null;

        int maxAvailability = intArgOrProperty(args, 4, "cds.distdownloader.maxAvailabilityParallelism");
        int maxDownload = intArgOrProperty(args, 5, "cds.distdownloader.maxDownloadParallelism");
        boolean quiet = booleanArgOrProperty(args, 6, "cds.distdownloader.quiet");
        ClientConcurrencyConfig concurrency = new ClientConcurrencyConfig(maxAvailability, maxDownload);

        ClientService clientService = new ClientService(trackerHost, trackerPort, manifestPath, requestedFilename, concurrency, quiet);
        clientService.start();
    }

    private static int intArgOrProperty(String[] args, int index, String propertyName) {
        if (args.length > index) {
            return Integer.parseInt(args[index].trim());
        }
        String p = System.getProperty(propertyName);
        if (p == null || p.isEmpty()) {
            return 0;
        }
        return Integer.parseInt(p.trim());
    }

    private static boolean booleanArgOrProperty(String[] args, int index, String propertyName) {
        if (args.length > index) {
            String t = args[index].trim();
            if ("true".equalsIgnoreCase(t) || "false".equalsIgnoreCase(t)) {
                return Boolean.parseBoolean(t);
            }
        }
        String p = System.getProperty(propertyName);
        if (p == null || p.isEmpty()) {
            return false;
        }
        return Boolean.parseBoolean(p.trim());
    }
}
