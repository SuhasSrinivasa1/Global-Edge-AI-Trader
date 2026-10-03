GLOBAL EDGE AI TRADER v1.5.0 — LADB INSTALL

APK filename:
  Global-Edge-AI-Trader-v1.5.0.apk

Put the APK in the phone's Download folder, then in LADB run:
  ls -lh /sdcard/Download/Global-Edge-AI-Trader-v1.5.0.apk
  pm install -r -g "/sdcard/Download/Global-Edge-AI-Trader-v1.5.0.apk"

Verify:
  dumpsys package com.suhas.globaledgeai | grep -E "versionCode|versionName"

Launch:
  monkey -p com.suhas.globaledgeai -c android.intent.category.LAUNCHER 1

If and ONLY if Android reports INSTALL_FAILED_UPDATE_INCOMPATIBLE / signature mismatch:
  pm uninstall com.suhas.globaledgeai
  pm install -g "/sdcard/Download/Global-Edge-AI-Trader-v1.5.0.apk"

WARNING: uninstalling deletes the app's local credentials, settings and learning ledger.
v1.5.0 uses the same signing certificate as v1.4.0, so a normal v1.4.0 -> v1.5.0 update should use -r and must not require uninstall.

Runtime diagnostics:
Settings -> Export full EOD diagnostic ZIP.
The exported bundle intentionally omits Groww credentials, TOTP secret and access token.
