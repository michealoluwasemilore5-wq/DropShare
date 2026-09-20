#!/usr/bin/env bash
set -euo pipefail

# The project bundles a small Gradle bootstrap script instead of a binary wrapper JAR.
# It downloads Gradle 8.13 when needed and reuses the local cache afterward.
./gradlew --version
ACTION="${1:-assembleDebug}"
./gradlew --no-daemon "$ACTION"
