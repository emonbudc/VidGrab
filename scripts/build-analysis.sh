#!/usr/bin/env bash
set -euo pipefail

: "${STATICRYPT_PASSWORD:?Set STATICRYPT_PASSWORD before building the dashboard}"

SALT="f85da0edb56f6bfcccfb541d159fbdc7"

npx --yes staticrypt@3 scripts/analysis/index.html \
  -d docs/analysis \
  -c false \
  -s "$SALT" \
  --remember false \
  --short \
  --template-title "VidGrab Analytics" \
  --template-instructions "Admin access only." \
  --template-button "Unlock dashboard" \
  --template-placeholder "Password" \
  --template-error "Wrong password." \
  --template-color-primary "#4f8cff" \
  --template-color-secondary "#05070c"
