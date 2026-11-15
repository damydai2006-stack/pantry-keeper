#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew check --no-daemon
if command -v pwsh >/dev/null 2>&1; then
  pwsh -NoProfile -File deploy/windows/install-secrets.test.ps1
fi
if [[ -f web/package.json ]]; then
  (cd web && npm test)
else
  echo "web/ is not part of this PR; skipped npm test"
fi
