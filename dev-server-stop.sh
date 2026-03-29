#!/bin/bash

echo "============================================"
echo "   Stopping Market Data Stream Validator"
echo "============================================"
echo ""

# Function to kill process on port
kill_port() {
    local port=$1
    local service=$2
    
    if command -v lsof &> /dev/null; then
        local pid=$(lsof -ti ":$port" 2>/dev/null)
        if [ -n "$pid" ]; then
            echo "   Stopping $service (port $port) - PID $pid"
            kill -9 $pid 2>/dev/null
            echo "   ✓ Killed"
        else
            echo "   $service (port $port) - Not running"
        fi
    else
        echo "   ⚠ lsof not found. Unable to stop services automatically."
        echo "   Please manually stop the services."
    fi
}

echo "Stopping Frontend (port 5174)..."
kill_port 5174 "Frontend"
echo ""

echo "Stopping Backend (port 8082)..."
kill_port 8082 "Backend"
echo ""

# Clean up PID files if they exist
rm -f "$SCRIPT_DIR/backend.pid" "$SCRIPT_DIR/frontend.pid" 2>/dev/null

echo "============================================"
echo "   Done. Both servers stopped."
echo "============================================"
