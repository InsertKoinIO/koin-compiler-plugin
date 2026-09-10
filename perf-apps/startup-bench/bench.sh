#!/bin/bash
# Runs the four (koinVersion x directRegistration) combinations serially and collects JMH JSON.
# Usage: ./bench.sh <output-dir> [--quick] [--koin 4.2.2,4.2.3-Beta1] [--kcp 1.2.1,1.3.0]
#   --quick : 1 fork, 5+5 iterations, fixed heap, no profiler — for iterating.
#   default : Metro-equivalent profile with -prof gc — for publishing.
#   --kcp   : compiler plugin versions (from Maven Local / Plugin Portal). directRegistration is
#             only an axis for 1.3+; a 1.2.x entry runs once.
set -u
OUT="${1:-results}"; shift || true
QUICK=false; KOINS="4.2.2 4.2.3-Beta1"; KCPS="1.2.1 1.3.0"
while [ $# -gt 0 ]; do
  case "$1" in
    --quick) QUICK=true ;;
    --koin) shift; KOINS="${1//,/ }" ;;
    --kcp) shift; KCPS="${1//,/ }" ;;
  esac
  shift
done
mkdir -p "$OUT"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
run() {
  local KV="$1" KCP="$2" DR="$3" NAME="kcp-${KCP}_koin-${KV}_direct-${DR}"
  echo "===== $NAME (quick=$QUICK) : $(date) ====="
  rm -f "$(dirname "$0")/build/results/jmh/results.json"   # never let a failed run inherit the previous JSON
  (cd "$ROOT" && ./gradlew -p perf-apps/startup-bench jmh -PkoinVersion="$KV" -PkcpVersion="$KCP" -PdirectRegistration="$DR" -Pquick="$QUICK" --console=plain) > "$OUT/$NAME.log" 2>&1
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
      1.0.*|1.1.*|1.2.*) run "$KV" "$KCP" n/a ;;
      *) run "$KV" "$KCP" false; run "$KV" "$KCP" true ;;
    esac
  done
done
echo "ALLDONE"
