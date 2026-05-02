@Component
public class NodeCommandRunner implements CommandLineRunner {

    private final PeerGrpcService peerService;
    private final ClientService clientService;

    public NodeCommandRunner(PeerGrpcService peerService, ClientService clientService) {
        this.peerService = peerService;
        this.clientService = clientService;
    }

    @Override
    public void run(String... args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        System.out.println("Commands:");
        System.out.println("  upload <path>");
        System.out.println("  download <filename>");
        System.out.println("  exit");

        while (true) {
            System.out.print("> ");
            String line = scanner.nextLine().trim();

            if (line.equals("exit")) {
                break;
            }

            if (line.startsWith("upload ")) {
                String path = line.substring("upload ".length()).trim();
                //use peer service to share file
                continue;
            }

            if (line.startsWith("download ")) {
                String filename = line.substring("download ".length()).trim();
                //use client service to download file
                continue;
            }

            System.out.println("Unknown command.");
        }
    }
}