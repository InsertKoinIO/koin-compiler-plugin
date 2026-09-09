#!/bin/bash
#
# Startup benchmark for the Koin compiler plugin (KTZ-4829) — same role as ./benchmark.sh in the
# koin repo (core/benchmark): install the plugin to Maven Local, generate the Metro-shaped workload,
# run the koinVersion x directRegistration matrix with JMH, append the summary to
# perf-apps/startup-bench/bench_results.txt.
#
# Usage:
#   ./benchmark.sh                 # Metro-equivalent profile (2 forks, 4+10 x 1s, -prof gc) ~1 h
#   ./benchmark.sh --quick         # 1 fork, 5+5 x 1s, fixed heap, no profiler              ~15 min
#   ./benchmark.sh --koin 4.2.2    # restrict the koin-core axis (default: 4.2.2,4.2.3-perfs)
#   ./benchmark.sh --no-install    # skip ./install.sh (plugin already in Maven Local)
#
set -u
cd "$(dirname "$0")" || exit 1
ARGS=(); INSTALL=true
for a in "$@"; do
  case "$a" in
    --no-install) INSTALL=false ;;
    *) ARGS+=("$a") ;;
  esac
done
BENCH=perf-apps/startup-bench
RESULTS="$BENCH/results"
PROFILE="full"; for a in "${ARGS[@]:-}"; do [ "$a" = "--quick" ] && PROFILE="quick"; done

if $INSTALL; then ./install.sh || exit 1; fi
python3 "$BENCH/generate.py" || exit 1
rm -rf "$RESULTS"
"$BENCH/bench.sh" "$RESULTS" "${ARGS[@]:-}" || exit 1

{
  echo
  echo "# $(grep '^pluginVersion' gradle.properties | cut -d= -f2) @ $(git rev-parse --short HEAD) - $(date +%Y-%m-%d) - profile=$PROFILE - $(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m)"
  python3 "$BENCH/summarize.py" "$RESULTS"
} | tee -a "$BENCH/bench_results.txt"
