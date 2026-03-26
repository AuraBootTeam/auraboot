#!/bin/bash
#
# DEPRECATED: Use the CLI instead:
#   cd plugins/cli && npx tsx src/index.ts plugin build <dir>
#   See: plugins/cli/README.md
#
# Build script for AuraBoot Plugin Package
# Creates a unified ZIP package containing config, backend JAR, and frontend bundle
#
# Usage: ./build-plugin.sh [--skip-backend] [--skip-frontend]
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
PLUGIN_SRC_DIR="$PROJECT_DIR/asset-management"
OUTPUT_DIR="$PROJECT_DIR/dist"
PLUGIN_NAME="asset-management"
ZIP_NAME="${PLUGIN_NAME}-plugin.zip"

# Plugin API Maven coordinates
PLUGIN_API_GROUP="com.auraboot"
PLUGIN_API_ARTIFACT="platform-plugin-api"
PLUGIN_API_VERSION="1.0.0-SNAPSHOT"
PLUGIN_API_PATH="$HOME/.m2/repository/${PLUGIN_API_GROUP//.//}/$PLUGIN_API_ARTIFACT/$PLUGIN_API_VERSION"

# Parse arguments
SKIP_BACKEND=false
SKIP_FRONTEND=false

while [[ $# -gt 0 ]]; do
  case $1 in
    --skip-backend)
      SKIP_BACKEND=true
      shift
      ;;
    --skip-frontend)
      SKIP_FRONTEND=true
      shift
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

echo "=============================================="
echo "  AuraBoot Plugin Build Script"
echo "  Plugin: $PLUGIN_NAME"
echo "=============================================="
echo ""

# Run plugin validation before build
if [ "$SKIP_VALIDATE" != "true" ]; then
  echo "[0/6] Running plugin validation..."
  if [ -f "$SCRIPT_DIR/validate-plugin.sh" ]; then
    if "$SCRIPT_DIR/validate-plugin.sh" "$PLUGIN_SRC_DIR"; then
      echo "       ✓ Validation passed"
    else
      echo ""
      echo "ERROR: Validation failed. Fix errors before building."
      echo "       Use SKIP_VALIDATE=true to bypass (not recommended)"
      exit 1
    fi
  else
    echo "       ⚠ validate-plugin.sh not found, skipping validation"
  fi
  echo ""
fi

# Clean previous build
echo "[1/6] Cleaning previous build..."
rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR/package"

# Copy plugin.json
echo "[2/6] Copying plugin.json..."
if [ ! -f "$PLUGIN_SRC_DIR/plugin.json" ]; then
  echo "ERROR: plugin.json not found at $PLUGIN_SRC_DIR/plugin.json"
  exit 1
fi
cp "$PLUGIN_SRC_DIR/plugin.json" "$OUTPUT_DIR/package/"
echo "       ✓ plugin.json copied"

# Copy config files
echo "[3/6] Copying config files..."
if [ -d "$PLUGIN_SRC_DIR/config" ]; then
  cp -r "$PLUGIN_SRC_DIR/config" "$OUTPUT_DIR/package/"
  CONFIG_COUNT=$(find "$OUTPUT_DIR/package/config" -name "*.json" | wc -l | tr -d ' ')
  echo "       ✓ $CONFIG_COUNT config files copied"
else
  echo "       ⚠ No config directory found, skipping"
fi

# Build backend JAR
echo "[4/6] Building backend JAR..."
BACKEND_BUILT=false

if [ "$SKIP_BACKEND" = true ]; then
  echo "       ⚠ Skipped (--skip-backend)"
elif [ ! -d "$PLUGIN_SRC_DIR/backend" ]; then
  echo "       ⚠ No backend directory found, skipping"
