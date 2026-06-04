# Build stage
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
# Warm the dependency cache (best-effort; safe to fail offline)
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src ./src
RUN ./gradlew --no-daemon installDist

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/install/nostr-authed-search-interface ./

ENV RELAY_HOST=0.0.0.0 \
    RELAY_PORT=8080 \
    VESPA_URL=http://vespa:8081

EXPOSE 8080

# More file descriptors and a server-tuned GC help with many concurrent sockets.
ENV JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=75"

ENTRYPOINT ["./bin/nostr-authed-search-interface"]
