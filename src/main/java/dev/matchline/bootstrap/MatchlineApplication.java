package dev.matchline.bootstrap;

import java.io.PrintStream;
import java.util.Objects;

/**
 * Dependency-free CLI bootstrap and self-check. The matching core is currently
 * available via Java API, not CLI/HTTP; exit status 0 is not exchange readiness.
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
                out.println("Usage: matchline [--help | --version | --self-check]");
                out.println("Matching and optional file journaling: Java API only. No CLI order entry or HTTP service.");
                yield 0;
            }
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
}
