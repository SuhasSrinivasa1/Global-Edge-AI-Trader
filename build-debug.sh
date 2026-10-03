#!/usr/bin/env bash
set -euo pipefail
gradle --no-daemon clean testDebugUnitTest assembleDebug
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
