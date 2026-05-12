# AuraBoot Backend — Multi-stage Docker build
# Stage 1: Build with Gradle + JDK 21
# Stage 2: Runtime with JRE 21

# ── Stage 1: Build ──────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /app

# Copy Gradle wrapper and config (cache dependencies layer)
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle gradle.properties ./

# Copy subproject build files for dependency resolution
COPY platform-plugin-api/build.gradle platform-plugin-api/build.gradle
COPY platform-storage-minio/build.gradle platform-storage-minio/build.gradle
COPY platform-storage-s3/build.gradle platform-storage-s3/build.gradle
COPY platform-storage-oss/build.gradle platform-storage-oss/build.gradle
COPY platform-mq-kafka/build.gradle platform-mq-kafka/build.gradle
COPY platform-mq-rabbitmq/build.gradle platform-mq-rabbitmq/build.gradle

RUN chmod +x gradlew

# Download dependencies (cached unless build files change)
RUN ./gradlew dependencies --no-daemon || true

# Copy source code
COPY src ./src
COPY platform-plugin-api/src platform-plugin-api/src

# Copy vendor dependencies if present (use glob pattern to make optional)
COPY vendor* ./vendor/

# Build the application (skip tests for faster builds)
RUN ./gradlew bootJar --no-daemon -x test

# ── Stage 2: Runtime ────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

# Install wget for health checks
RUN apk add --no-cache wget

# Create non-root user
RUN addgroup -g 1000 appuser && \
    adduser -D -u 1000 -G appuser appuser

# Create data directories
RUN mkdir -p /app/data/files /app/data/git-repos /app/data/artifacts && \
    chown -R appuser:appuser /app

# Copy built JAR from builder
COPY --from=builder --chown=appuser:appuser /app/build/libs/*.jar app.jar

USER appuser

EXPOSE 6443

ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:6443/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
