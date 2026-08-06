#!/usr/bin/env bash
#
# Smoke-test a built nmvn native binary: build every example project under
# examples/spring/<version>/ with it (an unsupported/ subdirectory is skipped)
# and fail if any project fails.
#
# This is the CI test behind .github/workflows/test-spring-nmvn.yml and a quick
# local check after an image build. No timing and no classic-Maven comparison —
# for that use benchmark-nmvn-examples.sh.
#
# Default goals:
#   clean package -DskipTests=true
#
# Usage:
#   ./test-nmvn-examples.sh --spring VERSION --nmvn-binary PATH [options]
#
# Options:
#   --spring VERSION    Spring Boot examples to test: 4.0.7 or 4.1.0
#                       (or short form 407/410); also settable via SPRING_VERSION
#                       (required, no default)
#   --nmvn-binary PATH  native binary to test, absolute or relative to the repo
#                       root; also settable via NMVN_BINARY (required, no default;
#                       the build scripts write to build/, e.g. build/nmvn-spring-4.1.0)
#   --goals "..."       Maven goals/args (default: clean package -DskipTests=true)
#   --only NAME[,...]   only these example directory names
#   --keep-going        run remaining projects after a failure (exit still non-zero)
#   -h, --help          this help
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SPRING_ROOT="$SCRIPT_DIR/examples/spring"

SPRING="${SPRING_VERSION:-}"
NMVN_BINARY_NAME="${NMVN_BINARY:-}"
GOALS=(clean package -DskipTests=true)
ONLY=""
KEEP_GOING=0

usage() {
  sed -n '3,28p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --spring) SPRING="${2:?}"; shift 2 ;;
    --nmvn-binary) NMVN_BINARY_NAME="${2:?}"; shift 2 ;;
    --goals) # shellcheck disable=SC2206
      GOALS=($2); shift 2 ;;
    --only) ONLY="${2:?}"; shift 2 ;;
    --keep-going) KEEP_GOING=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [ -z "$SPRING" ]; then
  echo "Error: no Spring version specified — set SPRING_VERSION or pass --spring VERSION" >&2
  echo "       Available: 4.0.7, 4.1.0 (folders under examples/spring/)" >&2
  exit 2
fi

# Normalize the version to its examples/spring/ folder name (4.1.0 -> 410).
case "$SPRING" in
  4.0.7|407) SPRING_DIR=407; SPRING_FULL=4.0.7 ;;
  4.1.0|410) SPRING_DIR=410; SPRING_FULL=4.1.0 ;;
  *)
    echo "Error: unknown Spring version: $SPRING (expected 4.0.7 or 4.1.0)" >&2
    exit 2
    ;;
esac

EXAMPLES_DIR="$SPRING_ROOT/$SPRING_DIR"
if [ ! -d "$EXAMPLES_DIR" ]; then
  echo "Error: examples not found at $EXAMPLES_DIR" >&2
  exit 1
fi

if [ -z "$NMVN_BINARY_NAME" ]; then
  echo "Error: no native binary specified — set NMVN_BINARY or pass --nmvn-binary PATH" >&2
  echo "       e.g.: NMVN_BINARY=build/nmvn-spring-$SPRING_FULL $0 --spring $SPRING_FULL" >&2
  exit 2
fi

