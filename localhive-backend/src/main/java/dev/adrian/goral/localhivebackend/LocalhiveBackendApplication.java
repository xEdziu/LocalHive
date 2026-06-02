package dev.adrian.goral.localhivebackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class LocalhiveBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocalhiveBackendApplication.class, args);
    }

}
