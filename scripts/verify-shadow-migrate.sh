#!/usr/bin/env bash
# Build the fat jar and run Flyway migrate from that jar against embedded PostgreSQL.
# Does not need Docker. Prints no secrets.
set -euo pipefail
cd "$(dirname "$0")/.."
exec ./gradlew :server:verifyShadowMigrate "$@"
