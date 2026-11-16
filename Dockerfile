# Spring Boot runtime, Java 21. The legacy CLI image is retained in Dockerfile.legacy.
FROM gradle:8.14.3-jdk21 AS build
USER gradle
WORKDIR /home/gradle/project
COPY --chown=gradle:gradle . .
RUN gradle --no-daemon --max-workers=1 --console=plain clean test bootJar
# Fail at image build time if Gradle accidentally packages the legacy CLI entrypoint.
# jar is available in the JDK build stage; only the verified Spring archive is copied below.
RUN jar xf build/libs/matchline-0.1.0-SNAPSHOT.jar META-INF/MANIFEST.MF \
    && tr -d '\r' < META-INF/MANIFEST.MF \
       | grep -Fxq 'Start-Class: dev.matchline.spring.MatchlineSpringApplication'

FROM eclipse-temurin:21-jre-jammy
WORKDIR /opt/matchline
COPY --from=build --chown=10001:10001 /home/gradle/project/build/libs/matchline-0.1.0-SNAPSHOT.jar /opt/matchline/app.jar
USER 10001:10001
# PORT is the source of truth for both Docker and Render; Render may override 8080.
ENV PORT=8080 \
    MATCHLINE_HTTP_HOST=0.0.0.0 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -Dfile.encoding=UTF-8"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/matchline/app.jar"]