else
  # Check if Plugin API is available in Maven Local
  if [ ! -d "$PLUGIN_API_PATH" ]; then
    echo "       ⚠ Plugin API not found in Maven Local."
    echo "         Publishing Plugin API to Maven Local..."
    PLATFORM_DIR="$PROJECT_DIR/../../platform"
    if [ -d "$PLATFORM_DIR" ]; then
      (cd "$PLATFORM_DIR" && ./gradlew :platform-plugin-api:publishToMavenLocal --quiet 2>/dev/null) && {
        echo "       ✓ Plugin API published to Maven Local"
      } || {
        echo "       ⚠ Failed to publish Plugin API. Run manually:"
        echo "         cd platform && ./gradlew :platform-plugin-api:publishToMavenLocal"
        echo "         Skipping backend build..."
      }
    else
      echo "         Platform directory not found at $PLATFORM_DIR"
      echo "         Skipping backend build..."
    fi
  fi

  # Try to build if Plugin API is now available
  if [ -d "$PLUGIN_API_PATH" ]; then
    cd "$PLUGIN_SRC_DIR/backend"

    if [ -f "gradlew" ]; then
      chmod +x gradlew
      if ./gradlew clean build -x test --quiet 2>/dev/null; then
        BACKEND_BUILT=true
      else
        echo "       ⚠ Gradle build failed, skipping backend"
      fi
    elif [ -f "build.gradle" ]; then
      if gradle clean build -x test --quiet 2>/dev/null; then
        BACKEND_BUILT=true
      else
        echo "       ⚠ Gradle build failed, skipping backend"
      fi
    else
      echo "       ⚠ No build tool found, skipping backend"
    fi

    cd "$PROJECT_DIR"

    if [ "$BACKEND_BUILT" = true ]; then
      mkdir -p "$OUTPUT_DIR/package/backend"
      if [ -f "$PLUGIN_SRC_DIR/backend/build/libs/asset-plugin-1.0.0.jar" ]; then
        cp "$PLUGIN_SRC_DIR/backend/build/libs/asset-plugin-1.0.0.jar" "$OUTPUT_DIR/package/backend/"
        echo "       ✓ Backend JAR created"
      else
        # Copy any JAR found
        find "$PLUGIN_SRC_DIR/backend/build/libs" -name "*.jar" -exec cp {} "$OUTPUT_DIR/package/backend/" \; 2>/dev/null || {
          echo "       ⚠ No JAR file found"
        }
      fi
    fi
  fi
fi

# Build frontend
echo "[5/6] Building frontend..."
if [ "$SKIP_FRONTEND" = true ]; then
  echo "       ⚠ Skipped (--skip-frontend)"
elif [ -d "$PLUGIN_SRC_DIR/frontend" ]; then
  cd "$PLUGIN_SRC_DIR/frontend"

  if [ -f "package.json" ]; then
    # Install dependencies if node_modules doesn't exist
    if [ ! -d "node_modules" ]; then
      echo "       Installing npm dependencies..."
      npm install --silent 2>/dev/null || {
        echo "       ⚠ npm install failed, creating placeholder"
        mkdir -p dist
        echo "// Placeholder remoteEntry.js" > dist/remoteEntry.js
      }
    fi

    # Build
    npm run build --silent 2>/dev/null || {
      echo "       ⚠ npm build failed, creating placeholder"
      mkdir -p dist
      echo "// Placeholder remoteEntry.js - requires npm build" > dist/remoteEntry.js
    }
  else
    echo "       ⚠ No package.json found, creating placeholder"
    mkdir -p dist
    echo "// Placeholder remoteEntry.js" > dist/remoteEntry.js
  fi

  cd "$PROJECT_DIR"
  mkdir -p "$OUTPUT_DIR/package/frontend"

  if [ -d "$PLUGIN_SRC_DIR/frontend/dist" ]; then
    cp -r "$PLUGIN_SRC_DIR/frontend/dist/"* "$OUTPUT_DIR/package/frontend/"
    echo "       ✓ Frontend bundle created"
  fi
else
  echo "       ⚠ No frontend directory found, skipping"
fi

# Create ZIP package
echo "[6/6] Creating ZIP package..."
cd "$OUTPUT_DIR/package"
zip -r "../$ZIP_NAME" . -x "*.DS_Store" > /dev/null
cd "$PROJECT_DIR"

# Calculate sizes
ZIP_SIZE=$(ls -lh "$OUTPUT_DIR/$ZIP_NAME" | awk '{print $5}')

echo ""
echo "=============================================="
echo "  Build Complete!"
echo "=============================================="
echo ""
echo "  Output: $OUTPUT_DIR/$ZIP_NAME"
echo "  Size:   $ZIP_SIZE"
echo ""
echo "  Package contents:"
# List files only (macOS compatible, using zipinfo -1)
zipinfo -1 "$OUTPUT_DIR/$ZIP_NAME" 2>/dev/null || unzip -Z1 "$OUTPUT_DIR/$ZIP_NAME"
echo ""
echo "  To install:"
echo "  curl -X POST -F \"file=@$OUTPUT_DIR/$ZIP_NAME\" \\"
echo "       http://localhost:6443/api/plugins/packages/install"
echo ""
