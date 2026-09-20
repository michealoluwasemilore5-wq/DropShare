#!/usr/bin/env sh
set -eu
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
GRADLE_VERSION="8.13"
CACHE_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/dropshare-gradle-$GRADLE_VERSION"
GRADLE_HOME="$CACHE_DIR/gradle-$GRADLE_VERSION"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  mkdir -p "$CACHE_DIR"
  TMP="$CACHE_DIR/gradle.zip"
  echo "Downloading Gradle $GRADLE_VERSION..."
  curl -fL --retry 3 "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$TMP"
  rm -rf "$CACHE_DIR/unpack"
  mkdir -p "$CACHE_DIR/unpack"
  unzip -q "$TMP" -d "$CACHE_DIR/unpack"
  mv "$CACHE_DIR/unpack/gradle-$GRADLE_VERSION" "$GRADLE_HOME"
  rm -rf "$CACHE_DIR/unpack" "$TMP"
fi
exec "$GRADLE_HOME/bin/gradle" "$@"
