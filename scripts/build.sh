#!/bin/sh
# Build, lint, and run pure Java unit tests. JAVA_HOME must point to JDK 17.
# Optionally: BANXU_TOOLCHAIN=/path/to/isolated/toolchain ./scripts/build.sh
# Standard Android Studio installations also work through JAVA_HOME/ANDROID_HOME.
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TOOLCHAIN_DIR=${BANXU_TOOLCHAIN:-"$PROJECT_DIR/.toolchain"}
if [ -f "$TOOLCHAIN_DIR/env.sh" ]; then
    . "$TOOLCHAIN_DIR/env.sh"
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo 'Set JAVA_HOME to JDK 17, or run scripts/setup-toolchain.py first.' >&2
    exit 1
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ] && [ ! -f "$PROJECT_DIR/local.properties" ]; then
    echo 'Set ANDROID_HOME to your Android SDK, or run scripts/setup-toolchain.py first.' >&2
    exit 1
fi
cd "$PROJECT_DIR"
if [ "$#" -eq 0 ]; then
    set -- assembleDebug lintDebug testDebugUnitTest
fi
exec "$PROJECT_DIR/gradlew" --console=plain "$@"
