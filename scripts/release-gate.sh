#!/usr/bin/env bash
# Release gate: debug hooks, test clock, and tracked secrets.
set -euo pipefail
cd "$(dirname "$0")/.."

fail() { echo "release-gate: $1" >&2; exit 1; }

tracked() { git ls-files; }

tracked | grep -E '(^|/)server\.env$|\.pem$|\.key$|\.jks$|\.p12$|\.pfx$' && fail "tracked secret file" || true

if git ls-files | grep -E '(^|/)\.env$' | grep -v '\.env\.example$' >/dev/null; then
  fail "tracked .env file"
fi

grep -q '^PANTRY_PROFILE=prod$' .env.example || fail ".env.example must default PANTRY_PROFILE=prod"
if grep -E '^PANTRY_TEST_CLOCK=(on|true|yes|1)$' .env.example deploy -r --include='*.ps1' --include='*.template' --include='*.example' . >/dev/null 2>&1; then
  fail "test clock is enabled in a template"
fi

grep -q 'test clock is enabled but PANTRY_PROFILE is prod' server/src/main/kotlin/com/pantrykeeper/server/config/AppConfig.kt \
  || fail "production startup does not refuse an enabled test clock"
grep -q 'ifEmpty { "prod" }' server/src/main/kotlin/com/pantrykeeper/server/config/AppConfig.kt \
  || fail "default profile is not prod"

# Direct wall-clock calls are banned outside the single system clock wiring.
banned='Instant\.now\(\s*\)|LocalDate\.now\(\s*\)|LocalDateTime\.now\(\s*\)|OffsetDateTime\.now\(\s*\)|ZonedDateTime\.now\(\s*\)|System\.currentTimeMillis\(\s*\)|Clock\.System'
if grep -R -n -E "$banned" --include='*.kt' core server | grep -v 'Clock.systemUTC()' ; then
  fail "found a direct now() call"
fi
if grep -R -n 'Clock.systemUTC()' --include='*.kt' server | grep -v 'Main.kt'; then
  fail "Clock.systemUTC() is only allowed in Main.kt"
fi

if grep -R -n -E 'SimulateToday|debug-fill-test-code|FillTestCode' --include='*.kt' core server; then
  fail "debug hook present in server or core"
fi

if grep -R -n -E '调试填充|DEBUG_PHONE|DEBUG_CODE|DEBUG_INPUT' android --include='*.kt' --include='*.xml' --exclude-dir=build | grep -v '/src/debug/'; then
  fail "debug login hook is present outside the debug source set"
fi

echo "release-gate: ok"
