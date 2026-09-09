#!/usr/bin/env python3
"""
Summarize JMH JSON results produced by bench.sh into one table:
score, error and gc.alloc.rate.norm (bytes/op) per benchmark per configuration.

Usage: python3 summarize.py <results-dir>
"""
import glob, json, os, sys

results_dir = sys.argv[1] if len(sys.argv) > 1 else "results"
rows = {}
configs = []
for path in sorted(glob.glob(os.path.join(results_dir, "*.json"))):
    config = os.path.basename(path)[:-5]
    configs.append(config)
    for entry in json.load(open(path)):
        bench = entry["benchmark"].split(".")[-1]
        metric = entry["primaryMetric"]
        # JMH prefixes profiler metrics with a middle dot: "·gc.alloc.rate.norm"
        secondary = entry.get("secondaryMetrics", {})
        alloc = next((v for k, v in secondary.items() if k.endswith("gc.alloc.rate.norm")), {})
        rows.setdefault(bench, {})[config] = (
            metric["score"], metric["scoreError"], metric["scoreUnit"],
            alloc.get("score"), alloc.get("scoreUnit"),
        )

for bench, per_config in rows.items():
    print(f"\n{bench}")
    print(f"  {'configuration':<32}{'score':>14}{'±':>12}  {'alloc/op':>16}")
    base = None
    for config in configs:
        if config not in per_config:
            continue
        score, err, unit, alloc, alloc_unit = per_config[config]
        if base is None:
            base = score
        delta = f"{(score - base) / base * 100:+.0f}%" if base else ""
        alloc_str = f"{alloc:,.0f} {alloc_unit}" if alloc is not None else "-"
        print(f"  {config:<32}{score:>10.3f} {unit:<4}{err:>10.3f}  {alloc_str:>16}  {delta}")
