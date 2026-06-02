#!/bin/sh
# ----------------------------------------------------------------------------
# Apache Maven Wrapper startup script, simplified for this project.
# ----------------------------------------------------------------------------

set -e

BASE_DIR=$(cd "$(dirname "$0")" && pwd)
WRAPPER_DIR="$BASE_DIR/.mvn/wrapper"
WRAPPER_JAR="$WRAPPER_DIR/maven-wrapper.jar"
WRAPPER_PROPERTIES="$WRAPPER_DIR/maven-wrapper.properties"

if [ -z "$JAVA_HOME" ]; then
  JAVACMD=$(command -v java)
else
  JAVACMD="$JAVA_HOME/bin/java"
fi

if [ ! -x "$JAVACMD" ]; then
  echo "Error: JAVA_HOME is not set correctly and java was not found on PATH." >&2
  exit 1
fi

if [ ! -f "$WRAPPER_JAR" ]; then
  WRAPPER_URL=$(sed -n 's/^wrapperUrl=//p' "$WRAPPER_PROPERTIES")
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$WRAPPER_JAR" "$WRAPPER_URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$WRAPPER_JAR" "$WRAPPER_URL"
  else
    echo "Error: curl or wget is required to download the Maven Wrapper jar." >&2
    exit 1
  fi
fi

exec "$JAVACMD" -Dmaven.multiModuleProjectDirectory="$BASE_DIR" -jar "$WRAPPER_JAR" "$@"
