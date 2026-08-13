#!/bin/bash
#
# Build specialized nmvn native image(s) from a catalog produced by resolve_boot_catalog.py.
#
#   ./catalog/resolve_boot_catalog.py --boot-version 4.1.0 --language java
#   ./build-scripts/build-nmvn-catalog.sh build/catalogs/nmvn-spring-4.1.0.json
#   # → build/nmvn-spring-4.1.0
##
# Builds each catalog entry with the Maven native profile ("mvn -Pnative[,crema] package
# -pl native/launcher -am"), passing that entry's plugin GAV list via -Dnmvn.pluginsFile.
# The variant is chosen via NMVN_VARIANT (default: crema):
#   crema      — unbaked plugins still load dynamically at run time (needs a Crema-enabled GraalVM)
#   non-crema  — no runtime class loading; baked plugins run natively, everything else on the
#                in-process HotSpot JVM fallback (stock GraalVM suffices)
# The per-variant build-scripts/<variant>/build-nmvn-prebuilt.sh remain as the standalone
# reference implementation; this driver no longer calls them.
#
# Cost: full GraalVM native-image of ~1GB; tens of minutes and many GB RAM. Prefer --dry-run first.
#
# Usage:
#   ./build-scripts/build-nmvn-catalog.sh <catalog.json> [--only NAME[,NAME...]] [--dry-run]
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

NMVN_VARIANT="${NMVN_VARIANT:-crema}"
case "$NMVN_VARIANT" in
  crema) MVN_PROFILES="native,crema" ;;
  non-crema) MVN_PROFILES="native" ;;
  *)
    echo "Error: NMVN_VARIANT='$NMVN_VARIANT' (expected 'crema' or 'non-crema')" >&2
    exit 2
    ;;
esac

# The dist is still required (image classpath, exported artifacts, sanitize -cp). Resolved by
# glob because CI strips -SNAPSHOT from the poms while keeping the -SNAPSHOT dist dir name —
# the profile's ${project.version}-derived default would miss there, so it is passed explicitly.
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

# Same layout as build-nmvn-prebuilt.sh (local == CI):
#   build/       — final binary + .plugins
#   build/work/  — scratch
NMVN_OUT_DIR="${NMVN_OUT_DIR:-$ROOT_DIR/build}"
mkdir -p "$NMVN_OUT_DIR"

usage() {
  cat <<'EOF' >&2
Usage:
  ./build-scripts/build-nmvn-catalog.sh <catalog.json> [--only NAME[,NAME...]] [--dry-run]

  <catalog.json>   Required. Path to a catalog for one Spring Boot version
                   (from resolve_boot_catalog.py). One file per Boot line, e.g.:
                     build/catalogs/nmvn-spring-4.1.0.json

  --only NAME      Build only this binary name from the catalog
  --dry-run        Print plugins and exit (no native-image)

  Output (default): build/nmvn-spring-<bootVersion>
  Scratch:          build/work/
  Variant:          NMVN_VARIANT=crema (default) or non-crema

Create a catalog first, then build:

  ./catalog/resolve_boot_catalog.py --boot-version 4.1.0 --language java
  ./build-scripts/build-nmvn-catalog.sh build/catalogs/nmvn-spring-4.1.0.json --dry-run
  ./build-scripts/build-nmvn-catalog.sh build/catalogs/nmvn-spring-4.1.0.json
EOF
}

CATALOG=""
ONLY=""
DRY_RUN=0

while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --only)
      ONLY="${2:?--only needs a name}"
      shift 2
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --catalog)
      # Accept long form as well as the required positional arg.
      CATALOG="${2:?--catalog needs a file}"
      shift 2
      ;;
    -*)
      echo "Error: unknown option: $1" >&2
      echo >&2
      usage
      exit 2
      ;;
    *)
      if [ -n "$CATALOG" ]; then
        echo "Error: unexpected argument: $1 (catalog already set to $CATALOG)" >&2
        echo >&2
        usage
        exit 2
      fi
      CATALOG="$1"
      shift
      ;;
  esac
done

if [ -z "$CATALOG" ]; then
  echo "Error: catalog file is required." >&2
  echo >&2
  usage
  exit 2
fi

# Resolve relative paths from the caller's cwd (usual for a required path arg).
if [ ! -f "$CATALOG" ]; then
  echo "Error: catalog not found: $CATALOG" >&2
  echo >&2
  echo "Create one for the Spring Boot version you want, then pass that path:" >&2
  echo >&2
  echo "  ./catalog/resolve_boot_catalog.py \\" >&2
  echo "    --boot-version 4.1.0 \\" >&2
  echo "    --language java" >&2
  echo >&2
  echo "  ./build-scripts/build-nmvn-catalog.sh build/catalogs/nmvn-spring-4.1.0.json" >&2
  exit 1
fi

