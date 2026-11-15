# Verified official Gradle wrapper — first-run download

This ZIP includes a correct Gradle wrapper properties file and launchers for **Gradle 8.14.3**, but not the binary `gradle-wrapper.jar`. Our artifact-generating environment cannot retrieve external binaries. It would be misleading to include an invented JAR and call it the official wrapper.

On **first run** of `./gradlew` (or `gradlew.bat`, through `bootstrap-wrapper.ps1`), the launcher:

1. Downloads the *official* wrapper JAR from `https://services.gradle.org/distributions/gradle-8.14.3-wrapper.jar`, falling back to the exact `v8.14.3` Gradle source repository binary.
2. Verifies SHA-256: `7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172`.
3. Moves it to `gradle/wrapper/gradle-wrapper.jar` **only after verification**.
4. Runs `org.gradle.wrapper.GradleWrapperMain` from that official JAR.

The Gradle distribution download is checked against the fixed SHA-256 in `gradle-wrapper.properties`:

`bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531`

These checksums come from the [official Gradle checksums reference](https://gradle.org/release-checksums/).

**Important:** Run `./gradlew --version` before `git add -A` on your first commit so that the *genuine* wrapper JAR is committed. A clone after that contains all four standard wrapper assets: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle/wrapper/gradle-wrapper.properties`. You can then regenerate ordinary standard launchers with `./gradlew wrapper --gradle-version 8.14.3` once the official JAR has been installed, if desired. Future commits should preserve these assets unless an explicit Gradle upgrade is scheduled.

If the first download fails, check DNS, proxy, TLS, firewall and whether `curl`/`sha256sum` are installed in WSL. Do **not** bypass checksum verification or use an unverified JAR.
