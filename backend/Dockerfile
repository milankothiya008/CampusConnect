# syntax=docker/dockerfile:1.7
# CampusConnect API — multi-stage build: compile with the Maven wrapper, unpack Spring Boot's layers, run as a
# non-root user on a small JRE image. Rebuilds after a code change only re-copy the "application" layer.

FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Dependencies in their own layer: rebuilt only when pom.xml changes. (No BuildKit cache mounts: Railway's builder
# rejects them unless they carry its service-specific id.)
RUN sh mvnw -B -q dependency:go-offline
COPY src src
RUN sh mvnw -B -q -o package -DskipTests \
    && java -Djarmode=tools -jar target/campusconnect-api.jar extract --layers --launcher --destination /layers

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S campus && adduser -S -G campus -h /app campus \
    && mkdir -p /app/uploads && chown campus:campus /app/uploads
WORKDIR /app
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
USER campus
# Container-aware heap; crash fast (and get restarted) instead of limping on after an OutOfMemoryError.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC" \
    SPRING_PROFILES_ACTIVE=prod \
    UPLOAD_DIR=/app/uploads
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=90s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health/liveness >/dev/null || exit 1
# Netty loads its native transport: allowed explicitly (the JDK warns now and will refuse it in a later release).
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "org.springframework.boot.loader.launch.JarLauncher"]
