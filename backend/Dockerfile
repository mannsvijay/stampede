# Backend image for Stampede (D-013). Built by docker-compose (`docker compose up --build`).
#
# Two stages: "build" compiles the jar with the full JDK and the Gradle wrapper; the final stage
# only has a JRE and the jar, so compilers, Gradle and the source code never reach the image.
#
# Supply chain: base images are pinned by digest (a tag can be moved, a digest can't), with the tag
# in a comment. Each build was at least 14 days old when pinned (D-011, D-012). To update, find
# the new digest with: docker buildx imagetools inspect eclipse-temurin:<tag>
#
# Secrets: none are copied in. The build context is backend/ (the repo-root .env is outside it) and
# .dockerignore excludes .env* anyway. The container gets its secrets as environment variables
# from docker-compose when it starts.

# eclipse-temurin:21.0.12_8-jdk-noble, built 2026-09-16
FROM eclipse-temurin@sha256:4d271cd5e0624598cf563342f47281b09cb364bc13acbbd7251f49f83470018d AS build
WORKDIR /workspace

# Gradle keeps the wrapper distribution and downloaded dependencies here. It is a BuildKit cache
# mount in the RUN steps below: it survives between builds on the same machine, but is never part
# of an image layer.
ENV GRADLE_USER_HOME=/gradle-home

# 1. The wrapper first, so this layer only changes when the Gradle version changes.
#    github.com sometimes returns 504 for the Gradle distribution (M0.1, D-012), so the download is
#    retried 3 times with backoff, as in CI. The wrapper checks the download against
#    distributionSha256Sum in gradle-wrapper.properties.
COPY gradlew ./
COPY gradle/ gradle/
RUN --mount=type=cache,target=/gradle-home \
    for attempt in 1 2 3; do \
      ./gradlew --no-daemon --version && exit 0; \
      echo "Gradle download failed (attempt $attempt of 3)"; \
      sleep $((attempt * 15)); \
    done; \
    echo "Could not download Gradle after 3 attempts"; exit 1

# 2. Build files, then source. Only bootJar runs: the tests need Docker (Testcontainers) and
#    already run in CI and locally (./gradlew test).
COPY build.gradle.kts settings.gradle.kts ./
COPY src/ src/
RUN --mount=type=cache,target=/gradle-home \
    ./gradlew --no-daemon bootJar \
 && cp build/libs/*.jar /workspace/app.jar


# eclipse-temurin:21.0.12_8-jre-noble, built 2026-09-16. Includes curl, used by the compose healthcheck.
FROM eclipse-temurin@sha256:7739f0ffce786528961eea6bf46d9610ee968ac6127c9b2e93494757bdecce9f

# Run as an unprivileged system user, not root. A fixed uid (10001) avoids clashing with users in
# the base image (ubuntu is 1000) and makes file ownership predictable.
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin stampede

WORKDIR /app
# Owned by root and read-only for the app user: the process can't modify its own code.
COPY --from=build /workspace/app.jar /app/app.jar

USER stampede
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
