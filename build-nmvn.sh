#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="$SCRIPT_DIR/apache-maven/target"
MAVEN_HOME="$TARGET_DIR/apache-maven-4.1.0-SNAPSHOT"

# Check if Maven distribution exists, extract if needed
if [ ! -d "$MAVEN_HOME" ]; then
  if [ ! -d "$TARGET_DIR" ]; then
    echo "Error: target directory does not exist. Build Maven first:"
    echo "  mvn clean package -DskipTests -Drat.skip=true"
    exit 1
  fi
  TARBALL="$TARGET_DIR/apache-maven-4.1.0-SNAPSHOT-bin.tar.gz"
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

## question: where -H:+SupportPredefinedClasses was not needed when -H:+RuntimeClassLoading -H:EnableURLProtocols=jar was not there. How those two features interact with each other?
# Error: Cannot predefine class with hash zmplSph1v1JKf5uIzdezG9 from file:/Users/ihorak/Developer/native-maven/maven/reflection/agent-extracted-predefined-classes/ because class predefinition is disabled. Enable this feature using option -H:+SupportPredefinedClasses.
#   -H:Preserve=package=java.util.stream \
#  -H:Preserve=package=java.util.regex \
#  -H:Preserve=package=java.nio \
#  -H:Preserve=package=java.nio.file \
#  -H:Preserve=package=java.net \
#  --initialize-at-build-time=org.slf4j,org.apache.commons.logging,org.apache.maven.slf4j,org.apache.maven.logging,org.apache.maven.api.cli.logging,org.apache.maven.cli.logging,org.apache.maven.cling.logging,org.apache.maven.cling.invoker.logging,org.apache.maven.monitor.logging,org.apache.maven.plugin.logging,org.codehaus.plexus.logging \


# jdk.compiler and java.compiler are preserved as modules for Spotless/Palantir/Google Java Format
# which heavily use javac internals. This requires initialize-at-build-time for classes that
# end up in the image heap, and initialize-at-run-time for classes with native memory pointers.

native-image \
  -classpath "$CLASSPATH" \
  -Dguice_bytecode_gen_option=DISABLED \
  --enable-https \
  -H:+UnlockExperimentalVMOptions \
  -H:+AllowJRTFileSystem \
  -H:+RuntimeClassLoading \
  -H:EnableURLProtocols=jar \
  -H:ConfigurationFileDirectories=reflection4 \
  -H:Preserve=module=java.base \
  -H:Preserve=module=java.logging \
  -H:Preserve=module=java.xml \
  -H:Preserve=module=java.desktop \
  -H:Preserve=module=java.compiler \
  -H:Preserve=module=jdk.compiler \
  -H:Preserve=package=org.apache.maven.artifact.* \
  -H:Preserve=package=org.apache.maven.project.* \
  -H:Preserve=package=org.apache.maven.plugin.* \
  -H:Preserve=package=org.apache.maven.archiver.* \
  -H:Preserve=package=org.apache.commons.logging.impl.* \
  -H:Preserve=package=org.apache.maven.api.* \
  -H:Preserve=package=org.eclipse.aether.* \
  -H:Preserve=package=org.slf4j.* \
  -H:Preserve=package=org.codehaus.plexus.* \
  --initialize-at-build-time=org.slf4j,org.apache.maven.slf4j,org.apache.maven.logging,com.sun.tools.javac.api.JavacTool \
  --initialize-at-run-time=jdk.internal.org.jline.terminal.impl.ffm.CLibrary \
  org.apache.maven.cling.MavenCling \
  nmvn-native