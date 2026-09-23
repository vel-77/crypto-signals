# ---- build -----------------------------------------------------------------------------------
# Dependencies resolve in their own layer so that editing source does not re-download the world.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- run -------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# curl is only here for the container healthcheck; the application itself needs nothing extra.
# Runs as a non-root user: the process wants a socket and a JVM, nothing more.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 1001 app \
 && useradd --system --uid 1001 --gid app --home /app app

COPY --from=build /build/target/crypto-signals-*.jar /app/crypto-signals.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx, so the container honours whatever limit it is given.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
  CMD ["sh", "-c", "curl -fsS http://localhost:8080/actuator/health | grep -q UP || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/crypto-signals.jar \"$@\"", "--"]
