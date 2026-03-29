#!/bin/bash

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo ""
echo "============================================"
echo "   Frontend - Market Data Stream Validator"
echo "   React 18 + Vite"
echo "============================================"
echo ""

echo "   Starting Vite dev server on port 5174..."
echo ""

npm run dev

echo ""
echo "============================================"
echo "   Frontend has stopped. Check errors above."
echo "============================================"