# Resolve the native binary: absolute paths are used as-is, everything else is
# relative to the repo root.
case "$NMVN_BINARY_NAME" in
  /*) NMVN_BIN="$NMVN_BINARY_NAME" ;;
  *) NMVN_BIN="$SCRIPT_DIR/$NMVN_BINARY_NAME" ;;
esac
if [ ! -x "$NMVN_BIN" ]; then
  echo "Error: native binary not found/executable: $NMVN_BIN" >&2
  echo "       Build one first, e.g.: ./build-scripts/build-nmvn-catalog.sh build/catalogs/nmvn-spring-$SPRING_FULL.json" >&2
  exit 1
fi

# The binary's NmvnLauncher needs JAVA_HOME and resolves maven.home from MAVEN_HOME, else by
# walking up from the executable to apache-maven/target/apache-maven-4.1.0-SNAPSHOT. That
# walk-up knows only the -SNAPSHOT dir name — CI strips -SNAPSHOT from versions — so resolve
# and export MAVEN_HOME here from any packaged dist when the caller has not set one.
if [ -z "${JAVA_HOME:-}" ]; then
  echo "Error: JAVA_HOME is not set (the nmvn binary requires it)" >&2
  exit 1
fi
if [ -z "${MAVEN_HOME:-}" ]; then
  for d in "$SCRIPT_DIR"/apache-maven/target/apache-maven-*; do
    if [ -d "$d" ] && { [ -f "$d/bin/mvn" ] || [ -f "$d/bin/mvn.cmd" ]; }; then
      MAVEN_HOME="$d"
      export MAVEN_HOME
      break
    fi
  done
fi
if [ -n "${MAVEN_HOME:-}" ]; then
  echo ">>> MAVEN_HOME: $MAVEN_HOME"
fi

# Discover projects (directories under examples/spring/$SPRING_DIR/ that contain pom.xml).
# unsupported/ is skipped explicitly — don't rely on it never growing a pom.xml.
PROJECTS=()
for d in "$EXAMPLES_DIR"/*/; do
  [ -f "${d}pom.xml" ] || continue
  name="$(basename "$d")"
  [ "$name" = "unsupported" ] && continue
  if [ -n "$ONLY" ]; then
    case ",$ONLY," in
      *",$name,"*) ;;
      *) continue ;;
    esac
  fi
  PROJECTS+=("$name")
done

if [ ${#PROJECTS[@]} -eq 0 ]; then
  echo "Error: no example projects found under $EXAMPLES_DIR" >&2
  exit 1
fi

echo ">>> Smoke test: nmvn on examples/spring/$SPRING_DIR"
echo ">>> Binary:    $NMVN_BIN"
echo ">>> Projects:  ${PROJECTS[*]}"
echo ">>> Goals:     ${GOALS[*]}"
echo

PASSED=()
FAILED=()
for project in "${PROJECTS[@]}"; do
  dir="$EXAMPLES_DIR/$project"
  log="$(mktemp -t nmvn-test-log.XXXXXX)"
  echo "=== $project"
  start=$SECONDS
  # Capture the build log and only show it on failure — CI logs stay readable
  # and a green run is one line per project.
  if (cd "$dir" && "$NMVN_BIN" -B "${GOALS[@]}") >"$log" 2>&1; then
    echo "    ok (${GOALS[*]}) in $((SECONDS - start))s"
    PASSED+=("$project")
  else
    exit_code=$?
    echo "    FAILED (exit $exit_code) after $((SECONDS - start))s — last 100 log lines:" >&2
    tail -100 "$log" >&2 || true
    FAILED+=("$project")
    if [ "$KEEP_GOING" -eq 0 ]; then
      rm -f "$log"
      echo "Stopping (use --keep-going to continue)." >&2
      exit 1
    fi
  fi
  rm -f "$log"
done

echo
echo "=== Summary"
# ${arr[@]+...} guards the expansions: bash 3.2 (macOS /bin/bash) treats "${arr[@]}" on an empty
# array as unbound under 'set -u', and a bare '[ ... ] && printf' would trip 'set -e' when false.
if [ ${#PASSED[@]} -gt 0 ]; then
  printf '    ok      %s\n' ${PASSED[@]+"${PASSED[@]}"}
fi
if [ ${#FAILED[@]} -gt 0 ]; then
  printf '    FAILED  %s\n' ${FAILED[@]+"${FAILED[@]}"}
fi
echo "    ${#PASSED[@]}/${#PROJECTS[@]} passed"

[ ${#FAILED[@]} -eq 0 ]
