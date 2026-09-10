#!/bin/bash
# Runs the four (koinVersion x directRegistration) combinations serially and collects JMH JSON.
# Usage: ./bench.sh <output-dir> [--quick] [--koin 4.2.3-Beta1] [--kcp 1.2.1,1.3.0] [--control]
#   --quick   : 1 fork, 5+5 x 1s iterations, fixed heap, no profiler — for iterating.
#   default   : Metro-equivalent profile with -prof gc — for publishing.
#   --koin    : koin-core versions (default: 4.2.3-Beta1 only; add 4.2.2 to see the runtime axis).
#   --kcp     : compiler plugin versions (default 1.2.1,1.3.0). A 1.2.x entry runs once; 1.3+ runs
#               with directRegistration=true, plus =false when --control is given.
set -u
OUT="${1:-results}"; shift || true
QUICK=false; CONTROL=false; KOINS="4.2.3-Beta1"; KCPS="1.2.1 1.3.0 1.3.0-dsl"; ITER=1
while [ $# -gt 0 ]; do
  case "$1" in
    --quick) QUICK=true ;;
    --control) CONTROL=true ;;
    --iter) shift; ITER="$1" ;;
    --koin) shift; KOINS="${1//,/ }" ;;
    --kcp) shift; KCPS="${1//,/ }" ;;
  esac
  shift
done
mkdir -p "$OUT"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
run() {
  local KV="$1"; local KCP="$2"; local DR="$3"
  local NAME="kcp-${KCP}_koin-${KV}_direct-${DR}"
  echo "===== $NAME (quick=$QUICK) : $(date) ====="
  rm -f "$(dirname "$0")/build/results/jmh/results.json"   # never let a failed run inherit the previous JSON
  (cd "$ROOT" && ./gradlew -p perf-apps/startup-bench jmh -PkoinVersion="$KV" -PkcpVersion="$KCP" -PdirectRegistration="$DR" -Pquick="$QUICK" -PiterationSeconds="$ITER" --console=plain) > "$OUT/$NAME.log" 2>&1
  echo "gradle exit=$?"
  if [ -f "$(dirname "$0")/build/results/jmh/results.json" ]; then
    cp "$(dirname "$0")/build/results/jmh/results.json" "$OUT/$NAME.json"
  else
    echo "NO RESULTS for $NAME — see $OUT/$NAME.log"; grep -m1 -A3 "What went wrong" "$OUT/$NAME.log" | cut -c1-160
  fi
  grep -E "^StartupBenchmark\.[a-zA-Z]+ +avgt" "$OUT/$NAME.log" | head -6
}
for KV in $KOINS; do
  for KCP in $KCPS; do
    case "$KCP" in
      1.0.*|1.1.*|1.2.*) run "$KV" "$KCP" na ;;
      *) $CONTROL && run "$KV" "$KCP" false; run "$KV" "$KCP" true ;;
    esac
  done
done
echo "ALLDONE"
