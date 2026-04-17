#!/bin/bash

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo ""
echo "============================================"
echo "   Backend - Market Data Stream Validator"
echo "   Java 25 + Spring Boot 4.0.5"
echo "============================================"
echo ""

# Show Java version
echo "   JAVA_HOME: ${JAVA_HOME:-system default}"
echo "   Java version:"
java -version
echo ""

echo "============================================"
echo "   Starting Spring Boot on port 8082..."
echo "============================================"
echo ""

# Run with Maven wrapper
./mvnw -DskipTests spring-boot:run

echo ""
echo "============================================"
echo "   Backend has stopped. Check errors above."
echo "============================================"
