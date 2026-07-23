#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
KEYSTORE_FILE="$PROJECT_DIR/release-keystore.jks"
PROPERTIES_FILE="$PROJECT_DIR/keystore.properties"
KEY_ALIAS="video-wallpaper"

if [[ -e "$KEYSTORE_FILE" || -e "$PROPERTIES_FILE" ]]; then
  echo "Release signing files already exist; nothing was overwritten."
  echo "Remove them manually only if you intentionally want a new signing identity."
  exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
  echo "keytool was not found. Install JDK 17 or newer first."
  exit 1
fi

if ! command -v openssl >/dev/null 2>&1; then
  echo "openssl was not found."
  exit 1
fi

umask 077
SIGNING_PASSWORD="$(openssl rand -hex 24)"

keytool -genkeypair \
  -keystore "$KEYSTORE_FILE" \
  -storetype PKCS12 \
  -storepass "$SIGNING_PASSWORD" \
  -keypass "$SIGNING_PASSWORD" \
  -alias "$KEY_ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=VideoWallpaper, OU=Android, O=VideoWallpaper, L=Unknown, ST=Unknown, C=US" \
  >/dev/null

{
  printf 'storeFile=release-keystore.jks\n'
  printf 'storePassword=%s\n' "$SIGNING_PASSWORD"
  printf 'keyAlias=%s\n' "$KEY_ALIAS"
  printf 'keyPassword=%s\n' "$SIGNING_PASSWORD"
} > "$PROPERTIES_FILE"

chmod 600 "$KEYSTORE_FILE" "$PROPERTIES_FILE"

echo "Release signing is ready."
echo "Back up both files securely; losing them prevents future app updates:"
echo "  $KEYSTORE_FILE"
echo "  $PROPERTIES_FILE"
