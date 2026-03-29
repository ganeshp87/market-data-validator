#!/bin/bash

# Get script directory (handles symlinks)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR"
BACKEND="$ROOT/backend"
FRONTEND="$ROOT/frontend"

echo "============================================"
echo "   Market Data Stream Validator"
echo "   Java 25 + Spring Boot 4.0.5"
echo "============================================"
echo ""

# Function to check if port is listening
is_listening() {
    if command -v lsof &> /dev/null; then
        lsof -i ":$1" > /dev/null 2>&1
        return $?
    else
        # Fallback for systems without lsof
        netstat -tuln 2>/dev/null | grep ":$1 " > /dev/null 2>&1
        return $?
    fi
}

# Start Backend
echo "   Starting Backend on port 8082..."
if is_listening 8082; then
    echo "   ✓ Backend already running."
else
    open -a Terminal "$BACKEND/run-backend.sh" 2>/dev/null || \
    xterm -hold -e "$BACKEND/run-backend.sh" &
    sleep 2
fi

# Wait for backend to be ready
echo "   Waiting for backend..."
BWAIT=0
while [ $BWAIT -lt 30 ]; do
    if is_listening 8082; then
        echo "   ✓ Backend is ready on port 8082"
        break
    fi
    BWAIT=$((BWAIT + 1))
    sleep 1
done

if [ $BWAIT -eq 30 ]; then
    echo "   ⚠ Backend not ready after 30s. Check the Backend terminal."
fi

echo ""

# Start Frontend
echo "   Starting Frontend on port 5174..."
if is_listening 5174; then
    echo "   ✓ Frontend already running."
else
    open -a Terminal "$FRONTEND/run-frontend.sh" 2>/dev/null || \
    xterm -hold -e "$FRONTEND/run-frontend.sh" &
    sleep 2
fi

# Wait for frontend to be ready
echo "   Waiting for frontend..."
FWAIT=0
while [ $FWAIT -lt 15 ]; do
    if is_listening 5174; then
        echo "   ✓ Frontend is ready on port 5174"
        break
    fi
    FWAIT=$((FWAIT + 1))
    sleep 1
done

if [ $FWAIT -eq 15 ]; then
    echo "   ⚠ Frontend not ready after 15s. Check the Frontend terminal."
fi

echo ""
echo "============================================"
echo "   Dashboard: http://localhost:8082"
echo "============================================"
echo ""
echo "   To stop both servers, run: ./dev-server-stop.sh"
