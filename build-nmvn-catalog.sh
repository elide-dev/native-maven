#!/bin/bash
#
# Builds every native image in a catalog (catalog/catalog.json), one per entry, so a client can pick
# the smallest binary that bakes everything its pom needs (catalog/nmvn_select.py does the picking).
#
# Why a catalog rather than one image
# -----------------------------------
# For language=java a SINGLE image is already sufficient: across all 205 start.spring.io dependencies
# there is no groupId:artifactId collision, so one PrebuiltPluginRealms registry can hold the union
# (that image is build-nmvn-for-initializr.sh). The catalog exists only to stop ~82% of projects from
# downloading the ~334MB of feature realms they never load. Correctness is identical either way; the
# axis being optimized is bytes.
#
# Each entry is built by build-nmvn-prebuilt.sh with an explicit plugin list, so nothing here depends
# on a probe pom or on effective-pom resolution -- the versions were pinned into catalog.json by
# plan_catalog.py from real start.spring.io output.
#
# Cost warning: these are full GraalVM native-image builds of ~1GB binaries. Expect tens of minutes
# and many GB of RAM EACH, serially. --dry-run first.
#
# Usage:
#   ./build-nmvn-catalog.sh [--catalog FILE] [--only NAME[,NAME...]] [--dry-run] [--keep-going]
#
#   --only        build just these catalog entries (by name, or by feature slug suffix)
#   --dry-run     print what would be built, with plugin lists, and exit
#   --keep-going  do not stop at the first failed entry; report a summary at the end
#
# Output: one binary per entry in this directory, named as in the catalog, plus a manifest
# <name>.plugins listing exactly what got baked (useful for verifying a deployed binary).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CATALOG="$SCRIPT_DIR/catalog/catalog.json"
ONLY=""
DRY_RUN=0
KEEP_GOING=0

while [ $# -gt 0 ]; do
  case "$1" in
    --catalog) CATALOG="${2:?--catalog needs a file}"; shift 2 ;;
    --only) ONLY="${2:?--only needs a name}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --keep-going) KEEP_GOING=1; shift ;;
    *) echo "Error: unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ ! -f "$CATALOG" ]; then
  echo "Error: no catalog at $CATALOG" >&2
  echo "       Generate one first:  ./catalog/plan_catalog.py --k 3 --emit catalog/catalog.json" >&2
  exit 1
fi

# Names first, so the loop can be driven without re-parsing JSON in bash. Newline-separated because
# no catalog name contains whitespace (plan_catalog.py slugifies feature labels).
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
  echo "Error: no catalog entries matched${ONLY:+ --only $ONLY}" >&2
  exit 1
fi

COUNT=$(printf '%s\n' "$NAMES" | wc -l | tr -d ' ')
echo ">>> Catalog: $CATALOG"
echo ">>> Building $COUNT image(s)$([ "$DRY_RUN" = 1 ] && echo ' (dry run)')"
echo

FAILED=()
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

  EST=$(python3 -c "
import json,sys
c=json.load(open('$CATALOG'))
print(next(b['estimatedMb'] for b in c['binaries'] if b['name']=='$NAME'))")

  echo "=== [$INDEX/$COUNT] $NAME  (~${EST}MB, ${#GAVS[@]} plugins)"
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
      FAILED+=("$NAME")
    else
      mv "$SCRIPT_DIR/nmvn-native" "$SCRIPT_DIR/$NAME"
      printf '%s\n' "${GAVS[@]}" > "$SCRIPT_DIR/$NAME.plugins"
      ACTUAL=$(du -m "$SCRIPT_DIR/$NAME" | cut -f1)
      echo ">>> $NAME: ${ACTUAL}MB actual (estimate was ${EST}MB)"
      BUILT+=("$NAME	${ACTUAL}MB	est ${EST}MB")
    fi
  else
    echo "!!! $NAME: build failed" >&2
    FAILED+=("$NAME")
    [ "$KEEP_GOING" = 1 ] || { echo "Stopping (use --keep-going to continue)." >&2; exit 1; }
  fi
  echo
done <<< "$NAMES"

[ "$DRY_RUN" = 1 ] && exit 0

echo "=== Summary"
if [ ${#BUILT[@]} -gt 0 ]; then
  printf '    ok      %s\n' "${BUILT[@]}"
  # The estimates come from imageMbPerClosureMb=2.0 in model.json, which was never measured. Two
  # actual sizes are enough to calibrate it; until then treat catalog sizes as ordinal, not absolute.
  echo
  echo "    If actual sizes drift from the estimates, recalibrate imageMbPerClosureMb in"
  echo "    catalog/model.json from (size_b - size_a) / (closureMb_b - closureMb_a) and replan."
fi
if [ ${#FAILED[@]} -gt 0 ]; then
  printf '    FAILED  %s\n' "${FAILED[@]}"
  exit 1
fi
