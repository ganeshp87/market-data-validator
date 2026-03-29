# Multi-stage build for Market Data Stream Validator
# Produces a single JAR with embedded frontend (same pattern as API Comparator)

# ── Stage 1: Build frontend ─────────────────────────────
FROM node:22-alpine AS frontend-build
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ── Stage 2: Build backend ──────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /app/backend
COPY backend/pom.xml ./
RUN mvn dependency:go-offline -B
COPY backend/src ./src
# Embed the frontend build output as Spring Boot static resources
COPY --from=frontend-build /app/frontend/dist ./src/main/resources/static
RUN mvn package -DskipTests -B

# ── Stage 3: Runtime ────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create data directory for SQLite persistence
RUN mkdir -p /app/data

COPY --from=backend-build /app/backend/target/stream-validator-*.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
