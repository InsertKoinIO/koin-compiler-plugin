#!/bin/bash
# Runs the four (koinVersion x directRegistration) combinations serially and collects JMH JSON.
# Usage: ./bench.sh <output-dir>   (run from perf-apps/startup-bench; uses the repo's gradlew)
set -u
OUT="${1:-results}"; mkdir -p "$OUT"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
for KV in 4.2.2 4.2.3-perfs; do
  for DR in false true; do
    NAME="koin-${KV}_direct-${DR}"
    echo "===== $NAME : $(date) ====="
    (cd "$ROOT" && ./gradlew -p perf-apps/startup-bench clean jmh -PkoinVersion="$KV" -PdirectRegistration="$DR" --console=plain) > "$OUT/$NAME.log" 2>&1
    echo "exit=$?"
    cp "$(dirname "$0")/build/results/jmh/results.json" "$OUT/$NAME.json" 2>/dev/null || echo "no results.json for $NAME"
    grep -E "^StartupBenchmark\.[a-zA-Z]+ +avgt" "$OUT/$NAME.log" | head -6
  done
done
echo "ALLDONE"
