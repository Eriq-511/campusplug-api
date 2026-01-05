#!/bin/sh

# Minimal Maven Wrapper bootstrapper.

set -euf

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if [ ! -f "$BASE_DIR/.mvn/wrapper/maven-wrapper.jar" ]; then
  echo "Missing .mvn/wrapper/maven-wrapper.jar. Run mvnw.cmd on Windows, or download the jar." 1>&2
  exit 1
fi

JAVA_CMD="java"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
fi

exec "$JAVA_CMD" -classpath "$BASE_DIR/.mvn/wrapper/maven-wrapper.jar" -Dmaven.multiModuleProjectDirectory="$BASE_DIR" org.apache.maven.wrapper.MavenWrapperMain "$@"
