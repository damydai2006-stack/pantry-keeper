#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew check --no-daemon
if [[ -f web/package.json ]]; then
  (cd web && npm test)
else
  echo "web/ is not part of this PR; skipped npm test"
fi
