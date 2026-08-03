#!/usr/bin/env bash
#
# Benchmark classic Maven vs native nmvn on every project under examples/.
#
# Default command:
#   clean package -DskipTests=true
#
# Usage:
#   ./benchmark-nmvn-examples.sh [options]
#
# Options:
#   --runs N            timed iterations per engine after warmup (default: 3)
#   --warmup N          untimed warmup runs per engine (default: 1)
#   --nmvn-binary NAME  NMVN_BINARY value, relative to the repo root
#                       (default: build/nmvn-spring-4.1.0 — where the build scripts write)
#   --mvn CMD           classic Maven command (default: mvn on PATH, or MVN env)
#   --goals "..."       Maven goals/args (default: clean package -DskipTests=true)
#   --only NAME[,...]   only these example directory names
#   --csv FILE          also write machine-readable results
#   --keep-going        continue after a failed project/engine
#   -h, --help          this help
#
# Requires: python3, a working classic mvn, and a built native binary + ./nmvn launcher.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$SCRIPT_DIR/examples"

RUNS=3
WARMUP=1
NMVN_BINARY_NAME="${NMVN_BINARY:-build/nmvn-spring-4.1.0}"
MVN_CMD="${MVN:-mvn}"
GOALS=(clean package -DskipTests=true)
ONLY=""
CSV=""
KEEP_GOING=0

usage() {
  sed -n '3,24p' "$0" | sed 's/^# \?//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --runs) RUNS="${2:?}"; shift 2 ;;
    --warmup) WARMUP="${2:?}"; shift 2 ;;
    --nmvn-binary) NMVN_BINARY_NAME="${2:?}"; shift 2 ;;
    --mvn) MVN_CMD="${2:?}"; shift 2 ;;
    --goals) # shellcheck disable=SC2206
      GOALS=($2); shift 2 ;;
    --only) ONLY="${2:?}"; shift 2 ;;
    --csv) CSV="${2:?}"; shift 2 ;;
    --keep-going) KEEP_GOING=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [ ! -d "$EXAMPLES_DIR" ]; then
  echo "Error: examples/ not found at $EXAMPLES_DIR" >&2
  exit 1
fi

if [ ! -x "$SCRIPT_DIR/nmvn" ]; then
  echo "Error: nmvn launcher not executable: $SCRIPT_DIR/nmvn" >&2
  exit 1
fi

if [ ! -x "$SCRIPT_DIR/$NMVN_BINARY_NAME" ]; then
  echo "Error: native binary not found/executable: $SCRIPT_DIR/$NMVN_BINARY_NAME" >&2
  echo "       Build one first, e.g.: ./build-nmvn-catalog.sh build/catalogs/nmvn-spring-4.1.0.json" >&2
  exit 1
fi

if ! command -v "$MVN_CMD" >/dev/null 2>&1 && [ ! -x "$MVN_CMD" ]; then
  echo "Error: classic Maven not found: $MVN_CMD" >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "Error: python3 is required for timing/table formatting" >&2
  exit 1
fi

