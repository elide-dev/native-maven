#!/bin/bash
#
# Builds ONE nmvn native image covering EVERY project start.spring.io can generate for a given
# (language=java, bootVersion) pair — all 205 dependencies, both packagings, all java versions.
#
# Why one image is enough
# -----------------------
# Of the 205 dependencies start.spring.io offers, 192 do not touch <build><plugins> at all; the
# generated pom differs only in <dependencies>, which never affects the plugin realm set. The 13
# that do add plugins each add a DISTINCT groupId:artifactId, and PrebuiltPluginRealms.BY_KEY is
# keyed by g:a — so the union has no key collision and fits in a single registry. (The one
# collision in the whole initializr space is kotlin-maven-plugin, allopen vs allopen+noarg, which
# language=java excludes by construction. That is what makes THIS slice a one-image slice.)
#
# So there is no image-selection algorithm to run for java: given the Boot version, there is
# exactly one answer. Selection only reappears once Kotlin is in scope.
#
# Tiers
# -----
# Baking is not all-or-nothing: PrebuiltPluginRealmCache.createKey falls through to dynamic
# resolution per plugin, so an unbaked plugin costs its resolution time and nothing else. That
# makes the rare/heavy plugins a pure size-vs-time knob:
#
#   core   10 plugins  — lifecycle + spring-boot + war + compiler. Covers all 192 plugin-neutral
#                        dependencies, plus lombok and configuration-processor (they only add
#                        maven-compiler-plugin, which is already baked, and annotationProcessorPaths
#                        are NOT plugin <dependencies> — the realm key is unchanged, so they are free).
#   cheap  +4 plugins  — build-helper, protobuf, cyclonedx, graphqlcodegen (~31 MB of jars).
#   full   +3 plugins  — hibernate, asciidoctor, vaadin (~136 MB of jars): rare triggers, heavy realms.
#
# Never bakeable (both carry <extensions>true</extensions>, which build-nmvn-for-pom.sh skips
# because an extensions plugin gets a structurally different realm):
#   org.graalvm.buildtools:native-maven-plugin
#   org.springframework.cloud:spring-cloud-contract-maven-plugin
#
# Usage:
#   ./build-nmvn-for-initializr.sh [--tier core|cheap|full] [--plugins-only]
#
# Output: nmvn-initializr-java-<boot-version> in this directory.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

BOOT_VERSION="4.1.0"
TIER="full"
PLUGINS_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --tier) TIER="${2:?--tier needs a value}"; shift 2 ;;
    --boot) BOOT_VERSION="${2:?--boot needs a value}"; shift 2 ;;
    --plugins-only) PLUGINS_ONLY=1; shift ;;
    *) echo "Error: unknown argument: $1"; exit 2 ;;
  esac
done
case "$TIER" in core|cheap|full) ;; *) echo "Error: --tier must be core|cheap|full"; exit 2 ;; esac

if [ "$BOOT_VERSION" != "4.1.0" ]; then
  echo "Error: the pinned entry list below was derived for Boot 4.1.0 only."
  echo "       Re-derive for $BOOT_VERSION before trusting it: several versions differ per Boot line"
  echo "       (4.0.7 pins compiler 3.14.1, jar 3.4.2, resources 3.3.1), and a baked version that"
  echo "       Maven never requests is dead weight that silently falls back to dynamic resolution."
  exit 1
fi

# ---------------------------------------------------------------------------------------------------
# The entry list. Every version here was resolved by build-nmvn-for-pom.sh from REAL start.spring.io
# output, not from starter-parent pluginManagement — the two disagree, and pluginManagement loses.
# Initializr pins asciidoctor to 2.2.1 while the parent manages 3.2.0; graphqlcodegen 1.61.5 and
# gmavenplus 1.13.1 exist only in initializr's templates. Baking the pluginManagement version would
# produce a realm that never matches at runtime.
# ---------------------------------------------------------------------------------------------------
CORE=(
  "org.springframework.boot:spring-boot-maven-plugin:4.1.0"
  "org.apache.maven.plugins:maven-clean-plugin:3.5.0"
  "org.apache.maven.plugins:maven-compiler-plugin:3.15.0"
  "org.apache.maven.plugins:maven-surefire-plugin:3.5.6"
  "org.apache.maven.plugins:maven-jar-plugin:3.5.0"
  "org.apache.maven.plugins:maven-war-plugin:3.5.1"
  "org.apache.maven.plugins:maven-install-plugin:3.1.4"
  "org.apache.maven.plugins:maven-resources-plugin:3.5.0"
  "org.apache.maven.plugins:maven-deploy-plugin:3.1.4"
  "org.apache.maven.plugins:maven-site-plugin:3.21.0"
)

# Triggered by: dgs-codegen (graphqlcodegen + build-helper), spring-grpc-client/server (protobuf),
# sbom-cyclone-dx (cyclonedx).
CHEAP=(
  "io.github.deweyjose:graphqlcodegen-maven-plugin:1.61.5"
  "org.codehaus.mojo:build-helper-maven-plugin:3.6.1"
  "io.github.ascopes:protobuf-maven-plugin:5.1.4"
  "org.cyclonedx:cyclonedx-maven-plugin:2.9.1"
)

# hibernate-maven-plugin appears ONLY for native + data-jpa (data-jpa alone does not add it).
# asciidoctor carries a per-plugin <dependencies> — the |... suffix is the canonical dependency key
# that PrebuiltPluginRealms.match() compares exactly; drop it and the realm never matches.
HEAVY=(
  "org.hibernate.orm:hibernate-maven-plugin:7.4.1.Final"
  "org.asciidoctor:asciidoctor-maven-plugin:2.2.1|org.springframework.restdocs:spring-restdocs-asciidoctor:4.0.1:jar::compile"
  "com.vaadin:vaadin-maven-plugin:25.2.4"
)

GAVS=("${CORE[@]}")
[ "$TIER" != "core" ] && GAVS+=("${CHEAP[@]}")
[ "$TIER" = "full" ] && GAVS+=("${HEAVY[@]}")

if [ "$PLUGINS_ONLY" -eq 1 ]; then
  printf '%s\n' "${GAVS[@]}"
  exit 0
fi

echo ">>> Baking ${#GAVS[@]} plugins (tier=$TIER) for java / Spring Boot $BOOT_VERSION:" >&2
printf '    %s\n' "${GAVS[@]}" >&2

(cd "$SCRIPT_DIR" && ./build-nmvn-prebuilt.sh "${GAVS[@]}")

OUT="$SCRIPT_DIR/nmvn-initializr-java-$BOOT_VERSION"
mv "$SCRIPT_DIR/nmvn-native" "$OUT"
echo ">>> Done: $OUT"