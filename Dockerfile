# Java 21, reproducible Gradle distribution build; requires outbound Gradle/Maven during build.
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates coreutils \
    && rm -rf /var/lib/apt/lists/*
COPY . .
RUN chmod +x ./gradlew && ./gradlew --no-daemon --max-workers=1 --console=plain clean test installDist

FROM eclipse-temurin:21-jre-jammy
WORKDIR /opt/matchline
COPY --from=build --chown=10001:10001 /src/build/install/matchline/ ./
USER 10001:10001
ENV MATCHLINE_HTTP_HOST=0.0.0.0 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -Dfile.encoding=UTF-8"
EXPOSE 8080
ENTRYPOINT ["/opt/matchline/bin/matchline"]
CMD ["--serve"]
