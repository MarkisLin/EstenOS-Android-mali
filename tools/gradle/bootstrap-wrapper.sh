#!/usr/bin/env sh
set -eu

# DroidDeck keeps the Gradle version pinned in gradle-wrapper.properties. Some source
# archives omit the binary wrapper JAR, so restore only the exact upstream 8.10.2
# wrapper and verify it before Gradle is allowed to run.
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
WRAPPER="$ROOT/gradle/wrapper/gradle-wrapper.jar"
EXPECTED_SHA256="2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046"
URL="https://raw.githubusercontent.com/gradle/gradle/v8.10.2/gradle/wrapper/gradle-wrapper.jar"

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        echo "No se encontró sha256sum ni shasum para verificar Gradle Wrapper." >&2
        exit 2
    fi
}

if [ -f "$WRAPPER" ]; then
    actual=$(sha256_file "$WRAPPER")
    if [ "$actual" = "$EXPECTED_SHA256" ]; then
        exit 0
    fi
    echo "El Gradle Wrapper existente no coincide con el SHA-256 esperado; no se usará." >&2
    rm -f "$WRAPPER"
fi

mkdir -p "$(dirname -- "$WRAPPER")"
tmp="$WRAPPER.tmp.$$"
trap 'rm -f "$tmp"' EXIT HUP INT TERM

if command -v curl >/dev/null 2>&1; then
    curl --fail --location --silent --show-error --retry 3 --connect-timeout 15 \
        --output "$tmp" "$URL"
elif command -v wget >/dev/null 2>&1; then
    wget -q --tries=3 --timeout=30 -O "$tmp" "$URL"
else
    echo "Se necesita curl o wget para restaurar Gradle Wrapper." >&2
    exit 2
fi

actual=$(sha256_file "$tmp")
if [ "$actual" != "$EXPECTED_SHA256" ]; then
    echo "Gradle Wrapper descargado rechazado: SHA-256 inesperado ($actual)." >&2
    exit 3
fi

mv "$tmp" "$WRAPPER"
trap - EXIT HUP INT TERM
echo "Gradle Wrapper 8.10.2 restaurado y verificado."
