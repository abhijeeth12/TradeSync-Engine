# ── Build stage ────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /build
COPY pom.xml .
# Download dependencies in a separate layer for cache efficiency
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn clean package -DskipTests -q

# ── Runtime stage ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create non-root user
RUN addgroup -S tradesync && adduser -S tradesync -G tradesync
RUN mkdir -p /app/logs && chown tradesync:tradesync /app/logs

COPY --from=builder /build/target/tradesync-engine-1.0.0.jar app.jar

# Support DB URL/credentials via environment variables
COPY <<'ENTRYPOINT' /app/entrypoint.sh
#!/bin/sh
set -e
# Override application.properties values with env vars if set
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx1g}"
exec java $JAVA_OPTS \
  -Ddb.url="${DB_URL:-jdbc:postgresql://localhost:5432/tradesync}" \
  -Ddb.username="${DB_USER:-tradesync}" \
  -Ddb.password="${DB_PASS:-tradesync_secret}" \
  -jar /app/app.jar "$@"
ENTRYPOINT

RUN chmod +x /app/entrypoint.sh

USER tradesync
EXPOSE 7070

ENTRYPOINT ["/app/entrypoint.sh"]
