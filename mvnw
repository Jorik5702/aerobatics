#!/bin/sh
# ----------------------------------------------------------------------------
# Self-contained Maven startup script for this project.
# Downloads Apache Maven if it is not already available in .mvn/wrapper/dists.
# On macOS, maps './mvnw exec:java' to a forked Java process with -XstartOnFirstThread.
# ----------------------------------------------------------------------------

set -e

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER_DIR="$BASE_DIR/.mvn/wrapper"
WRAPPER_PROPERTIES="$WRAPPER_DIR/maven-wrapper.properties"
MAVEN_VERSION="3.9.11"
MAVEN_DIR="$WRAPPER_DIR/dists/apache-maven-$MAVEN_VERSION"
MAVEN_BIN="$MAVEN_DIR/bin/mvn"
MAVEN_ZIP="$WRAPPER_DIR/dists/apache-maven-$MAVEN_VERSION-bin.zip"
MAIN_CLASS="ch.flighttrack.FlightTrackApp"

read_property() {
  sed -n "s/^$1=//p" "$WRAPPER_PROPERTIES" | tail -n 1
}

mkdir -p "$WRAPPER_DIR/dists"

if [ ! -x "$MAVEN_BIN" ]; then
  DISTRIBUTION_URL=$(read_property distributionUrl)
  if [ -z "$DISTRIBUTION_URL" ]; then
    echo "Error: distributionUrl is missing in $WRAPPER_PROPERTIES" >&2
    exit 1
  fi

  echo "Downloading Apache Maven $MAVEN_VERSION..."
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$MAVEN_ZIP" "$DISTRIBUTION_URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$MAVEN_ZIP" "$DISTRIBUTION_URL"
  else
    echo "Error: curl or wget is required to download Apache Maven." >&2
    exit 1
  fi

  rm -rf "$MAVEN_DIR"
  unzip -q "$MAVEN_ZIP" -d "$WRAPPER_DIR/dists"
  chmod +x "$MAVEN_BIN"
fi

if [ "$(uname -s)" = "Darwin" ] && [ "$1" = "exec:java" ]; then
  shift
  "$MAVEN_BIN" -q -f "$BASE_DIR/pom.xml" compile dependency:build-classpath -Dmdep.outputFile="$BASE_DIR/target/classpath.txt"
  CLASSPATH="$BASE_DIR/target/classes:$(cat "$BASE_DIR/target/classpath.txt")"
  exec java -XstartOnFirstThread -cp "$CLASSPATH" "$MAIN_CLASS" "$@"
fi

exec "$MAVEN_BIN" -f "$BASE_DIR/pom.xml" "$@"
