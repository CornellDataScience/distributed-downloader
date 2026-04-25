package cds.distdownloader.client;

public class Client {
    public static void main(String[] args) {
        String trackerHost = args.length > 0 ? args[0] : "127.0.0.1";
        int trackerPort = args.length > 1 ? Integer.parseInt(args[1]) : 50051;
        String manifestPath = args.length > 2 ? args[2] : "env/manifest.json";
        String requestedFilename = args.length > 3 ? args[3] : null;

        ClientService clientService = new ClientService(trackerHost, trackerPort, manifestPath, requestedFilename);
        clientService.start();
    }
}
