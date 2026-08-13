#!/usr/bin/env bash
#
# Run the PGO training workload against an INSTRUMENTED non-crema nmvn binary and collect
# one .iprof per (example project, goal set) into an output directory.
#
# The binary must have been built with NMVN_PGO=instrument (see non-crema/build-nmvn-prebuilt.sh):
# such a binary dumps default.iprof into its working directory on every exit, which this script
# moves aside after each run. A binary without instrumentation produces no default.iprof — that is
# detected and reported as an error on the first run.
#
# Training matrix: every example project under examples/spring/<version>/ whose
# .nmvn-unsupported-variants marker does not list 'non-crema' (or 'all'), each built with:
#   package   clean package -DskipTests=true   (the CI-standard path: compile, jar, boot repackage)
#   test      clean test                       (real surefire test execution, JVM forking)
#   install   clean install -DskipTests=true   (adds checksum/install wiring to the package path)
#
# Goals that are BROKEN on non-crema today are deliberately absent — training must only contain
# passing runs (a failed run still dumps a profile, but of a failure path):
#   site      MissingReflectionRegistrationError on velocity DeprecatedRuntimeConstants
#   deploy    MissingReflectionRegistrationError on maven Settings.isOffline
#   reactor   sisu asks to define a cloned mojo class at run time (predefined-classes hash miss)
# Extend GOAL_SETS below when those get fixed.
#
# Usage:
#   ./build-scripts/non-crema/train-pgo.sh --spring VERSION --nmvn-binary PATH [options]
#
# Options:
#   --spring VERSION    examples to train on: 4.0.7 or 4.1.0 (or short form 407/410);
#                       also settable via SPRING_VERSION (required, no default)
#   --nmvn-binary PATH  INSTRUMENTED binary, absolute or relative to the repo root;
#                       also settable via NMVN_BINARY (required, no default)
#   --out DIR           where the .iprof files land (default: build/work/pgo-profiles;
#                       wiped first so stale profiles never mix into a build)
#   --only NAME[,...]   only these example directory names
#   -h, --help          this help
#
# On success prints the NMVN_PGO value for the optimized build and writes it to
# <out>/NMVN_PGO (newline-free, ready for NMVN_PGO="$(cat build/work/pgo-profiles/NMVN_PGO)").
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SPRING_ROOT="$ROOT_DIR/examples/spring"

SPRING="${SPRING_VERSION:-}"
NMVN_BINARY_NAME="${NMVN_BINARY:-}"
OUT_DIR="$ROOT_DIR/build/work/pgo-profiles"
ONLY=""

usage() {
  sed -n '3,36p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --spring) SPRING="${2:?}"; shift 2 ;;
    --nmvn-binary) NMVN_BINARY_NAME="${2:?}"; shift 2 ;;
    --out) OUT_DIR="${2:?}"; shift 2 ;;
    --only) ONLY="${2:?}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$SPRING" in
  4.0.7|407) SPRING_DIR="$SPRING_ROOT/407" ;;
  4.1.0|410) SPRING_DIR="$SPRING_ROOT/410" ;;
  '') echo "Error: --spring VERSION is required (4.0.7/407 or 4.1.0/410)" >&2; exit 2 ;;
  *) echo "Error: unknown spring version '$SPRING' (4.0.7/407 or 4.1.0/410)" >&2; exit 2 ;;
esac
[ -d "$SPRING_DIR" ] || { echo "Error: no examples under $SPRING_DIR" >&2; exit 1; }

if [ -z "$NMVN_BINARY_NAME" ]; then
  echo "Error: --nmvn-binary PATH is required (an NMVN_PGO=instrument build)" >&2
  exit 2
fi
case "$NMVN_BINARY_NAME" in
  /*) NMVN_BIN="$NMVN_BINARY_NAME" ;;
  *) NMVN_BIN="$ROOT_DIR/$NMVN_BINARY_NAME" ;;
esac
[ -x "$NMVN_BIN" ] || { echo "Error: not an executable: $NMVN_BIN" >&2; exit 1; }

# Label:goals pairs; labels end up in the .iprof filenames. Goals are word-split on purpose.
GOAL_SETS=(
  "package:clean package -DskipTests=true"
  "test:clean test"
  "install:clean install -DskipTests=true"
)

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

PROFILES=()
SKIPPED=()
for dir in "$SPRING_DIR"/*/; do
  project="$(basename "$dir")"
  [ -f "$dir/pom.xml" ] || continue
  if [ -n "$ONLY" ]; then
    case ",$ONLY," in
      *",$project,"*) ;;
      *) continue ;;
    esac
  fi
  # Same marker convention as test-nmvn-examples.sh: skip when 'non-crema' or 'all' is listed.
  marker="$dir/.nmvn-unsupported-variants"
  if [ -f "$marker" ] && grep -v '^#' "$marker" | grep -qxE 'all|non-crema'; then
    SKIPPED+=("$project")
    continue
  fi

  for entry in "${GOAL_SETS[@]}"; do
    label="${entry%%:*}"
    goals="${entry#*:}"
    echo "=== $project: $goals"
    rm -f "$dir/default.iprof"
    log="$OUT_DIR/$project-$label.log"
    # shellcheck disable=SC2086 — goals are intentionally split
    if ! (cd "$dir" && "$NMVN_BIN" -B $goals) >"$log" 2>&1; then
      echo "    FAILED — last 50 log lines ($log):" >&2
      tail -50 "$log" >&2 || true
      exit 1
    fi
    if [ ! -f "$dir/default.iprof" ]; then
      echo "Error: run left no default.iprof in $dir." >&2
      echo "       Is $NMVN_BIN an instrumented binary (built with NMVN_PGO=instrument)?" >&2
      exit 1
    fi
    mv "$dir/default.iprof" "$OUT_DIR/$project-$label.iprof"
    echo "    ok → $OUT_DIR/$project-$label.iprof"
    PROFILES+=("$OUT_DIR/$project-$label.iprof")
  done
done

if [ "${#PROFILES[@]}" -eq 0 ]; then
  echo "Error: no training runs happened (all projects skipped or filtered?)" >&2
  exit 1
fi

NMVN_PGO_VALUE="$(IFS=,; printf '%s' "${PROFILES[*]}")"
printf '%s' "$NMVN_PGO_VALUE" > "$OUT_DIR/NMVN_PGO"

echo
echo "=== ${#PROFILES[@]} profiles collected${SKIPPED[@]+" (skipped: ${SKIPPED[*]})"}"
echo "Build the optimized image with:"
echo "  NMVN_PGO=\"\$(cat $OUT_DIR/NMVN_PGO)\" NMVN_VARIANT=non-crema \\"
echo "    ./build-scripts/build-nmvn-catalog.sh <catalog.json>"
