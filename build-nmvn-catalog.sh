#!/bin/bash
#
# Build specialized nmvn native image(s) from a catalog produced by resolve_boot_catalog.py.
#
#   ./catalog/resolve_boot_catalog.py --boot-version 4.1.0 --language java \
#       --emit catalog/nmvn-spring-4.1.0.json
#   ./build-nmvn-catalog.sh catalog/nmvn-spring-4.1.0.json
#   # → nmvn-spring-4.1.0  (+ nmvn-spring-4.1.0.plugins)
#
# Delegates each catalog entry to build-nmvn-prebuilt.sh with that entry's plugin GAV list.
#
# Cost: full GraalVM native-image of ~1GB; tens of minutes and many GB RAM. Prefer --dry-run first.
#
# Usage:
#   ./build-nmvn-catalog.sh <catalog.json> [--only NAME[,NAME...]] [--dry-run]
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

usage() {
  cat <<'EOF' >&2
Usage:
  ./build-nmvn-catalog.sh <catalog.json> [--only NAME[,NAME...]] [--dry-run]

  <catalog.json>   Required. Path to a catalog for one Spring Boot version
                   (from resolve_boot_catalog.py). Keep one file per Boot line, e.g.:
                     catalog/nmvn-spring-4.1.0.json
                     catalog/nmvn-spring-3.5.0.json

  --only NAME      Build only this binary name from the catalog
  --dry-run        Print plugins and exit (no native-image)

Create a catalog first, then build:

  ./catalog/resolve_boot_catalog.py \
    --boot-version 4.1.0 \
    --language java \
    --emit catalog/nmvn-spring-4.1.0.json

  ./build-nmvn-catalog.sh catalog/nmvn-spring-4.1.0.json --dry-run
  ./build-nmvn-catalog.sh catalog/nmvn-spring-4.1.0.json
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
  echo "    --language java \\" >&2
  echo "    --emit catalog/nmvn-spring-4.1.0.json" >&2
  echo >&2
  echo "  ./build-nmvn-catalog.sh catalog/nmvn-spring-4.1.0.json" >&2
  exit 1
fi

# Binary names from the catalog (one today: nmvn-spring-<bootVersion>).
NAMES=$(python3 - "$CATALOG" "$ONLY" <<'PY'
import json, sys
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
  INDEX=$((INDEX + 1))

  # One plugin spec per line. Specs may carry a |canonical-deps suffix (asciidoctor does), which must
  # survive as a single argv entry -- hence read -r into an array rather than word splitting.
  PLUGINS=$(python3 - "$CATALOG" "$NAME" <<'PY'
import json, sys
catalog = json.load(open(sys.argv[1]))
entry = next(b for b in catalog['binaries'] if b['name'] == sys.argv[2])
for p in entry['plugins']:
    print(p)
PY
)
  GAVS=()
  while IFS= read -r line; do
    [ -n "$line" ] && GAVS+=("$line")
  done <<< "$PLUGINS"

  echo "=== [$INDEX/$COUNT] $NAME  (${#GAVS[@]} plugins)"
  printf '      %s\n' "${GAVS[@]}"

  if [ "$DRY_RUN" = 1 ]; then
    echo
    continue
  fi

  # build-nmvn-prebuilt.sh always writes ./nmvn-native in its cwd; run from SCRIPT_DIR and rename so
  # concurrent-looking output cannot be confused between entries. A stale nmvn-native from an earlier
  # aborted run would otherwise be renamed as if it were this entry's build.
  rm -f "$SCRIPT_DIR/nmvn-native"

  if (cd "$SCRIPT_DIR" && ./build-nmvn-prebuilt.sh "${GAVS[@]}"); then
    if [ ! -f "$SCRIPT_DIR/nmvn-native" ]; then
      echo "!!! $NAME: build reported success but produced no nmvn-native" >&2
      exit 1
    else
      mv "$SCRIPT_DIR/nmvn-native" "$SCRIPT_DIR/$NAME"
      printf '%s\n' "${GAVS[@]}" > "$SCRIPT_DIR/$NAME.plugins"
      ACTUAL=$(du -m "$SCRIPT_DIR/$NAME" | cut -f1)
      echo ">>> $NAME: ${ACTUAL}MB"
      BUILT+=("$NAME	${ACTUAL}MB")
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
