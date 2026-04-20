#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="$SCRIPT_DIR/apache-maven/target"
MAVEN_HOME="$TARGET_DIR/apache-maven-4.1.0"

# Check if Maven distribution exists, extract if needed
if [ ! -d "$MAVEN_HOME" ]; then
  if [ ! -d "$TARGET_DIR" ]; then
    echo "Error: target directory does not exist. Build Maven first:"
    echo "  mvn clean package -DskipTests -Drat.skip=true"
    exit 1
  fi
  TARBALL="$TARGET_DIR/apache-maven-4.1.0-bin.tar.gz"
  if [ ! -f "$TARBALL" ]; then
    echo "Error: $TARBALL not found. Build Maven first:"
    echo "  mvn clean package -DskipTests -Drat.skip=true"
    exit 1
  fi
  echo "Extracting $TARBALL..."
  tar -xzf "$TARBALL" -C "$TARGET_DIR"
fi

# Build classpath from boot/ (classworlds - still needed internally by Maven)
CLASSPATH=""
for jar in "$MAVEN_HOME"/boot/*.jar; do
  if [ -z "$CLASSPATH" ]; then
    CLASSPATH="$jar"
  else
    CLASSPATH="$CLASSPATH:$jar"
  fi
done

# Add all JARs from lib/
for jar in "$MAVEN_HOME"/lib/*.jar; do
  if [ -z "$CLASSPATH" ]; then
    CLASSPATH="$jar"
  else
    CLASSPATH="$CLASSPATH:$jar"
  fi
done

native-image \
  -classpath "$CLASSPATH" \
  -Dguice_bytecode_gen_option=DISABLED \
  --enable-https \
  -H:+AllowJRTFileSystem \
  -H:ConfigurationFileDirectories=reflection \
  --initialize-at-build-time=org.slf4j,org.apache.commons.logging,org.apache.maven.slf4j,org.apache.maven.logging,org.apache.maven.api.cli.logging,org.apache.maven.cli.logging,org.apache.maven.cling.logging,org.apache.maven.cling.invoker.logging,org.apache.maven.monitor.logging,org.apache.maven.plugin.logging,org.codehaus.plexus.logging \
  org.apache.maven.cling.MavenCling \
  -o dist/nmvn-native