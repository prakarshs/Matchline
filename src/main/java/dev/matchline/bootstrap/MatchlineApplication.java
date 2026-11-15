package dev.matchline.bootstrap;

import dev.matchline.http.HttpConfig;
import dev.matchline.http.MatchlineHttpServer;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/**
 * CLI entrypoint. --serve starts the demo-only Java 21 HTTP adapter over the
 * single-writer engine. Self-check still does not imply network readiness.
 */
public final class MatchlineApplication {

    private MatchlineApplication() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /** Testable application boundary with no System.exit side effects. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");

        if (args.length > 1) {
            err.println("Expected at most one argument. Try --help.");
            return 2;
        }

        String option = args.length == 0 ? "--help" : args[0];
        BootstrapStatus status = BootstrapStatus.current();

        return switch (option) {
            case "--help" -> {
                out.println("MATCHLINE | deterministic exchange simulator | Java core");
                out.println("Usage: matchline [--help | --version | --self-check | --recovery-demo | --serve]");
                out.println("--serve starts loopback HTTP on port 8080 (override using MATCHLINE_HTTP_* env).");
                yield 0;
            }
            case "--recovery-demo" -> RecoveryDrill.run(out, err);
            case "--serve" -> serve(out, err, System.getenv());
            case "--version" -> {
                out.println(status.application() + " " + status.version());
                yield 0;
            }
            case "--self-check" -> {
                int javaFeature = Runtime.version().feature();
                if (!BootstrapStatus.supportedJavaFeature(javaFeature)) {
                    err.println("MATCHLINE requires Java 21 or newer; found " + javaFeature);
                    yield 1;
                }
                out.println("MATCHLINE BOOTSTRAP OK | Java " + javaFeature);
                out.println("matching=" + status.matchingAvailable()
                        + " http=" + status.httpAvailable()
                        + " journal=" + status.journalAvailable());
                yield 0;
            }
            default -> {
                err.println("Unknown argument: " + option + ". Try --help.");
                yield 2;
            }
        };
    }

    private static int serve(PrintStream out, PrintStream err, Map<String, String> environment) {
        try (MatchlineHttpServer http = new MatchlineHttpServer(HttpConfig.fromEnvironment(environment))) {
            http.start();
            out.println("MATCHLINE HTTP READY | http://" + http.address().getHostString()
                    + ":" + http.address().getPort() + "/api/v1/health");
            Thread shutdown = new Thread(http::close, "matchline-http-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                new CountDownLatch(1).await(); // JVM shutdown hook or thread interruption stops service.
            } finally {
                try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                catch (IllegalStateException shuttingDown) { /* already terminating */ }
            }
            return 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (IllegalArgumentException badConfig) {
            err.println("Invalid HTTP configuration: " + badConfig.getMessage());
            return 2;
        } catch (IOException startup) {
            err.println("HTTP listener or journal could not start: " + startup.getMessage());
            return 1;
        }
    }
}
