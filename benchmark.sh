#!/bin/bash
#
# Startup benchmark for the Koin compiler plugin (KTZ-4829) — same role as ./benchmark.sh in the
# koin repo (core/benchmark): install the plugin to Maven Local, generate the Metro-shaped workload,
# run the koinVersion x directRegistration matrix with JMH, append the summary to
# perf-apps/startup-bench/bench_results.txt.
#
# Usage:
#   ./benchmark.sh                 # DEFAULT, like koin's core/benchmark: 10% scale (50 modules,
#                                  #   ~275 definitions), quick JMH, KCP 1.2.1 vs 1.3.0 on
#                                  #   koin 4.2.3-Beta1                                    ~2 min
#   ./benchmark.sh --scale 1.0     # full 2,756-definition workload, 1 fork, 5+5 x 5s      ~8 min
#   ./benchmark.sh --full          # Metro fingerprint (500 modules, 2,756 definitions) and
#                                  #   Metro-equivalent JMH (2 forks, 4+10 x 1s, -prof gc) ~1 h
#   ./benchmark.sh --koin 4.2.2,4.2.3-Beta1   # add the koin-core axis (default: 4.2.3-Beta1)
#   ./benchmark.sh --control       # also run 1.3.0 with directRegistration=false
#   ./benchmark.sh --kcp 1.2.1,1.3.0  # compiler plugin axis (default); 1.2.x runs once, 1.3+ direct off/on
#   ./benchmark.sh --no-install    # skip ./install.sh (plugin already in Maven Local)
#
set -u
cd "$(dirname "$0")" || exit 1
ARGS=(); INSTALL=true; FULL=false; SCALE=""
prev=""
for a in "$@"; do
  case "$a" in
    --no-install) INSTALL=false ;;
    --full) FULL=true ;;
    --scale) ;;
    *) if [ "$prev" = "--scale" ]; then SCALE="$a"; else ARGS+=("$a"); fi ;;
  esac
  prev="$a"
done
BENCH=perf-apps/startup-bench
RESULTS="$BENCH/results"
if $FULL; then PROFILE="full"; SCALE="${SCALE:-1.0}"; else PROFILE="quick"; SCALE="${SCALE:-0.1}"; ARGS+=("--quick"); fi
# quick iterations: 1 s is enough for the 10% workload; the full workload needs 5 s per iteration.
if ! $FULL && [ "$(python3 -c "print(1 if float('$SCALE') >= 0.5 else 0)")" = "1" ]; then ARGS+=("--iter" "5"); fi

if $INSTALL; then ./install.sh || exit 1; fi
python3 "$BENCH/generate.py" --scale "$SCALE" || exit 1
rm -rf "$RESULTS"
"$BENCH/bench.sh" "$RESULTS" "${ARGS[@]:-}" || exit 1

{
  echo
  echo "# $(grep '^pluginVersion' gradle.properties | cut -d= -f2) @ $(git rev-parse --short HEAD) - $(date +%Y-%m-%d) - profile=$PROFILE scale=$SCALE - $(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m)"
  python3 "$BENCH/summarize.py" "$RESULTS"
} | tee -a "$BENCH/bench_results.txt"
