package dev.matchline.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring application boundary for the staged migration. Scanning this package
 * wires one writer, the application facade, Actuator, and the health controller;
 * framework-independent domain and journal classes remain unchanged.
 *
 * S04 exposes demo routes locally but does NOT replace the deployed CLI.
 */
@SpringBootApplication
public class MatchlineSpringApplication {
    public static void main(String[] args) {
        SpringApplication.run(MatchlineSpringApplication.class, args);
    }
}
