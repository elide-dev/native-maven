#!/usr/bin/env bash
#
# E2E-test the JVM fallback of a built nmvn binary: build a REAL project
# (examples/jvm-fallback-project) whose pom binds non-baked plugins into the
# lifecycle, exactly the way users hit the fallback. On the non-crema variant
# those goals must delegate to the in-process HotSpot JVM; on crema they load
# at run time. Tests, in order:
#   1. symbol tripwire (Linux): the image must not dynamically export the
#      statically-linked JDK's JNU_* symbols — the fallback child's libjava.so
#      would bind to them and die booting (see linux-hide-static-jdk-symbols
#      in native/launcher/pom.xml)
#   2. `clean package` succeeds; the jar AND the antrun marker file exist (the
#      delegated goal really executed, not just reported success); non-crema:
#      both plugins delegated, with the pom's execution ids (goal@id)
#   3. `clean package -Pimpossible-rule` fails; non-crema: the failure came
#      through the fallback's exit-code plumbing (HotspotGoalFailedException)
#
# This is the CI test behind .github/workflows/test-spring-nmvn.yml (fallback
# step) and a quick local check after an image build.
#
# Usage:
#   ./test-nmvn-jvm-fallback.sh --nmvn-binary PATH [options]
#
# Options:
#   --nmvn-binary PATH  native binary to test, absolute or relative to the repo
#                       root; also settable via NMVN_BINARY (required, no
#                       default; CI builds write to build/, e.g.
#                       build/nmvn-spring-4.1.0)
#   --maven-home PATH   Maven dist the binary runs against (default:
#                       apache-maven/target/apache-maven-4.1.0-SNAPSHOT under
#                       the repo root)
#   --variant NAME      nmvn variant under test: crema or non-crema (default:
#                       non-crema) — crema serves non-baked plugins natively,
#                       so the delegation-marker assertions are skipped there
#   -h, --help          this help
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$SCRIPT_DIR/examples/jvm-fallback-project"

NMVN_BINARY_NAME="${NMVN_BINARY:-}"
MAVEN_HOME_ARG="$SCRIPT_DIR/apache-maven/target/apache-maven-4.1.0-SNAPSHOT"
VARIANT="non-crema"

usage() {
  sed -n '3,36p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --nmvn-binary) NMVN_BINARY_NAME="${2:?}"; shift 2 ;;
    --maven-home) MAVEN_HOME_ARG="${2:?}"; shift 2 ;;
    --variant) VARIANT="${2:?}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [ -z "$NMVN_BINARY_NAME" ]; then
  echo "error: --nmvn-binary is required" >&2
  usage >&2
  exit 2
fi
case "$NMVN_BINARY_NAME" in
  /*) BINARY="$NMVN_BINARY_NAME" ;;
  *) BINARY="$SCRIPT_DIR/$NMVN_BINARY_NAME" ;;
esac
[ -f "$BINARY" ] || { echo "error: binary not found: $BINARY" >&2; exit 2; }

# The binary needs MAVEN_HOME at run time (m2.conf, boot jars); Windows java wants C:/.
if command -v cygpath >/dev/null 2>&1; then
  MAVEN_HOME_ARG="$(cygpath -m "$MAVEN_HOME_ARG")"
fi
export MAVEN_HOME="$MAVEN_HOME_ARG"

FAILURES=0
fail() {
  echo "FAIL: $1" >&2
  FAILURES=$((FAILURES + 1))
}

# Delegation marker printed by nmvn.HotspotMavenRunner for every delegated goal, e.g.:
#   nmvn: running org.apache.maven.plugins:maven-enforcer-plugin:3.6.3:enforce
#   {execution: require-maven} on HotSpot JVM: mvn ... maven-enforcer-plugin:3.6.3:enforce@require-maven
# The second grep pins goalSpec's goal@executionId handling (the delegated child selects the
# pom's <execution> block by that id).
expect_delegated() { # <output> <plugin:version:goal> <executionId>
  if ! echo "$1" | grep -q "running org.apache.maven.plugins:$2 {execution: $3} on HotSpot JVM"; then
    echo "$1"
    fail "$2@$3 did not delegate to the HotSpot JVM (marker line missing)"
  elif ! echo "$1" | grep -q "$2@$3"; then
    echo "$1"
    fail "delegated command lost the execution id — $2@$3 missing from the goalSpec"
  fi
}

cd "$PROJECT"

# ---- 1. Symbol tripwire (Linux) --------------------------------------------------------
# Guard for the linux-hide-static-jdk-symbols profile (native/launcher/pom.xml): if the
# image dynamically exports the statically-linked JDK's JNU_* symbols again, the fallback
# child's libjava.so binds to them and dies during boot — report the real reason here
# instead of the downstream boot NPE.
if [ "$(uname -s)" = "Linux" ] && nm -D "$BINARY" 2>/dev/null | grep -q " JNU_"; then
  nm -D "$BINARY" | grep " JNU_"
  fail "image exports JNU_* symbols — exclude-libs,ALL stopped working, the fallback child JVM will crash"
fi

# ---- 2. Real build with non-baked plugins in the lifecycle ------------------------------
echo ">>> e2e: clean package (enforcer + antrun are non-baked and must run)"
if ! OUT="$("$BINARY" clean package 2>&1)"; then
  echo "$OUT"
  fail "clean package failed — the fallback (or crema runtime loading) is broken"
else
  # The build's word is not enough: the jar and the antrun MARKER FILE prove the delegated
  # goals actually executed. (CI strips -SNAPSHOT from poms, hence the glob.)
  ls target/jvm-fallback-sample-*.jar >/dev/null 2>&1 \
    || fail "clean package succeeded but produced no jar"
  [ -f target/fallback-marker.txt ] \
    || fail "antrun's marker file is missing — the delegated goal reported success without executing"
  if [ "$VARIANT" = "non-crema" ]; then
    expect_delegated "$OUT" "maven-enforcer-plugin:3.6.3:enforce" "require-maven"
    expect_delegated "$OUT" "maven-antrun-plugin:3.1.0:run" "write-marker"
  fi
  [ "$FAILURES" -eq 0 ] && echo ">>> OK"
fi

# ---- 3. Failure propagation ------------------------------------------------------------
# A FAILING delegated goal must fail the nmvn build. This is the exit-code plumbing (temp
# file, readExitCode, HotspotGoalFailedException) — if it regresses, nmvn reports BUILD
# SUCCESS for builds whose delegated goals failed, and no green CI would notice.
echo ">>> e2e: clean package -Pimpossible-rule (enforcer must fail the build)"
if OUT="$("$BINARY" clean package -Pimpossible-rule 2>&1)"; then
  echo "$OUT"
  fail "the impossible enforcer rule did not fail the build — exit-code propagation is broken"
elif [ "$VARIANT" = "non-crema" ] && ! echo "$OUT" | grep -q "failed on the HotSpot JVM (exit code"; then
  echo "$OUT"
  fail "build failed, but not via the fallback exit-code plumbing (HotspotGoalFailedException marker missing)"
else
  echo ">>> OK"
fi

# ---- Summary ----------------------------------------------------------------------------
if [ "$FAILURES" -gt 0 ]; then
  echo "$FAILURES JVM-fallback test(s) FAILED" >&2
  exit 1
fi
echo ">>> all JVM-fallback tests passed"
