#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="$SCRIPT_DIR/apache-maven/target"
SNAPSHOT="${SNAPSHOT:-false}"
LAUNCHER="${LAUNCHER:-scripts/mvn}"

if [ "$SNAPSHOT" = "true" ]; then
  VERSION="4.1.0-SNAPSHOT"
else
  VERSION="4.1.0"
fi

MAVEN_HOME="$TARGET_DIR/apache-maven-$VERSION"

# Check if Maven distribution exists, extract if needed
if [ ! -d "$MAVEN_HOME" ]; then
  if [ ! -d "$TARGET_DIR" ]; then
    echo "Error: target directory does not exist. Build Maven first:"
    echo "  mvn clean package -DskipTests -Drat.skip=true -Dcheckstyle.skip=true"
    exit 1
  fi
  TARBALL="$TARGET_DIR/apache-maven-$VERSION-bin.tar.gz"
  if [ ! -f "$TARBALL" ]; then
    echo "Error: $TARBALL not found. Build Maven first:"
    echo "  mvn clean package -DskipTests -Drat.skip=true -Dcheckstyle.skip=true"
    exit 1
  fi
  echo "Extracting $TARBALL..."
  tar -xzf "$TARBALL" -C "$TARGET_DIR"
fi

# Platform-specific classpath separator. Windows native-image also needs
# Windows-style paths; use cygpath -m (forward slashes) so they stay valid
# inside the native-image argument file, where backslashes are escape chars.
SEP=":"
WINDOWS=false
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=";"; WINDOWS=true ;;
esac

# Build classpath from boot/ (classworlds - still needed internally by Maven)
# and all JARs from lib/.
CLASSPATH=""
for jar in "$MAVEN_HOME"/boot/*.jar "$MAVEN_HOME"/lib/*.jar; do
  if $WINDOWS; then
    jar="$(cygpath -m "$jar")"
  fi
  if [ -z "$CLASSPATH" ]; then
    CLASSPATH="$jar"
  else
    CLASSPATH="$CLASSPATH$SEP$jar"
  fi
done

# Create a distribution layout
DIST_DIR="$SCRIPT_DIR/build"
mkdir -p "$DIST_DIR"/bin "$DIST_DIR"/conf "$DIST_DIR"/lib

NATIVE_IMAGE="native-image"
if $WINDOWS; then
  NATIVE_IMAGE="native-image.cmd"
fi

# The classpath is far too long for the Windows command line (native-image.cmd
# is a batch wrapper bound by cmd.exe's ~8191-char limit), so pass it via a
# native-image argument file instead of inline.
ARGFILE="$DIST_DIR/native-image.args"
printf -- '-classpath "%s"\n' "$CLASSPATH" > "$ARGFILE"
ARGFILE_REF="@$ARGFILE"
if $WINDOWS; then
  ARGFILE_REF="@$(cygpath -m "$ARGFILE")"
fi

"$NATIVE_IMAGE" \
  "$ARGFILE_REF" \
  -Dguice_bytecode_gen_option=DISABLED \
  --enable-https \
  -H:ConfigurationFileDirectories=reflection \
  --initialize-at-build-time=org.slf4j,org.apache.commons.logging,org.apache.maven.slf4j,org.apache.maven.logging,org.apache.maven.api.cli.logging,org.apache.maven.cli.logging,org.apache.maven.cling.logging,org.apache.maven.cling.invoker.logging,org.apache.maven.monitor.logging,org.apache.maven.plugin.logging,org.codehaus.plexus.logging \
  --initialize-at-run-time=jdk.internal.jrtfs.SystemImage \
  org.apache.maven.cling.MavenCling \
  -o "$DIST_DIR"/bin/nmvn-native

# Copy the platform-appropriate launcher
if $WINDOWS; then
  # Windows: native-image emits nmvn-native.exe and the launcher is a .cmd
  cp "$SCRIPT_DIR/${LAUNCHER}.cmd" "$DIST_DIR"/bin/mvn.cmd
else
  cp "$SCRIPT_DIR/$LAUNCHER" "$DIST_DIR"/bin/mvn
  chmod +x "$DIST_DIR"/bin/mvn
fi
chmod +x "$DIST_DIR"/bin/nmvn-native* 2>/dev/null || true

cp -r "$MAVEN_HOME"/conf/* "$DIST_DIR"/conf/
cp -r "$MAVEN_HOME"/lib/* "$DIST_DIR"/lib/
cp "$MAVEN_HOME"/README.txt "$DIST_DIR"/ 2>/dev/null || true