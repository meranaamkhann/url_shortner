# syntax=docker/dockerfile:1

# ============================================================================
# Stage 1: Build — uses an official Maven+JDK image, produces the executable JAR.
# Kept separate from the runtime image so the final image doesn't carry the
# entire build toolchain (Maven, source code, build cache, ~300MB+) — smaller
# attack surface and a much smaller image to pull on every deploy/scale-out event.
# ============================================================================
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy only the POM first so Docker can cache the dependency-download layer
# independently of source code changes — this is what makes rebuilds fast
# during iterative development (only re-downloads deps when pom.xml changes).
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src src
RUN mvn clean package -DskipTests -B

# ============================================================================
# Stage 2: Runtime — minimal JRE (not JDK) image, non-root user.
# ============================================================================
FROM eclipse-temurin:21-jre-jammy

# Run as a dedicated non-root user — never run application processes as root
# inside a container; if the JVM is ever compromised, this limits blast radius.
RUN groupadd -r appuser && useradd -r -g appuser appuser

WORKDIR /app
COPY --from=build /app/target/url-shortener.jar app.jar

# Drop privileges before the entrypoint executes
RUN chown -R appuser:appuser /app
USER appuser

EXPOSE 8080

# Container-aware JVM flags: respect the cgroup memory limit set by Docker/K8s
# rather than defaulting to a fraction of the HOST's total memory (a classic
# "works locally, OOMKilled in production" bug class).
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# Used by Docker/Kubernetes liveness probing at the container level (in addition
# to the K8s-native HTTP probe defined in k8s/deployment.yaml).
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
