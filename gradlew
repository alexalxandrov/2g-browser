#!/usr/bin/env sh
# Small bootstrap wrapper for environments where the standard gradle-wrapper.jar is not present.
# Prefer an installed Gradle 9.6.0; otherwise download the official distribution.
set -eu
VERSION="9.6.0"
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
GRADLE_BIN="$GRADLE_USER_HOME/slowline-bootstrap/gradle-$VERSION/bin/gradle"
if command -v gradle >/dev/null 2>&1; then
  if gradle --version 2>/dev/null | grep -q "Gradle $VERSION"; then
    exec gradle -p "$ROOT_DIR" "$@"
  fi
fi
if [ ! -x "$GRADLE_BIN" ]; then
  command -v curl >/dev/null 2>&1 || { echo "Install Gradle $VERSION or curl + unzip." >&2; exit 1; }
  command -v unzip >/dev/null 2>&1 || { echo "Install unzip to bootstrap Gradle." >&2; exit 1; }
  DEST="$GRADLE_USER_HOME/slowline-bootstrap"
  mkdir -p "$DEST"
  TMP="$DEST/gradle-$VERSION-bin.zip"
  echo "Downloading official Gradle $VERSION distribution..."
  curl --fail --location --retry 2 "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$TMP"
  unzip -q -o "$TMP" -d "$DEST"
  rm -f "$TMP"
fi
exec "$GRADLE_BIN" -p "$ROOT_DIR" "$@"
