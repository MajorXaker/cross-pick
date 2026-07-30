#!/bin/bash

# Build script to compile the CrossPick plugin in a Docker container

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${PROJECT_DIR}/build"
DIST_DIR="${BUILD_DIR}/distributions"

echo "Building CrossPick plugin in Docker..."
echo "Project directory: $PROJECT_DIR"

# Build the Docker image
docker build -t crosspick-builder:latest "$PROJECT_DIR"

# Create a container to extract the build artifacts
CONTAINER_ID=$(docker create crosspick-builder:latest)
trap "docker rm $CONTAINER_ID" EXIT

# Create directories if they don't exist
mkdir -p "$DIST_DIR"

# Copy the built plugin ZIP from the container to the host
echo "Extracting build artifacts..."
docker cp "$CONTAINER_ID:/build/build/distributions/" "$BUILD_DIR/extracted_dist" 2>/dev/null || true

# Find the actual plugin zip file (name depends on version)
PLUGIN_ZIP=$(find "$BUILD_DIR" -name "crosspick-*.zip" | head -1)
if [ -z "$PLUGIN_ZIP" ]; then
    echo "✗ Error: Could not find built plugin ZIP"
    exit 1
fi

cp "$PLUGIN_ZIP" "$DIST_DIR/" || true

echo "✓ Build complete!"
echo "Plugin archive: $(ls $DIST_DIR/crosspick-*.zip | head -1)"
echo ""
echo "To install in PyCharm:"
echo "  1. Go to Preferences → Plugins"
echo "  2. Click ⚙ → Install Plugin from Disk..."
echo "  3. Select: $DIST_DIR/crosspick-1.0.0.zip"
echo "  4. Restart PyCharm"
