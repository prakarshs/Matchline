#!/bin/sh
# MATCHLINE Commit 01: download and verify the OFFICIAL wrapper JAR on first use.
# Once downloaded, this uses the real Gradle org.gradle.wrapper.GradleWrapperMain.
# This bootstrap is needed because our artifact authoring sandbox cannot fetch binaries.
set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
EXPECTED_SHA256=7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "[MATCHLINE bootstrap] Fetching official Gradle 8.14.3 wrapper JAR..." >&2
    TMP="$WRAPPER_JAR.download.$$"
    trap 'rm -f "$TMP"' EXIT HUP INT TERM
    OFFICIAL="https://services.gradle.org/distributions/gradle-8.14.3-wrapper.jar"
    FALLBACK="https://raw.githubusercontent.com/gradle/gradle/v8.14.3/gradle/wrapper/gradle-wrapper.jar"

    if command -v curl >/dev/null 2>&1; then
        if ! curl -fLsS --retry 2 --connect-timeout 15 --max-time 180 -o "$TMP" "$OFFICIAL"; then
            curl -fLsS --retry 2 --connect-timeout 15 --max-time 180 -o "$TMP" "$FALLBACK"
        fi
    elif command -v wget >/dev/null 2>&1; then
        if ! wget -q -T 180 -O "$TMP" "$OFFICIAL"; then
            wget -q -T 180 -O "$TMP" "$FALLBACK"
        fi
    else
        echo "Need curl or wget to download the official wrapper JAR." >&2
        exit 1
    fi

    ACTUAL_SHA256=$(sha256sum "$TMP" | cut -d ' ' -f 1)
    if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
        echo "Wrapper JAR checksum mismatch. Expected: $EXPECTED_SHA256; got: $ACTUAL_SHA256" >&2
        exit 1
    fi
    mv "$TMP" "$WRAPPER_JAR"
    trap - EXIT HUP INT TERM
    echo "[MATCHLINE bootstrap] Official wrapper JAR verified and saved." >&2
fi

if [ -n "${JAVA_HOME:-}" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
else
    JAVA_CMD=java
fi

if ! command -v "$JAVA_CMD" >/dev/null 2>&1; then
    echo "Java 21 JDK was not found. Check JAVA_HOME and PATH." >&2
    exit 1
fi
exec "$JAVA_CMD" -Dorg.gradle.appname=gradlew -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
