package dev.matchline.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring application boundary for the staged migration. Scanning this package
 * wires one writer, the application facade, Actuator, and the health controller;
 * framework-independent domain and journal classes remain unchanged.
 *
 * S05 packages this Spring application as the public demo Docker entrypoint.
 * The original CLI remains available through the Gradle application task and Dockerfile.legacy.
 */
@SpringBootApplication
public class MatchlineSpringApplication {
    public static void main(String[] args) {
        SpringApplication.run(MatchlineSpringApplication.class, args);
    }
}
