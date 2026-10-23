# AuraBoot Backend — Multi-stage Docker build
# Stage 1: Build with Gradle + JDK 21
# Stage 2: Runtime with JRE 21
#
# Build context: REPO ROOT (not platform/).
# The runtime image bakes the OSS config plugins from the repo-root plugins/
# tree so a pure `docker pull` needs no repo checkout (see docker-compose.pull.yml).
# Because plugins/ lives at the repo root — outside platform/ — the build context
# must be the repo root. All platform/ sources are therefore COPYed with a
# `platform/` prefix; the destination paths inside the image are unchanged.
#
# Invocation:
#   docker build -f platform/Dockerfile -t auraboot-backend .
# docker-compose maps this via:
#   build:
#     context: .
#     dockerfile: platform/Dockerfile

# ── Stage 1: Build ──────────────────────────────────────────
ARG BUILD_JDK_IMAGE=eclipse-temurin:21-jdk
ARG RUNTIME_JRE_IMAGE=eclipse-temurin:21-jre-alpine
FROM ${BUILD_JDK_IMAGE} AS builder
ARG AURA_GRADLE_REPOSITORY_POLICY=project

WORKDIR /app

# GitHub-hosted builders can opt into Maven Central before regional mirrors.
# This init script exists only in the builder; default repository policy is unchanged.
ARG MAVEN_CENTRAL_FIRST=false
RUN if [ "$MAVEN_CENTRAL_FIRST" = "true" ]; then \
      mkdir -p /root/.gradle/init.d && \
      printf '%s\n' 'gradle.beforeProject { project -> project.repositories.mavenCentral() }' \
        > /root/.gradle/init.d/maven-central-first.gradle; \
    fi

# Copy Gradle wrapper and config (cache dependencies layer)
COPY platform/gradlew gradlew
COPY platform/gradle gradle
COPY platform/build.gradle platform/settings.gradle platform/gradle.properties ./
COPY platform/gradle.lockfile ./
# Lombok controls constructor binding and copied injection qualifiers, hence the HTTP contract.
COPY platform/lombok.config ./
# platform/build.gradle intentionally reads ../VERSION so the platform and repository release
# stay on one canonical version. WORKDIR is /app in this stage, therefore that file is /VERSION.
COPY VERSION /VERSION
COPY scripts/ci/gradle-repositories.init.gradle /opt/aura-ci/gradle-repositories.init.gradle

# Copy subproject build files for dependency resolution
COPY platform/platform-bom/build.gradle platform-bom/build.gradle
COPY platform/platform-app/build.gradle platform-app/build.gradle
COPY platform/platform-agent/build.gradle platform-agent/build.gradle
COPY platform/platform-plugin-api/build.gradle platform-plugin-api/build.gradle
COPY platform/platform-storage-minio/build.gradle platform-storage-minio/build.gradle
COPY platform/platform-storage-s3/build.gradle platform-storage-s3/build.gradle
COPY platform/platform-storage-oss/build.gradle platform-storage-oss/build.gradle
COPY platform/platform-storage-cos/build.gradle platform-storage-cos/build.gradle
COPY platform/platform-mq-kafka/build.gradle platform-mq-kafka/build.gradle
COPY platform/platform-mq-rabbitmq/build.gradle platform-mq-rabbitmq/build.gradle
COPY platform/platform-scheduler-xxl/build.gradle platform-scheduler-xxl/build.gradle

COPY platform/platform-plugin-api/gradle.lockfile platform-plugin-api/gradle.lockfile
COPY platform/platform-storage-minio/gradle.lockfile platform-storage-minio/gradle.lockfile
COPY platform/platform-storage-s3/gradle.lockfile platform-storage-s3/gradle.lockfile
COPY platform/platform-storage-oss/gradle.lockfile platform-storage-oss/gradle.lockfile
COPY platform/platform-storage-cos/gradle.lockfile platform-storage-cos/gradle.lockfile
COPY platform/platform-mq-kafka/gradle.lockfile platform-mq-kafka/gradle.lockfile
COPY platform/platform-mq-rabbitmq/gradle.lockfile platform-mq-rabbitmq/gradle.lockfile
COPY platform/platform-scheduler-xxl/gradle.lockfile platform-scheduler-xxl/gradle.lockfile
COPY platform/platform-app/gradle.lockfile platform-app/gradle.lockfile
COPY platform/platform-agent/gradle.lockfile platform-agent/gradle.lockfile

