package cds.distdownloader.client;

public class ClientService {
    private final String trackerHost;
    private final int trackerPort;
    private final String manifestPath;

    public ClientService(String trackerHost, int trackerPort, String manifestPath) {
        this.trackerHost = trackerHost;
        this.trackerPort = trackerPort;
        this.manifestPath = manifestPath;
    }

    public void start() {
        System.out.println("trackerHost=" + trackerHost + ", trackerPort=" + trackerPort);
        System.out.println("manifestPath=" + manifestPath);
        System.out.println("TODO: implement stuff to do the download pipeline.");
    }
}
