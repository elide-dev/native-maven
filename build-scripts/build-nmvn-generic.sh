#!/bin/bash
#
# Builds the GENERIC nmvn native image (build/nmvn-generic): Maven with ONLY the default
# lifecycle plugins baked in — the plugin versions a project WITHOUT a version-pinning parent
# requests (crema: everything else loads dynamically at run time; non-crema: everything else
# runs on the in-process HotSpot JVM fallback).
#
# THIN driver over the Maven native profiles: the plugin set is the @default-lifecycle sentinel,
# which sanitize-maven-plugin:generate-realm-spec expands from the compiled version constants of
# the DIST's maven-core (ConstantValue attributes — always matches the binary actually embedded
# in the image, even if the working tree has moved on since the dist was built).
#
# Usage:
#   ./build-scripts/build-nmvn-generic.sh
#
# Output (same conventions as build-nmvn-catalog.sh):
#   build/nmvn-generic(.exe)   — the image
#   build/nmvn-generic.plugins — newline-separated baked GAVs (as expanded by the mojo)
# Optional overrides: NMVN_OUT_DIR, NMVN_MAVEN_HOME, NMVN_VARIANT=crema (default) or non-crema.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
NMVN_OUT_DIR="${NMVN_OUT_DIR:-$ROOT_DIR/build}"
mkdir -p "$NMVN_OUT_DIR"

if [ "$#" -gt 0 ]; then
  echo "Usage: ./build-scripts/build-nmvn-generic.sh"
  echo "       (no arguments; the default-lifecycle set is resolved from the Maven dist by the"
  echo "        generate-realm-spec mojo — use 'mvn -Pnative ... -Dnmvn.plugins=...' or"
  echo "        build-nmvn-catalog.sh to bake an explicit list)"
  exit 1
fi

NMVN_VARIANT="${NMVN_VARIANT:-crema}"
case "$NMVN_VARIANT" in
  crema) MVN_PROFILES="native,crema" ;;
  non-crema) MVN_PROFILES="native" ;;
  *)
    echo "Error: NMVN_VARIANT='$NMVN_VARIANT' (expected 'crema' or 'non-crema')" >&2
    exit 2
    ;;
esac

# Dist resolution: same glob as build-nmvn-catalog.sh (CI strips -SNAPSHOT from the poms while
# keeping the -SNAPSHOT dist dir name, so the profile's default cannot be relied on there).
NMVN_MAVEN_HOME="${NMVN_MAVEN_HOME:-}"
if [ -z "$NMVN_MAVEN_HOME" ]; then
  for d in "$ROOT_DIR"/apache-maven/target/apache-maven-*; do
    if [ -d "$d" ] && { [ -f "$d/bin/mvn" ] || [ -f "$d/bin/mvn.cmd" ]; }; then
      NMVN_MAVEN_HOME="$d"
      break
    fi
  done
fi
if [ -z "$NMVN_MAVEN_HOME" ] || [ ! -d "$NMVN_MAVEN_HOME/lib" ]; then
  echo "Error: no Maven dist under $ROOT_DIR/apache-maven/target — build it first" >&2
  echo "       (./mvnw clean install -DskipTests from the repo root)" >&2
  exit 2
fi

TARGET_DIR="$ROOT_DIR/native/launcher/target"
rm -f "$TARGET_DIR/nmvn-generic" "$TARGET_DIR/nmvn-generic.exe" \
      "$NMVN_OUT_DIR/nmvn-generic" "$NMVN_OUT_DIR/nmvn-generic.exe"

(
  cd "$ROOT_DIR"
  ./mvnw -P"$MVN_PROFILES" package -pl native/launcher -am -B -DskipTests \
    -Dnmvn.plugins=@default-lifecycle \
    -Dnmvn.imageName=nmvn-generic \
    -Dnmvn.mavenHome="$NMVN_MAVEN_HOME"
)

OUT_SRC=""
OUT_DST="$NMVN_OUT_DIR/nmvn-generic"
if [ -f "$TARGET_DIR/nmvn-generic" ]; then
  OUT_SRC="$TARGET_DIR/nmvn-generic"
elif [ -f "$TARGET_DIR/nmvn-generic.exe" ]; then
  OUT_SRC="$TARGET_DIR/nmvn-generic.exe"
  OUT_DST="$NMVN_OUT_DIR/nmvn-generic.exe"
fi
if [ -z "$OUT_SRC" ]; then
  echo "Error: build reported success but produced no nmvn-generic(.exe) under $TARGET_DIR" >&2
  exit 1
fi
mv "$OUT_SRC" "$OUT_DST"
# plugins.txt = the mojo's record of the expanded entry list
cp "$TARGET_DIR/nmvn/plugins.txt" "$NMVN_OUT_DIR/nmvn-generic.plugins"

echo ">>> Done: $OUT_DST ($(du -m "$OUT_DST" | cut -f1)MB)"
echo ">>>       $NMVN_OUT_DIR/nmvn-generic.plugins"
