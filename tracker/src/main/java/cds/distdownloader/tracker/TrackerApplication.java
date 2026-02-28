package cds.distdownloader.tracker;

import cds.distdownloader.proto.*;
import com.google.protobuf.InvalidProtocolBufferException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TrackerApplication {
    public static void main(String[] args) {
        SpringApplication.run(TrackerApplication.class, args);
        PeerEndpoint newPeer = PeerEndpoint.newBuilder().setId("1").setIp("2").setPort(123).build();
        System.out.println(newPeer.toString());

        byte[] bytes = newPeer.toByteArray();

        PeerEndpoint u2 = null;
        try {
            u2 = PeerEndpoint.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
        System.out.println(u2.toString());

    }
}