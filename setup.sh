#!/usr/bin/env bash
# ============================================================================
# setup.sh — First-time Jenga Mod project setup
#
# Downloads gradle-wrapper.jar from the official Fabric example mod on GitHub
# and places it at gradle/wrapper/gradle-wrapper.jar, which is required before
# any ./gradlew command will work.
# ============================================================================

set -e
DEST="gradle/wrapper/gradle-wrapper.jar"

if [ -f "$DEST" ]; then
    echo "[setup] gradle-wrapper.jar already exists. Nothing to do."
    exit 0
fi

echo "[setup] Downloading gradle-wrapper.jar from Fabric example mod…"

# Primary: download from GitHub directly
URL="https://github.com/FabricMC/fabric-example-mod/raw/1.20.1/gradle/wrapper/gradle-wrapper.jar"

if command -v curl &>/dev/null; then
    curl -fLo "$DEST" "$URL"
elif command -v wget &>/dev/null; then
    wget -q -O "$DEST" "$URL"
else
    echo "[setup] ERROR: neither curl nor wget found. Download manually from:"
    echo "  $URL"
    echo "  Place it at: $DEST"
    exit 1
fi

echo "[setup] Done! Now run:"
echo "  ./gradlew genSources   # generate decompiled Minecraft sources for IntelliSense"
echo "  ./gradlew build        # compile and package the mod jar"