# Binary names from the catalog (one today: nmvn-spring-<bootVersion>).
NAMES=$(python3 - "$CATALOG" "$ONLY" <<'PY'
import json, sys
# Windows python translates \n -> \r\n on stdout; `$(...)` strips the trailing \n but NOT the \r, so
# every value read back here would carry a CR. Downstream that CR ends up left of '=' in the realm
# spec, where the readers treat it as a line terminator and the entry looks truncated. Emit LF only.
sys.stdout.reconfigure(newline="\n")
catalog = json.load(open(sys.argv[1]))
only = [s for s in sys.argv[2].split(',') if s]
for b in catalog['binaries']:
    if only and not any(s == b['name'] or b['name'].endswith('-' + s) for s in only):
        continue
    print(b['name'])
PY
)

if [ -z "$NAMES" ]; then
  echo "Error: no catalog entries matched${ONLY:+ --only $ONLY} in $CATALOG" >&2
  exit 1
fi

COUNT=$(printf '%s\n' "$NAMES" | wc -l | tr -d ' ')
echo ">>> Catalog: $CATALOG"
echo ">>> Building $COUNT image(s)$([ "$DRY_RUN" = 1 ] && echo ' (dry run)')"
echo

BUILT=()
INDEX=0

while IFS= read -r NAME; do
  NAME="${NAME%$'\r'}"
  INDEX=$((INDEX + 1))

  # One plugin spec per line. Specs may carry a |canonical-deps suffix (asciidoctor does), which must
  # survive as a single argv entry -- hence read -r into an array rather than word splitting.
  PLUGINS=$(python3 - "$CATALOG" "$NAME" <<'PY'
import json, sys
sys.stdout.reconfigure(newline="\n")  # LF only — see the NAMES probe above
catalog = json.load(open(sys.argv[1]))
name = sys.argv[2].strip()
try:
    entry = next(b for b in catalog['binaries'] if b['name'] == name)
except StopIteration:
    raise SystemExit(f"catalog has no binary named {sys.argv[2]!r}")
for p in entry['plugins']:
    print(p)
PY
)
  # Belt and braces: a CR reaching a GAV lands left of '=' in the realm spec, where every reader
  # treats it as a line terminator and the entry then looks truncated to a bare g:a:v.
  GAVS=()
  while IFS= read -r line; do
    line="${line%$'\r'}"
    [ -n "$line" ] && GAVS+=("$line")
  done <<< "$PLUGINS"

  echo "=== [$INDEX/$COUNT] $NAME  (${#GAVS[@]} plugins)"
  printf '      %s\n' "${GAVS[@]}"

  if [ "$DRY_RUN" = 1 ]; then
    echo
    continue
  fi

  # One plugin spec per line, consumed via -Dnmvn.pluginsFile (a -D value could not carry the
  # |canonical-deps entries safely). Written up front; also serves as the build record.
  printf '%s\n' "${GAVS[@]}" > "$NMVN_OUT_DIR/$NAME.plugins"

  # The profile writes native/launcher/target/$NAME(.exe); wipe stale copies of both locations
  # so a failed previous run cannot be mistaken for this entry.
  TARGET_DIR="$ROOT_DIR/native/launcher/target"
  rm -f "$TARGET_DIR/$NAME" "$TARGET_DIR/$NAME.exe" "$NMVN_OUT_DIR/$NAME" "$NMVN_OUT_DIR/$NAME.exe"

  if (
    cd "$ROOT_DIR"
    ./mvnw -P"$MVN_PROFILES" package -pl native/launcher -am -B -DskipTests \
      -Dnmvn.pluginsFile="$NMVN_OUT_DIR/$NAME.plugins" \
      -Dnmvn.imageName="$NAME" \
      -Dnmvn.mavenHome="$NMVN_MAVEN_HOME"
  ); then
    OUT_SRC=""
    OUT_DST="$NMVN_OUT_DIR/$NAME"
    if [ -f "$TARGET_DIR/$NAME" ]; then
      OUT_SRC="$TARGET_DIR/$NAME"
    elif [ -f "$TARGET_DIR/$NAME.exe" ]; then
      OUT_SRC="$TARGET_DIR/$NAME.exe"
      OUT_DST="$NMVN_OUT_DIR/$NAME.exe"
    fi
    if [ -z "$OUT_SRC" ]; then
      echo "!!! $NAME: build reported success but produced no $NAME(.exe) under $TARGET_DIR" >&2
      exit 1
    else
      mv "$OUT_SRC" "$OUT_DST"
      ACTUAL=$(du -m "$OUT_DST" | cut -f1)
      echo ">>> $OUT_DST: ${ACTUAL}MB"
      BUILT+=("$OUT_DST	${ACTUAL}MB")
    fi
  else
    echo "!!! $NAME: build failed" >&2
    exit 1
  fi
  echo
done <<< "$NAMES"

[ "$DRY_RUN" = 1 ] && exit 0

echo "=== Summary"
if [ ${#BUILT[@]} -gt 0 ]; then
  printf '    ok      %s\n' "${BUILT[@]}"
fi
