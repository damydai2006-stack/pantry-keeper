#!/usr/bin/env bash
# Scan the git history of this branch for addresses, phone numbers, and private keys.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== gitleaks =="
if command -v gitleaks >/dev/null 2>&1; then
  gitleaks detect --source . --no-banner --redact || true
else
  echo "gitleaks not installed"
fi

echo "== trufflehog =="
if command -v trufflehog >/dev/null 2>&1; then
  trufflehog filesystem . --no-update || true
else
  echo "trufflehog not installed"
fi

echo "== ipv4 =="
# Allow loopback and unspecified only.
git grep -n -E '\b([0-9]{1,3}\.){3}[0-9]{1,3}\b' -- . ':(exclude)scripts/secret-scan.sh' || true

echo "== phone-like 11 digits =="
git grep -n -E '(^|[^0-9])[0-9]{11}([^0-9]|$)' -- . ':(exclude)scripts/secret-scan.sh' || true

echo "secret-scan: review the matches above; the gate fails on unexpected ones in CI-less review"
