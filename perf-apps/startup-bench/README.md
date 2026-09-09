# startup-bench — Koin startup with the compiler plugin, Metro-shaped (KTZ-4829)

A local equivalent of the Koin mode of Metro's startup benchmark: 500 generated modules,
2,756 `@Singleton` definitions, 874 constructor edges, 1,800 contributions bound to a multibinding
interface and realized through `getAll`, one root `@KoinApplication`. Compiled with the Koin
compiler plugin from `mavenLocal()`, measured with JMH (same shape as Metro's `startup-jvm`:
AverageTime, 2 forks, 4×1 s warm-up, 10×1 s measure) with the GC profiler on, so allocations per
operation are reported next to time.

```bash
# from the repo root — the one-liner, mirrors koin's core/benchmark ./benchmark.sh
./benchmark.sh                # default: 10% scale + quick JMH, ~4 min — the daily loop
./benchmark.sh --full         # Metro fingerprint + Metro JMH profile + -prof gc, ~1 h

# step by step
./install.sh                                    # plugin -> mavenLocal (version in gradle.properties)
python3 perf-apps/startup-bench/generate.py     # deterministic workload (seed 0)
./gradlew -p perf-apps/startup-bench jmh -PkoinVersion=4.2.2 -PdirectRegistration=true
# the four-way matrix (koinVersion x directRegistration), results as JMH JSON
(cd perf-apps/startup-bench && ./bench.sh results --quick && python3 summarize.py results)  # ~8 min, iterate
(cd perf-apps/startup-bench && ./bench.sh results && python3 summarize.py results)          # ~1 h, publish
```

`--quick` (or `-Pquick=true`): 1 fork, 5 warm-up + 5 measure, fixed 2 GB heap, no GC profiler, plugin compile-safety
off for the bench build (it only validates; the emitted registration code is the same). Expect
wider error bars — it is for iterating, the default profile is for numbers you quote.

Properties: `-PkoinVersion` (default `4.2.2`; a locally published koin-core works, e.g.
`4.2.3-perfs`), `-PdirectRegistration` (default `true`), `-PverifyGeneratedKeys` (default `false`;
`true` makes every generated key self-check against `indexKey()` at module load — the way to prove
key derivation at this scale, at the cost of the String build the feature removes), `-PkcpVersion`
(default `1.3.0`).

Benchmarks: `graphCreationAndInitialization` (what Metro times: load + realize everything),
`startOnly` (module loading only), `warmGetAll` (the multibinding scan on a built container).