# Discover projects (directories under examples/ that contain pom.xml).
PROJECTS=()
for d in "$EXAMPLES_DIR"/*/; do
  [ -f "${d}pom.xml" ] || continue
  name="$(basename "$d")"
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

echo ">>> Benchmark: classic Maven vs native nmvn"
echo ">>> Projects:  ${PROJECTS[*]}"
echo ">>> Goals:     ${GOALS[*]}"
echo ">>> Runs:      $RUNS timed (+ $WARMUP warmup) per engine"
echo ">>> Classic:   $MVN_CMD"
echo ">>> Native:    NMVN_BINARY=$NMVN_BINARY_NAME $SCRIPT_DIR/nmvn"
echo

# Results file: TSV rows for python summarizer
RESULTS="$(mktemp -t nmvn-bench.XXXXXX)"
trap 'rm -f "$RESULTS"' EXIT
# columns: project engine run_index seconds exit_code

run_one() {
  local project="$1" engine="$2" label="$3"
  local dir="$EXAMPLES_DIR/$project"
  local log exit_code
  log="$(mktemp -t nmvn-bench-log.XXXXXX)"

  # Always start from a clean reactor so "clean package" work is comparable.
  # (We still pass clean in GOALS; an extra clean here reduces cache noise between engines.)
  (
    cd "$dir"
    if [ "$engine" = "classic" ]; then
      "$MVN_CMD" -q -B clean -DskipTests=true >/dev/null 2>&1 || true
    else
      NMVN_BINARY="$NMVN_BINARY_NAME" "$SCRIPT_DIR/nmvn" -q -B clean -DskipTests=true >/dev/null 2>&1 || true
    fi
  )

  local start end elapsed
  start=$(python3 -c 'import time; print(time.perf_counter())')
  set +e
  if [ "$engine" = "classic" ]; then
    (cd "$dir" && "$MVN_CMD" -B "${GOALS[@]}") >"$log" 2>&1
    exit_code=$?
  else
    (cd "$dir" && NMVN_BINARY="$NMVN_BINARY_NAME" "$SCRIPT_DIR/nmvn" -B "${GOALS[@]}") >"$log" 2>&1
    exit_code=$?
  fi
  set -e
  end=$(python3 -c 'import time; print(time.perf_counter())')
  elapsed=$(python3 -c "print(f'{$end - $start:.3f}')")

  if [ "$exit_code" -ne 0 ]; then
    echo "    !!! $label FAILED (exit $exit_code) in ${elapsed}s — last 30 log lines:" >&2
    tail -30 "$log" >&2 || true
    rm -f "$log"
    return "$exit_code"
  fi
  rm -f "$log"
  echo "    $label  ${elapsed}s"
  # shellcheck disable=SC2034
  _LAST_ELAPSED="$elapsed"
  return 0
}

FAILED=0
for project in "${PROJECTS[@]}"; do
  echo "=== $project"

  for engine in classic nmvn; do
    if [ "$engine" = "classic" ]; then
      label_prefix="classic mvn"
    else
      label_prefix="nmvn/$NMVN_BINARY_NAME"
    fi

    if [ "$WARMUP" -gt 0 ]; then
      for w in $(seq 1 "$WARMUP"); do
        echo "  warmup $w/$WARMUP ($label_prefix)..."
        if ! run_one "$project" "$engine" "warmup $label_prefix"; then
          FAILED=1
          if [ "$KEEP_GOING" -eq 0 ]; then
            echo "Stopping (use --keep-going to continue)." >&2
            exit 1
          fi
          # still record failure times as empty skip further timed runs for this engine
          continue 2
        fi
      done
    fi

    for r in $(seq 1 "$RUNS"); do
      echo "  run $r/$RUNS ($label_prefix)..."
      if run_one "$project" "$engine" "run $r $label_prefix"; then
        echo -e "${project}\t${engine}\t${r}\t${_LAST_ELAPSED}\t0" >>"$RESULTS"
      else
        echo -e "${project}\t${engine}\t${r}\t\t1" >>"$RESULTS"
        FAILED=1
        if [ "$KEEP_GOING" -eq 0 ]; then
          echo "Stopping (use --keep-going to continue)." >&2
          exit 1
        fi
        break
      fi
    done
  done
  echo
done

python3 - "$RESULTS" "$CSV" <<'PY'
import sys
from collections import defaultdict
from pathlib import Path

path = sys.argv[1]
csv_path = sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] else None

rows = []
with open(path) as fh:
    for line in fh:
        line = line.strip()
        if not line:
            continue
        project, engine, run, seconds, exit_code = line.split("\t")
        rows.append({
            "project": project,
            "engine": engine,
            "run": int(run),
            "seconds": float(seconds) if seconds else None,
            "exit_code": int(exit_code),
        })

if not rows:
    print("No successful/failed timed runs recorded.")
    sys.exit(0)

# Aggregate successful runs only
by = defaultdict(list)
for r in rows:
    if r["exit_code"] == 0 and r["seconds"] is not None:
        by[(r["project"], r["engine"])].append(r["seconds"])

projects = sorted({r["project"] for r in rows})
engines = ["classic", "nmvn"]

def stats(xs):
    if not xs:
        return None
    xs = sorted(xs)
    n = len(xs)
    mean = sum(xs) / n
    med = xs[n // 2] if n % 2 else 0.5 * (xs[n // 2 - 1] + xs[n // 2])
    return {"n": n, "min": xs[0], "med": med, "mean": mean, "max": xs[-1]}

# Table
headers = [
    "project",
    "classic min",
    "classic med",
    "classic mean",
    "nmvn min",
    "nmvn med",
    "nmvn mean",
    "speedup (med)",
    "delta s (med)",
]
col_data = []
for project in projects:
    sc = stats(by.get((project, "classic"), []))
    sn = stats(by.get((project, "nmvn"), []))
    if sc and sn and sn["med"] > 0:
        speedup = sc["med"] / sn["med"]
        delta = sc["med"] - sn["med"]
        speedup_s = f"{speedup:.2f}x"
        delta_s = f"{delta:+.3f}"
    else:
        speedup_s = "n/a"
        delta_s = "n/a"

    def fmt(st, key):
        return f"{st[key]:.3f}" if st else "FAIL"

    col_data.append([
        project,
        fmt(sc, "min"), fmt(sc, "med"), fmt(sc, "mean"),
        fmt(sn, "min"), fmt(sn, "med"), fmt(sn, "mean"),
        speedup_s,
        delta_s,
    ])

widths = [len(h) for h in headers]
for row in col_data:
    for i, cell in enumerate(row):
        widths[i] = max(widths[i], len(cell))

def fmt_row(cells):
    return "  ".join(str(c).ljust(widths[i]) for i, c in enumerate(cells))

print("=== Results (seconds; lower is better) ===")
print(fmt_row(headers))
print(fmt_row(["-" * w for w in widths]))
for row in col_data:
    print(fmt_row(row))

# Totals over projects with both engines
classic_med = []
nmvn_med = []
for project in projects:
    sc = stats(by.get((project, "classic"), []))
    sn = stats(by.get((project, "nmvn"), []))
    if sc and sn:
        classic_med.append(sc["med"])
        nmvn_med.append(sn["med"])

if classic_med and nmvn_med:
    csum, nsum = sum(classic_med), sum(nmvn_med)
    print()
    print(f"Sum of per-project medians:  classic={csum:.3f}s  nmvn={nsum:.3f}s  "
          f"speedup={csum/nsum:.2f}x  saved={csum-nsum:+.3f}s")

# Failures
fails = [r for r in rows if r["exit_code"] != 0]
if fails:
    print()
    print("Failures:")
    for r in fails:
        print(f"  {r['project']} / {r['engine']} run {r['run']}")

if csv_path:
    import csv
    with open(csv_path, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=["project", "engine", "run", "seconds", "exit_code"])
        w.writeheader()
        for r in rows:
            w.writerow(r)
    print()
    print(f"CSV written: {csv_path}")
PY

if [ "$FAILED" -ne 0 ]; then
  exit 1
fi
