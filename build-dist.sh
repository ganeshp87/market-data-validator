#!/bin/bash

echo "=== Building Market Data Stream Validator ==="
echo ""

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "[1/3] Building frontend..."
cd frontend
npm install
npm run build
cd ..

echo ""
echo "[2/3] Copying frontend to backend static..."
mkdir -p backend/src/main/resources/static
rm -rf backend/src/main/resources/static/*
cp -r frontend/dist/* backend/src/main/resources/static/

echo ""
echo "[3/3] Building backend JAR..."
cd backend
./mvnw clean package -DskipTests
cd ..

echo ""
echo "=== Build complete ==="
echo "JAR: backend/target/stream-validator-0.0.1-SNAPSHOT.jar"
echo "Docker: docker compose up --build"
