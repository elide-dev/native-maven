#!/bin/bash
#
# Builds a SPECIALIZED nmvn native image for a given Spring Boot version: generates a minimal
# probe pom inheriting from spring-boot-starter-parent:<version>, derives the plugin set that
# a default jar-packaging Spring Boot app of that version will request (starter-parent's
# pluginManagement pins + this Maven snapshot's default lifecycle bindings), and delegates to
# build-nmvn-for-pom.sh / build-nmvn-prebuilt.sh to bake exactly those plugins.
#
# The resulting binary is keyed by (Spring Boot version x Maven snapshot): the same Boot version
# on a different Maven snapshot may bind different default-lifecycle plugin versions.
#
# Usage:
#   ./build-nmvn-for-spring.sh <boot-version> [--plugins-only] [--with g:a[,g:a...]]
#
#   <boot-version>   e.g. 4.1.0, 3.4.5 — must exist as spring-boot-starter-parent in a repo
#   --plugins-only   print the derived g:a:v list and exit without building
#   --with g:a,...   additional plugins to declare in the probe pom (version resolved by
#                    starter-parent's pluginManagement), e.g. for non-default-profile builds:
#                      org.apache.maven.plugins:maven-war-plugin       (war packaging)
#                      org.apache.maven.plugins:maven-failsafe-plugin  (integration tests)
#                      org.graalvm.buildtools:native-maven-plugin
#                      io.github.git-commit-id:git-commit-id-maven-plugin
#                      org.jetbrains.kotlin:kotlin-maven-plugin
#
# Output: nmvn-spring-<boot-version> in this directory.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ $# -lt 1 ]; then
  echo "Usage: $0 <boot-version> [--plugins-only] [--with g:a[,g:a...]]"
  exit 2
fi

BOOT_VERSION="$1"
shift

PLUGINS_ONLY=0
EXTRA_PLUGINS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --plugins-only)
      PLUGINS_ONLY=1
      shift
      ;;
    --with)
      [ $# -ge 2 ] || { echo "Error: --with requires an argument"; exit 2; }
      IFS=',' read -r -a EXTRA_PLUGINS <<< "$2"
      shift 2
      ;;
    *)
      echo "Error: unknown argument: $1"
      exit 2
      ;;
  esac
done

# ---------------------------------------------------------------------------------------------------
# 1) Generate the probe pom. A canonical Spring Boot app declares exactly one plugin
#    (spring-boot-maven-plugin); everything else arrives via default lifecycle bindings, with
#    versions pinned by starter-parent's pluginManagement. Extra plugins are declared without a
#    version for the same reason — pluginManagement resolves it, keeping version fidelity with
#    what a real build of that Boot version would request.
# ---------------------------------------------------------------------------------------------------
WORK="$SCRIPT_DIR/.prebuilt-work"
PROBE="$WORK/spring-probe-$BOOT_VERSION"
mkdir -p "$PROBE"

EXTRA_XML=""
# ${arr[@]+...} guards the expansion: bash 3.2 (macOS /bin/bash) treats "${arr[@]}" on an
# empty array as an unbound variable under set -u.
for GA in ${EXTRA_PLUGINS[@]+"${EXTRA_PLUGINS[@]}"}; do
  G="${GA%%:*}"; A="${GA#*:}"
  if [ -z "$G" ] || [ -z "$A" ] || [ "$G" = "$A" ]; then
    echo "Error: --with entries must be groupId:artifactId, got: $GA"
    exit 2
  fi
  EXTRA_XML="$EXTRA_XML
      <plugin><groupId>$G</groupId><artifactId>$A</artifactId></plugin>"
done

cat > "$PROBE/pom.xml" <<POM
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>$BOOT_VERSION</version>
    <relativePath/>
  </parent>
  <groupId>nmvn</groupId>
  <artifactId>spring-probe</artifactId>
  <version>1</version>
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>$EXTRA_XML
    </plugins>
  </build>
</project>
POM

# ---------------------------------------------------------------------------------------------------
# 2) Derive the plugin set and build. build-nmvn-for-pom.sh uses the dist's own mvn for the
#    effective pom, so default-lifecycle plugin versions match what the baked binary will request.
# ---------------------------------------------------------------------------------------------------
if [ "$PLUGINS_ONLY" -eq 1 ]; then
  exec "$SCRIPT_DIR/build-nmvn-for-pom.sh" "$PROBE" --plugins-only
fi

# build-nmvn-prebuilt.sh writes the image into the caller's cwd as nmvn-native; run from
# SCRIPT_DIR so the rename below is unambiguous.
(cd "$SCRIPT_DIR" && ./build-nmvn-for-pom.sh "$PROBE")

mv "$SCRIPT_DIR/nmvn-native" "$SCRIPT_DIR/nmvn-spring-$BOOT_VERSION"
echo ">>> Done: $SCRIPT_DIR/nmvn-spring-$BOOT_VERSION"
