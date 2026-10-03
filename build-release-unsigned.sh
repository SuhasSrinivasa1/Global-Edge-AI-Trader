#!/usr/bin/env bash
set -euo pipefail
gradle --no-daemon clean testDebugUnitTest assembleRelease
echo "Unsigned APK: app/build/outputs/apk/release/app-release-unsigned.apk"
