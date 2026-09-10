#!/bin/bash
# Runs the four (koinVersion x directRegistration) combinations serially and collects JMH JSON.
# Usage: ./bench.sh <output-dir> [--quick] [--koin 4.2.2,4.2.3-Beta1]
#   --quick : 1 fork, 2+3 iterations, no profiler (~2 min per configuration) — for iterating.
#   default : Metro-equivalent profile with -prof gc (~15 min per configuration) — for publishing.
set -u
OUT="${1:-results}"; shift || true
QUICK=false; KOINS="4.2.2 4.2.3-Beta1"
while [ $# -gt 0 ]; do
  case "$1" in
    --quick) QUICK=true ;;
    --koin) shift; KOINS="${1//,/ }" ;;
  esac
  shift
done
mkdir -p "$OUT"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
for KV in $KOINS; do
  for DR in false true; do
    NAME="koin-${KV}_direct-${DR}"
    echo "===== $NAME (quick=$QUICK) : $(date) ====="
    rm -f "$(dirname "$0")/build/results/jmh/results.json"   # never let a failed run inherit the previous JSON
    (cd "$ROOT" && ./gradlew -p perf-apps/startup-bench jmh -PkoinVersion="$KV" -PdirectRegistration="$DR" -Pquick="$QUICK" --console=plain) > "$OUT/$NAME.log" 2>&1
    echo "gradle exit=$?"
    if [ -f "$(dirname "$0")/build/results/jmh/results.json" ]; then
      cp "$(dirname "$0")/build/results/jmh/results.json" "$OUT/$NAME.json"
    else
      echo "NO RESULTS for $NAME — see $OUT/$NAME.log"; grep -m1 -A3 "What went wrong" "$OUT/$NAME.log" | cut -c1-160
    fi
    grep -E "^StartupBenchmark\.[a-zA-Z]+ +avgt" "$OUT/$NAME.log" | head -6
  done
done
echo "ALLDONE"
