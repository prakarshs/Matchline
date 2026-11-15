# ADR 0002 — JVM/IDE and correctness-evidence foundation

**Status:** Accepted for Commit 01.

## Decision

- Keep source files on WSL2's Linux filesystem and develop with IntelliJ's WSL JDK 21 integration.
- Pin Gradle 8.14.3 and JUnit BOM 5.12.2. Use `--release 21` to prevent accidental use of newer Java APIs.
- Use JUnit 5 and AssertJ for fast deterministic unit tests. Model/property testing and JMH come later when behavior and performance can actually be measured.
- The initial CLI supports only `--help`, `--version`, `--self-check`; error exit codes are observable and testable.
- The Docker image runs a non-root, one-shot bootstrap self-check without opening a port or claiming an API.
- Source builds must not require a running database, broker or Docker daemon.

## Gradle wrapper packaging exception

The artifact authoring sandbox cannot fetch an official binary. The ZIP therefore carries a checksum-verifying wrapper bootstrap that downloads the **genuine** Gradle JAR once. The first manually verified commit must include the resulting binary. Never substitute an unverified surrogate binary.

## Verification boundary

The author can compile/smoke-test the standalone Java source in its sandbox; the user must run the Gradle/JUnit/IntelliJ/Docker checks and report results before advancing.
