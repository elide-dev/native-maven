#!/bin/bash
#
# Builds the GENERIC nmvn native image (build/nmvn-generic): Maven with ONLY the default
# lifecycle plugins baked in — the plugin versions a project WITHOUT a version-pinning parent
# requests. Everything else resolves dynamically at run time via Crema.
#
# The versions are NOT hardcoded here: they are read from the Maven distribution being baked
# (maven-core's compiled version constants, via javap ConstantValue), so this script keeps
# tracking the tree when the default bindings are bumped:
#   org.apache.maven.lifecycle.providers.packaging.AbstractLifecycleMappingProvider
#     RESOURCES / COMPILER / SUREFIRE / JAR / INSTALL / DEPLOY _PLUGIN_VERSION
#   org.apache.maven.internal.impl.DefaultLifecycleRegistry$CleanLifecycle
#     MAVEN_CLEAN_PLUGIN_VERSION
# Reading the DIST (not the sources) means the baked versions always match the binary actually
# embedded in the image, even if the working tree has moved on since the dist was built.
#
# Baked set = the default 'clean' + 'default' lifecycle bindings for jar packaging:
#   clean, resources, compiler, surefire, jar, install, deploy
# (site is a separate lifecycle and rarely part of the basic flow; add it here if that changes.)
#
# Routing is version-exact: a project that pins ANY other version of these plugins (e.g. via
# spring-boot-starter-parent) falls back to dynamic resolution for that plugin — use the
# catalog-specialized images for those stacks.
#
# Usage:
#   ./build-scripts/build-nmvn-generic.sh [--dry-run]
#
# Output (same conventions as build-nmvn-catalog.sh):
#   build/nmvn-generic(.exe)   — the image
#   build/nmvn-generic.plugins — newline-separated baked GAVs
# Optional overrides: NMVN_OUT_DIR, NMVN_WORK_DIR, NMVN_MAVEN_HOME (all forwarded to prebuilt),
# NMVN_VARIANT=crema (default) or non-crema.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
NMVN_OUT_DIR="${NMVN_OUT_DIR:-$ROOT_DIR/build}"

NMVN_VARIANT="${NMVN_VARIANT:-crema}"
PREBUILT="$SCRIPT_DIR/$NMVN_VARIANT/build-nmvn-prebuilt.sh"
if [ ! -f "$PREBUILT" ]; then
  echo "Error: NMVN_VARIANT='$NMVN_VARIANT' has no $PREBUILT (expected 'crema' or 'non-crema')" >&2
  exit 2
fi

DRY_RUN=0
if [ "${1:-}" = "--dry-run" ]; then
  DRY_RUN=1
elif [ "$#" -gt 0 ]; then
  echo "Usage: ./build-scripts/build-nmvn-generic.sh [--dry-run]"
  echo "       (plugins are not arguments here — the default-lifecycle set is resolved"
  echo "        from the Maven dist; use build-scripts/<variant>/build-nmvn-prebuilt.sh"
  echo "        to bake an explicit list)"
  exit 1
fi

# Resolve the Maven dist exactly like build-nmvn-prebuilt.sh: explicit override, else any
# packaged apache-maven-* under target/ (CI strips -SNAPSHOT; local keeps it).
TARGET_DIR="$ROOT_DIR/apache-maven/target"
if [ -n "${NMVN_MAVEN_HOME:-}" ]; then
  MAVEN_HOME="$NMVN_MAVEN_HOME"
else
  MAVEN_HOME=""
  for d in \
      "$TARGET_DIR/apache-maven-4.1.0-SNAPSHOT" \
      "$TARGET_DIR/apache-maven-4.1.0" \
      "$TARGET_DIR"/apache-maven-*; do
    if [ -d "$d" ] && { [ -f "$d/bin/mvn" ] || [ -f "$d/bin/mvn.cmd" ]; }; then
      MAVEN_HOME="$d"
      break
    fi
  done
fi
if [ -z "$MAVEN_HOME" ] || [ ! -d "$MAVEN_HOME" ]; then
  echo "Error: Maven distribution not found under $TARGET_DIR"
  echo "       Build Maven first: mvn clean package -DskipTests -Drat.skip=true"
  exit 1
fi

CORE_JAR=""
for j in "$MAVEN_HOME"/lib/maven-core-*.jar; do
  [ -f "$j" ] && CORE_JAR="$j" && break
done
if [ -z "$CORE_JAR" ]; then
  echo "Error: no maven-core jar under $MAVEN_HOME/lib"
  exit 1
fi

# javap from the same toolchain the build uses (JAVA_HOME first, PATH otherwise).
JAVAP="javap"
if [ -n "${JAVA_HOME:-}" ]; then
  for c in "$JAVA_HOME/bin/javap" "$JAVA_HOME/bin/javap.exe"; do
    if [ -x "$c" ] || [ -f "$c" ]; then
      JAVAP="$c"
      break
    fi
  done
fi

