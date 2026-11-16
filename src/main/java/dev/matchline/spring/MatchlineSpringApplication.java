package dev.matchline.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * New application boundary. Package-limited component scanning keeps the existing
 * framework-independent matching, sequencing, and journal classes untouched.
 *
 * Increment 01 deliberately exposes only health/observability. Do not change the
 * deployed legacy HTTP entrypoint until full API parity has been verified.
 */
@SpringBootApplication
public class MatchlineSpringApplication {
    public static void main(String[] args) {
        SpringApplication.run(MatchlineSpringApplication.class, args);
    }
}
