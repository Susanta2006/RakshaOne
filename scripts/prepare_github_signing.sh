#!/usr/bin/env bash
set -euo pipefail

# Generates an Android release signing keystore (.jks), base64-encodes it for
# GitHub Actions (RELEASE_KEYSTORE_BASE64), and optionally uploads the 4 secrets
# via GitHub CLI (`gh`).

KEYSTORE_FILE="${1:-rakshaone-release.jks}"
KEY_ALIAS="${KEY_ALIAS:-rakshaone_upload}"
VALIDITY_DAYS="${VALIDITY_DAYS:-10000}"
OWNER_DN="${OWNER_DN:-CN=Susanta Banik,OU=RakshaOne,O=Susanta Banik}"

if ! command -v keytool >/dev/null 2>&1; then
  echo "Error: 'keytool' not found. Install JDK 17+ or JDK 21 first." >&2
  exit 1
fi

if [ -f "$KEYSTORE_FILE" ]; then
  echo "Using existing keystore: $KEYSTORE_FILE"
else
  read -r -s -p "Enter new keystore password (min 6 chars): " KEYSTORE_PASSWORD
  echo
  read -r -s -p "Confirm keystore password: " KEYSTORE_PASSWORD_CONFIRM
  echo
  if [ "$KEYSTORE_PASSWORD" != "$KEYSTORE_PASSWORD_CONFIRM" ] || [ ${#KEYSTORE_PASSWORD} -lt 6 ]; then
    echo "Error: Passwords do not match or are shorter than 6 characters." >&2
    exit 1
  fi

  read -r -s -p "Enter key password (press Enter to reuse keystore password): " KEY_PASSWORD
  echo
  KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"

  keytool -genkeypair -v \
    -keystore "$KEYSTORE_FILE" \
    -storetype JKS \
    -storepass "$KEYSTORE_PASSWORD" \
    -alias "$KEY_ALIAS" \
    -keypass "$KEY_PASSWORD" \
    -keyalg RSA \
    -keysize 2048 \
    -validity "$VALIDITY_DAYS" \
    -dname "$OWNER_DN"
fi

if [ -z "${KEYSTORE_PASSWORD:-}" ]; then
  read -r -s -p "Enter keystore password for $KEYSTORE_FILE: " KEYSTORE_PASSWORD
  echo
fi
if [ -z "${KEY_PASSWORD:-}" ]; then
  read -r -s -p "Enter key password for alias '$KEY_ALIAS' (Enter to reuse keystore password): " KEY_PASSWORD
  echo
  KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"
fi

echo
echo "Verifying keystore and extracting certificate fingerprints..."
keytool -list -v -keystore "$KEYSTORE_FILE" -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS" | grep -E "Alias name:|Owner:|SHA1:|SHA256:"

BASE64_OUT="${KEYSTORE_FILE}.base64"
base64 < "$KEYSTORE_FILE" | tr -d '\r\n' > "$BASE64_OUT"

echo
echo "Base64 keystore written to: $BASE64_OUT"
echo "WARNING: Never commit '$KEYSTORE_FILE' or '$BASE64_OUT' to git."

if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  read -r -p "Upload secrets directly to the current GitHub repo via 'gh'? [y/N]: " UPLOAD_GH
  if [[ "$UPLOAD_GH" =~ ^[Yy]$ ]]; then
    gh secret set RELEASE_KEYSTORE_BASE64 < "$BASE64_OUT"
    printf '%s' "$KEYSTORE_PASSWORD" | gh secret set KEYSTORE_PASSWORD
    printf '%s' "$KEY_ALIAS" | gh secret set KEY_ALIAS
    printf '%s' "$KEY_PASSWORD" | gh secret set KEY_PASSWORD
    echo "Uploaded all 4 GitHub Actions signing secrets cleanly."
    exit 0
  fi
fi

echo
echo "Add these 4 Repository Secrets in GitHub -> Settings -> Secrets and variables -> Actions:"
echo "  1. RELEASE_KEYSTORE_BASE64 : (entire single-line contents of $BASE64_OUT)"
echo "  2. KEYSTORE_PASSWORD       : (your keystore password)"
echo "  3. KEY_ALIAS               : $KEY_ALIAS"
echo "  4. KEY_PASSWORD            : (your key password)"