# Windows javap needs a Windows-style classpath (C:/..., not /c/...).
CORE_JAR_CP="$CORE_JAR"
if command -v cygpath >/dev/null 2>&1; then
  CORE_JAR_CP="$(cygpath -m "$CORE_JAR" 2>/dev/null || printf '%s' "$CORE_JAR")"
fi

# Read one compiled 'static final String <FIELD> = "<version>";' constant. javap -v prints the
# field declaration followed by 'ConstantValue: String <value>' within the next few lines; the
# constant-pool section never matches because pool entries have no trailing ';' on the name.
extract_version() {
  local class="$1" field="$2" version
  version=$("$JAVAP" -p -v -cp "$CORE_JAR_CP" "$class" 2>/dev/null | awk -v f="$field" '
    $0 ~ ("[ .]" f ";$")  { hit = NR }
    hit && NR <= hit + 4 && $1 == "ConstantValue:" { print $3; exit }
  ')
  if [ -z "$version" ]; then
    echo "Error: could not read $field from $class in $CORE_JAR" >&2
    echo "       (constant renamed/moved? update extract_version calls in this script)" >&2
    return 1
  fi
  printf '%s' "$version"
}

LIFECYCLE_PROVIDER=org.apache.maven.lifecycle.providers.packaging.AbstractLifecycleMappingProvider
CLEAN_LIFECYCLE='org.apache.maven.internal.impl.DefaultLifecycleRegistry$CleanLifecycle'

CLEAN_V=$(extract_version "$CLEAN_LIFECYCLE" MAVEN_CLEAN_PLUGIN_VERSION)
RESOURCES_V=$(extract_version "$LIFECYCLE_PROVIDER" RESOURCES_PLUGIN_VERSION)
COMPILER_V=$(extract_version "$LIFECYCLE_PROVIDER" COMPILER_PLUGIN_VERSION)
SUREFIRE_V=$(extract_version "$LIFECYCLE_PROVIDER" SUREFIRE_PLUGIN_VERSION)
JAR_V=$(extract_version "$LIFECYCLE_PROVIDER" JAR_PLUGIN_VERSION)
INSTALL_V=$(extract_version "$LIFECYCLE_PROVIDER" INSTALL_PLUGIN_VERSION)
DEPLOY_V=$(extract_version "$LIFECYCLE_PROVIDER" DEPLOY_PLUGIN_VERSION)

GAVS=(
  "org.apache.maven.plugins:maven-clean-plugin:$CLEAN_V"
  "org.apache.maven.plugins:maven-resources-plugin:$RESOURCES_V"
  "org.apache.maven.plugins:maven-compiler-plugin:$COMPILER_V"
  "org.apache.maven.plugins:maven-surefire-plugin:$SUREFIRE_V"
  "org.apache.maven.plugins:maven-jar-plugin:$JAR_V"
  "org.apache.maven.plugins:maven-install-plugin:$INSTALL_V"
  "org.apache.maven.plugins:maven-deploy-plugin:$DEPLOY_V"
)

echo ">>> Maven dist:  $MAVEN_HOME"
echo ">>> Default lifecycle plugins (resolved from $(basename "$CORE_JAR")):"
printf '>>>   %s\n' "${GAVS[@]}"

if [ "$DRY_RUN" = 1 ]; then
  echo ">>> --dry-run: not building"
  exit 0
fi

# prebuilt writes $NMVN_OUT_DIR/nmvn-native(.exe); wipe stale output and rename afterwards,
# same as build-nmvn-catalog.sh, so a failed run cannot be mistaken for a fresh image.
rm -f "$NMVN_OUT_DIR/nmvn-native" "$NMVN_OUT_DIR/nmvn-native.exe"

(
  cd "$ROOT_DIR"
  export NMVN_OUT_DIR
  [ -n "${NMVN_WORK_DIR:-}" ] && export NMVN_WORK_DIR
  [ -n "${NMVN_MAVEN_HOME:-}" ] && export NMVN_MAVEN_HOME
  "$PREBUILT" "${GAVS[@]}"
)

OUT_SRC=""
OUT_DST="$NMVN_OUT_DIR/nmvn-generic"
if [ -f "$NMVN_OUT_DIR/nmvn-native" ]; then
  OUT_SRC="$NMVN_OUT_DIR/nmvn-native"
elif [ -f "$NMVN_OUT_DIR/nmvn-native.exe" ]; then
  OUT_SRC="$NMVN_OUT_DIR/nmvn-native.exe"
  OUT_DST="$NMVN_OUT_DIR/nmvn-generic.exe"
fi
if [ -z "$OUT_SRC" ]; then
  echo "Error: build reported success but produced no nmvn-native(.exe) under $NMVN_OUT_DIR" >&2
  exit 1
fi
mv "$OUT_SRC" "$OUT_DST"
printf '%s\n' "${GAVS[@]}" > "$NMVN_OUT_DIR/nmvn-generic.plugins"

echo ">>> Done: $OUT_DST ($(du -m "$OUT_DST" | cut -f1)MB)"
echo ">>>       $NMVN_OUT_DIR/nmvn-generic.plugins"
