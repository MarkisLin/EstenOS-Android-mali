#!/usr/bin/env bash
set -euo pipefail

OUT="${GITHUB_WORKSPACE:-$(pwd)}/build/ci-emulator"
mkdir -p "$OUT"

APK="$(find app/build/outputs/apk/debug -maxdepth 1 -type f -name '*.apk' | sort | head -n1)"
if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "No se encontró el APK debug." >&2
  exit 2
fi

echo "== Dispositivo"
adb shell getprop ro.product.cpu.abi | tee "$OUT/device-abi.txt"
adb shell getprop ro.build.version.sdk | tee "$OUT/device-api.txt"

echo "== Instalando APK"
adb install -r "$APK" 2>&1 | tee "$OUT/adb-install.txt"

echo "== Lanzando EstenOS"
adb logcat -c
set +e
adb shell am start -W -n com.droiddeck.launcher/.MainActivity >"$OUT/am-start.txt" 2>&1
START_RC=$?
set -e
cat "$OUT/am-start.txt"

# Give Compose, services and first-run checks enough time to settle.
sleep 10

adb shell pidof com.droiddeck.launcher >"$OUT/pid.txt" 2>/dev/null || true
adb shell dumpsys activity activities >"$OUT/activity.txt" 2>&1 || true
adb shell dumpsys package com.droiddeck.launcher >"$OUT/package.txt" 2>&1 || true
adb logcat -d -v threadtime >"$OUT/logcat.txt" 2>&1 || true
adb exec-out screencap -p >"$OUT/screenshot.png" 2>/dev/null || true

if [[ "$START_RC" -ne 0 ]]; then
  echo "MainActivity no pudo iniciarse (am start rc=$START_RC)." >&2
  exit 3
fi

if [[ ! -s "$OUT/pid.txt" ]]; then
  echo "El proceso de EstenOS murió durante el smoke test." >&2
  tail -n 200 "$OUT/logcat.txt" >&2 || true
  exit 4
fi

if grep -Eq 'FATAL EXCEPTION|ANR in com\.droiddeck\.launcher|Process: com\.droiddeck\.launcher.*has died' "$OUT/logcat.txt"; then
  echo "Android reportó un crash/ANR de EstenOS." >&2
  grep -En 'FATAL EXCEPTION|ANR in com\.droiddeck\.launcher|Process: com\.droiddeck\.launcher.*has died' "$OUT/logcat.txt" >&2 || true
  exit 5
fi

echo "Smoke test OK: APK instalado, MainActivity inició y el proceso sigue vivo."
