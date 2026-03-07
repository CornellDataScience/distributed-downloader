package cds.distdownloader.peer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PeerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PeerApplication.class, args);
    }
}