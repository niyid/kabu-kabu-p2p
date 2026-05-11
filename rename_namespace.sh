#!/usr/bin/env bash
# rename_namespace.sh
# Renames package com.techducat.kabukabup2p → com.techducat.kabukabu
# Also fixes signing config guard in build.gradle.kts
# Usage: bash rename_namespace.sh   (run from project root)

set -euo pipefail

OLD_PKG="com.techducat.kabukabup2p"
NEW_PKG="com.techducat.kabukabu"
OLD_PATH="com/techducat/kabukabup2p"
NEW_PATH="com/techducat/kabukabu"

echo "=== Step 1: Rename Java/Kotlin source directories ==="
for SRC_ROOT in \
    "app/src/main/java" \
    "app/src/test/java" \
    "app/src/androidTest/java"; do
    OLD_DIR="$SRC_ROOT/$OLD_PATH"
    NEW_DIR="$SRC_ROOT/$NEW_PATH"
    if [ -d "$OLD_DIR" ]; then
        mkdir -p "$(dirname "$NEW_DIR")"
        mv "$OLD_DIR" "$NEW_DIR"
        echo "  Moved $OLD_DIR → $NEW_DIR"
    fi
done

echo ""
echo "=== Step 2: Replace package/import references in all source files ==="
# Kotlin, Java, XML, Gradle, ProGuard
find . \
    -not -path "./.git/*" \
    -not -path "./app/build/*" \
    -not -path "./.gradle/*" \
    \( -name "*.kt" \
    -o -name "*.java" \
    -o -name "*.xml" \
    -o -name "*.kts" \
    -o -name "*.pro" \
    -o -name "*.txt" \) \
    -print0 | xargs -0 grep -l "$OLD_PKG" 2>/dev/null | while read -r f; do
        sed -i "s|$OLD_PKG|$NEW_PKG|g" "$f"
        echo "  Patched: $f"
    done

echo ""
echo "=== Step 3: Fix signing config (guard against empty keystore path) ==="
GRADLE="app/build.gradle.kts"
# Replace the release signing block with a null-safe version
python3 - "$GRADLE" << 'PYEOF'
import sys, re

path = sys.argv[1]
text = open(path).read()

old = '''        create("release") {
            val ksPath = getLocalProperty("RELEASE_STORE_FILE")
            if (ksPath.isNotEmpty()) {
                storeFile     = file(ksPath)
                storePassword = getLocalProperty("RELEASE_STORE_PASSWORD")
                keyAlias      = getLocalProperty("RELEASE_KEY_ALIAS")
                keyPassword   = getLocalProperty("RELEASE_KEY_PASSWORD")
            }
        }'''

new = '''        create("release") {
            val ksPath = getLocalProperty("RELEASE_STORE_FILE")
            if (ksPath.isNotEmpty() && java.io.File(ksPath).exists()) {
                storeFile     = file(ksPath)
                storePassword = getLocalProperty("RELEASE_STORE_PASSWORD")
                keyAlias      = getLocalProperty("RELEASE_KEY_ALIAS")
                keyPassword   = getLocalProperty("RELEASE_KEY_PASSWORD")
            }
            // If no keystore is configured the bundle will be unsigned.
            // Sign manually:
            //   jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256
            //             -keystore your.jks app-release.aab your_alias
        }'''

if old in text:
    text = text.replace(old, new)
    print(f"  Patched signing config in {path}")
else:
    print(f"  WARNING: signing block not found verbatim — check {path} manually")

# Also update applicationId if present
text = text.replace(
    '"com.techducat.kabukabup2p"',
    '"com.techducat.kabukabu"'
)
# Update applicationIdSuffix references (debug stays .debug, fdroid stays .fdroid — no change needed)
# Update namespace
text = text.replace(
    'namespace  = "com.techducat.kabukabup2p"',
    'namespace  = "com.techducat.kabukabu"'
)
open(path, 'w').write(text)
print("  applicationId + namespace updated")
PYEOF

echo ""
echo "=== Step 4: Update settings.gradle.kts project name ==="
if [ -f "settings.gradle.kts" ]; then
    sed -i 's/rootProject\.name = "kabu-kabu-p2p"/rootProject.name = "kabu-kabu"/' settings.gradle.kts \
        || true  # non-fatal if line not found
fi

echo ""
echo "=== Step 5: Clean stale build artefacts ==="
rm -rf app/build .gradle

echo ""
echo "=== Done ==="
echo "New package : $NEW_PKG"
echo "Old package : $OLD_PKG"
echo ""
echo "Next steps:"
echo "  1. Generate a keystore if you don't have one:"
echo "       keytool -genkey -v -keystore kabukabu-release.jks \\"
echo "               -alias kabukabu -keyalg RSA -keysize 4096 \\"
echo "               -validity 10000"
echo ""
echo "  2. Add to local.properties (never commit this file):"
echo "       RELEASE_STORE_FILE=/absolute/path/to/kabukabu-release.jks"
echo "       RELEASE_STORE_PASSWORD=yourPassword"
echo "       RELEASE_KEY_ALIAS=kabukabu"
echo "       RELEASE_KEY_PASSWORD=yourPassword"
echo ""
echo "  3. Build:"
echo "       ./gradlew assemblePlaystoreRelease"
echo "       # or for the AAB:"
echo "       ./gradlew bundlePlaystoreRelease"
