#!/bin/bash
# Copyright (c) 2026 Ordo contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Puts the Google Play upload secrets into the GitHub repository (gh secret set) without printing them.
# Values come from environment variables, the service account from its JSON file:
#   ORDO_KEYSTORE=upload.jks (or ORDO_UPLOAD_KEYSTORE_BASE64=<base64>), ORDO_STORE_PASSWORD, ORDO_KEY_ALIAS,
#   ORDO_KEY_PASSWORD (optional, defaults to ORDO_STORE_PASSWORD as in PKCS12 keystores)
# usage: utils/play-secrets.sh <service-account.json>     (see docs/PLAY-AUTO-UPLOAD.md)
set -euo pipefail
repo=${REPO:-BEKO2210/ordo}
json=${1:?usage: $0 <service-account.json>}
: "${ORDO_STORE_PASSWORD:?missing}" "${ORDO_KEY_ALIAS:?missing}"
export ORDO_KEY_PASSWORD=${ORDO_KEY_PASSWORD:-$ORDO_STORE_PASSWORD}
if [ -z "${ORDO_UPLOAD_KEYSTORE_BASE64:-}" ]; then
    ORDO_UPLOAD_KEYSTORE_BASE64=$(base64 -w0 "${ORDO_KEYSTORE:?set ORDO_KEYSTORE or ORDO_UPLOAD_KEYSTORE_BASE64}")
fi

# Refuse broken values before they reach GitHub.
python3 -c 'import json, sys; d = json.load(open(sys.argv[1])); assert d.get("type") == "service_account" and d.get("private_key"), "not a service account key"' "$json"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
printf %s "$ORDO_UPLOAD_KEYSTORE_BASE64" | base64 -d > "$tmp/upload.jks"
if command -v keytool > /dev/null; then
    keytool -list -keystore "$tmp/upload.jks" -storepass:env ORDO_STORE_PASSWORD -alias "$ORDO_KEY_ALIAS" > /dev/null
else
    echo "keytool not found: keystore password and alias not checked" >&2
fi

gh secret set PLAY_SERVICE_ACCOUNT_JSON -R "$repo" < "$json"
printf %s "$ORDO_UPLOAD_KEYSTORE_BASE64" | gh secret set ORDO_UPLOAD_KEYSTORE_BASE64 -R "$repo"
printf %s "$ORDO_STORE_PASSWORD" | gh secret set ORDO_STORE_PASSWORD -R "$repo"
printf %s "$ORDO_KEY_ALIAS" | gh secret set ORDO_KEY_ALIAS -R "$repo"
printf %s "$ORDO_KEY_PASSWORD" | gh secret set ORDO_KEY_PASSWORD -R "$repo"
gh secret list -R "$repo"
