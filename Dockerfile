# Commit 01: one-shot bootstrap check, not an HTTP server.
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /src
# Wrapper bootstrap requires curl or wget; declare the dependency explicitly.
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates coreutils \
    && rm -rf /var/lib/apt/lists/*
COPY . .
RUN chmod +x ./gradlew && ./gradlew --no-daemon --console=plain installDist

FROM eclipse-temurin:21-jre-jammy
WORKDIR /opt/matchline
COPY --from=builder --chown=10001:10001 /src/build/install/matchline/ ./
USER 10001:10001
ENTRYPOINT ["/opt/matchline/bin/matchline"]
EXPOSE 8080
CMD ["--self-check"]
