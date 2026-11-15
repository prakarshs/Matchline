# Commit 01 — Manual verification handoff

## 1. Extract to the WSL filesystem

Open **Ubuntu in Windows Terminal**. Replace your Windows username only if Downloads lives in a nonstandard place.

```bash
mkdir -p ~/Work/MATCHLINE
# Simplest: in Windows Explorer, copy the downloaded ZIP into \wsl$\Ubuntu\home\<your-linux-user>\Work\
# Then from Ubuntu:
unzip -oq ~/Work/MATCHLINE_Commit01_Starter.zip -d ~/Work/MATCHLINE
cd ~/Work/MATCHLINE
ls -la
```

If the ZIP instead lives in Windows Downloads, locate the actual Windows user directory:

```bash
ls /mnt/c/Users
# Then substitute your real path in this command:
unzip -oq '/mnt/c/Users/YOUR_WINDOWS_USER/Downloads/MATCHLINE_Commit01_Starter.zip' -d ~/Work/MATCHLINE
```

Do **not** create a nested `MATCHLINE/MATCHLINE` directory. The ZIP is intentionally root-flat. Use a fresh empty directory for Commit 01.

## 2. Linux JDK 21 and first Gradle wrapper

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk unzip curl ca-certificates coreutils
java -version
readlink -f "$(command -v java)"
cd ~/Work/MATCHLINE
chmod +x gradlew
./gradlew --version
sha256sum gradle/wrapper/gradle-wrapper.jar
```

The JAR checksum **must** equal:

`7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172`

`./gradlew --version` downloads and verifies the genuine JAR (once), then downloads/checks Gradle 8.14.3 (once). If you already have a WSL Linux JDK 21, omit the apt install step.

## 3. Test, run, and debug

```bash
./gradlew clean test --console=plain
./gradlew run --args='--self-check' --console=plain
./gradlew run --args='--version' --console=plain
./gradlew installDist --console=plain
./build/install/matchline/bin/matchline --self-check
```

Expected: seven JUnit tests, Gradle `BUILD SUCCESSFUL`, two-line bootstrap OK message and an installed runnable CLI script. Build reports: `build/reports/tests/test/index.html`.

For **IntelliJ IDEA Community on Windows**:

1. Open the project folder via the WSL path (for example `\wsl.localhost\Ubuntu\home\<linux-user>\Work\MATCHLINE`), not the Windows C-drive copy of the source.
2. Select **Trust Project** if asked.
3. Use **File > Project Structure > SDK > Add SDK > Download JDK** only if you are not using the installed WSL JDK; otherwise choose **Add JDK from WSL** / locate the Linux JDK via IDE WSL support. Select the **WSL Ubuntu JDK 21**, not a Windows JDK for a WSL-backed Gradle import.
4. In **Settings > Build, Execution, Deployment > Build Tools > Gradle**, use the Gradle wrapper distribution and JDK 21 in WSL. Exact labels vary by IntelliJ version.
5. Wait for the Gradle import. In the Project pane, right-click `src/test/java/dev/matchline/bootstrap/MatchlineApplicationTest.java` > **Run**.
6. Add a breakpoint on `int javaFeature = Runtime.version().feature();`, then right-click the test `selfCheckReportsOnlyBootstrapCapabilities` > **Debug**. The IDE should pause in `MatchlineApplication.run`.

If IntelliJ cannot use WSL JDK/Gradle directly, open the project **from within WSL** using the supported JetBrains WSL integration for your installed IDE. As a temporary fallback, run all Gradle tasks from Ubuntu in the WSL terminal and debug the test using the IDE's standard remote/WSL support; do not move the authoritative repository to `/mnt/c`.

## 4. Optional Docker Desktop smoke

Enable Docker Desktop > Settings > Resources > WSL Integration for Ubuntu, then:

```bash
docker compose --profile smoke run --build --rm matchline-smoke
```

Expected: bootstrap OK output, process exit code 0. This is *not* an HTTP service.

## 5. Initialize and push Git — manually

Make sure `./gradlew --version` has completed and the **real** `gradle-wrapper.jar` exists **before** `git add`.

```bash
cd ~/Work/MATCHLINE
git init -b main
git add -A
git status --short
git diff --cached --check
git commit -m "build: bootstrap MATCHLINE Java 21 project"
```

**Copyable commit subject:** `build: bootstrap MATCHLINE Java 21 project`

If you already have an empty repository initialized, skip `git init`. To push, create an **empty GitHub repository without auto-generated README/license/gitignore** in your own account. Then substitute its actual URL:

```bash
git remote add origin git@github.com:YOUR_GITHUB_USERNAME/matchline.git
git push -u origin main
```

For HTTPS auth, use the HTTPS remote URL instead. Do not paste a fake GitHub remote without substituting the username and verifying your permissions.

## 6. What to send back (STOP gate)

- `java -version` and `./gradlew --version` (Gradle version lines suffice).
- Output of `./gradlew clean test --console=plain` and the CLI self-check.
- IntelliJ debugging confirmation or a screenshot/error details.
- Optional Docker output.
- Whether you committed and pushed, including the **actual** hash only after Git reports it.

Do not proceed to Commit 02 until test/debug pass or we have repaired Commit 01.