RUN chmod +x gradlew

# Download dependencies (cached unless build files change)
RUN --mount=type=cache,target=/root/.gradle/wrapper \
    --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.m2/repository,id=aura-m2,sharing=locked \
    AURA_GRADLE_REPOSITORY_POLICY="$AURA_GRADLE_REPOSITORY_POLICY" \
    ./gradlew --init-script /opt/aura-ci/gradle-repositories.init.gradle dependencies --no-daemon || true

# Copy source code
COPY platform/src ./src
COPY platform/platform-app/src platform-app/src
COPY platform/platform-agent/src platform-agent/src
COPY platform/platform-plugin-api/src platform-plugin-api/src
COPY platform/platform-storage-minio/src platform-storage-minio/src
COPY platform/platform-storage-s3/src platform-storage-s3/src
COPY platform/platform-storage-oss/src platform-storage-oss/src
COPY platform/platform-storage-cos/src platform-storage-cos/src
COPY platform/platform-mq-kafka/src platform-mq-kafka/src
COPY platform/platform-mq-rabbitmq/src platform-mq-rabbitmq/src
COPY platform/platform-scheduler-xxl/src platform-scheduler-xxl/src

# Copy vendor dependencies if present (use glob pattern to make optional)
COPY platform/vendor* ./vendor/

# Build the platform application only. Product PF4J JARs are supplied by the
# independently versioned application image and never compiled into Core.
RUN --mount=type=cache,target=/root/.gradle/wrapper \
    --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.m2/repository,id=aura-m2,sharing=locked \
    AURA_GRADLE_REPOSITORY_POLICY="$AURA_GRADLE_REPOSITORY_POLICY" \
    ./gradlew --init-script /opt/aura-ci/gradle-repositories.init.gradle :platform-plugin-api:publishToMavenLocal bootJar --no-daemon -x test

# ── Stage 2: Runtime ────────────────────────────────────────
FROM ${RUNTIME_JRE_IMAGE}

WORKDIR /app

# Install wget for health checks
RUN apk add --no-cache wget

# Create non-root user
RUN addgroup -g 1000 appuser && \
    adduser -D -u 1000 -G appuser appuser

# Create data directories
RUN mkdir -p /app/data/files /app/data/git-repos /app/data/artifacts /app/pf4j-plugins && \
    chown -R appuser:appuser /app

# Copy built executable boot JAR from builder (bootJar carries the 'boot' classifier;
# the plain library jar also lands in build/libs, so glob the boot jar explicitly)
COPY --from=builder --chown=appuser:appuser /app/build/libs/*-boot.jar app.jar

# Bake the public plugins into the image so a pure `docker pull` needs no repo
# checkout. quickstart.sh imports them from /app/plugins (AURA_BUILTIN_PLUGINS_DIR).
# The OSS repo contains no enterprise code (enforced by check-oss-boundary.sh), so
# this image stays free of report/BOM plugins by construction. The compose dev flow
# still bind-mounts ./plugins:/app/plugins:ro, overriding these baked copies for live
# iteration; docker-compose.pull.yml omits the mount and relies on them.
COPY --chown=appuser:appuser plugins /app/plugins

USER appuser

EXPOSE 6443

ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# Secure-by-default Spring profile. application.yml bakes in `dev,local,test` for
# local development, which enables the anonymous test-seed endpoints
# (TestSeedController: create tenant + mint JWT) and the dev actuator surface.
# Baking a safe default here ensures a bare `docker run` of this image never falls
# through to that unsafe default. Deployments override it (compose `environment:` /
# `docker run -e SPRING_PROFILES_ACTIVE=...`); local gradle bootRun is unaffected.
ENV SPRING_PROFILES_ACTIVE="community"

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:6443/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
