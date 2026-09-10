#!/usr/bin/env python3
"""
KTZ-4829: deterministic Metro-shaped annotation workload for the startup benchmark.

Fingerprint mirrors Metro's published koin-mode benchmark: 500 modules (80 core / 350 feature /
70 app), 2,756 @Singleton contributions, 874 constructor edges, ~1,800 contributions bound to a
multibinding interface (Plugin / Initializer) realized with getAll<T>(). One Gradle module, one
@Module @ComponentScan per generated package (what Metro's generator does per Gradle module to stay
under the 64 KB method limit), one root @KoinApplication listing every module.

Usage: python3 generate.py [--scale F] [--dsl]   (writes src/main/kotlin/bench/gen, overwriting)
  --dsl: same graph declared with the safe DSL — one `fun xModule() = module { single<T>() bind I::class }`
  per generated module instead of @Module @ComponentScan + @Singleton — to measure the DSL path.
  Functions, not vals: a top-level val would run the module body (where registration happens) once
  at class load, outside the measured operation, and share its factories across containers.
  --scale 1.0 (default) is the Metro fingerprint; --scale 0.1 keeps the same shape at 10% size
  (50 modules / ~275 definitions) for qualifying plugin output in about a minute per configuration.
"""
import os, random, shutil, sys

SEED = 0
SCALE = 1.0
DSL = "--dsl" in sys.argv
if "--scale" in sys.argv:
    SCALE = float(sys.argv[sys.argv.index("--scale") + 1])
def scaled(n): return max(1, round(n * SCALE))
MODULES = [("core", scaled(80)), ("feature", scaled(350)), ("app", scaled(70))]
TOTAL_DEFINITIONS = scaled(2756)
EDGES = scaled(874)
BOUND = scaled(1800)  # contributions that also bind Plugin or Initializer

random.seed(SEED)
out = os.path.join(os.path.dirname(__file__), "src", "main", "kotlin", "bench", "gen")
shutil.rmtree(out, ignore_errors=True)
os.makedirs(out)

modules = []
for layer, count in MODULES:
    for i in range(count):
        modules.append((layer, i))

# distribute definitions across modules (>= 1 each)
per_module = [1] * len(modules)
for _ in range(TOTAL_DEFINITIONS - len(modules)):
    per_module[random.randrange(len(modules))] += 1

classes = []  # (module_index, class_name)
for m, n in enumerate(per_module):
    for k in range(n):
        classes.append((m, f"Svc{m}_{k}"))

# edges: a class may depend on classes from earlier modules only (acyclic, like layered Metro graph)
deps = {name: [] for _, name in classes}
edge_count = 0
by_module = {}
for m, name in classes:
    by_module.setdefault(m, []).append(name)
attempts = 0
while edge_count < EDGES and attempts < EDGES * 50:
    attempts += 1
    m, name = classes[random.randrange(len(classes))]
    if m == 0:
        continue
    src_module = random.randrange(0, m)
    target = random.choice(by_module[src_module])
    if target in deps[name] or len(deps[name]) >= 3:
        continue
    deps[name].append(target)
    edge_count += 1

bound = set(name for _, name in random.sample(classes, min(BOUND, len(classes))))

def pkg(m):
    layer, i = modules[m]
    return f"bench.gen.{layer}{i}"

with open(os.path.join(out, "Contracts.kt"), "w") as f:
    f.write("package bench.gen\n\ninterface Plugin\ninterface Initializer\n")

for m in range(len(modules)):
    layer, i = modules[m]
    p = pkg(m)
    lines = [f"package {p}", ""] + ([] if DSL else ["import org.koin.core.annotation.ComponentScan", "import org.koin.core.annotation.Module", "import org.koin.core.annotation.Singleton"]) + ["import bench.gen.Plugin", "import bench.gen.Initializer", ""]
    # imports for cross-package deps
    imported = set()
    for name in by_module[m]:
        for d in deps[name]:
            dm = next(mm for mm, nn in classes if nn == d)
            imported.add(f"{pkg(dm)}.{d}")
    for imp in sorted(imported):
        lines.append(f"import {imp}")
    lines.append("")
    if DSL:
        lines.append("import org.koin.dsl.module")
        lines.append("import org.koin.dsl.bind")
        lines.append("import org.koin.plugin.module.dsl.single")
        lines.append("")
    else:
        lines.append("@Module")
        lines.append("@ComponentScan")
        lines.append(f"class {layer.capitalize()}{i}Module")
        lines.append("")
    for idx, name in enumerate(by_module[m]):
        params = ", ".join(f"val d{j}: {d}" for j, d in enumerate(deps[name]))
        if name in bound:
            iface = "Plugin" if idx % 2 == 0 else "Initializer"
            if not DSL: lines.append(f"@Singleton(binds = [{iface}::class])")
            lines.append(f"class {name}({params}) : {iface}")
        else:
            if not DSL: lines.append("@Singleton")
            lines.append(f"class {name}({params})")
        lines.append("")
    if DSL:
        lines.append(f"fun {layer}{i}Module() = module {{")
        for idx, name in enumerate(by_module[m]):
            if name in bound:
                iface = "Plugin" if idx % 2 == 0 else "Initializer"
                lines.append(f"    single<{name}>() bind {iface}::class")
            else:
                lines.append(f"    single<{name}>()")
        lines.append("}")
        lines.append("")
    with open(os.path.join(out, f"{layer}{i}.kt"), "w") as f:
        f.write("\n".join(lines))

if DSL:
    module_vals = ",\n        ".join(f"{pkg(m)}.{modules[m][0]}{modules[m][1]}Module()" for m in range(len(modules)))
    with open(os.path.join(out, "App.kt"), "w") as f:
        f.write(f'''package bench.gen

import org.koin.core.Koin
import org.koin.dsl.koinApplication

// DSL flavour: the 500 modules are `fun xModule() = module {{ single<T>() bind I::class }}`; the
// root builds them fresh per operation and loads them with the plain runtime koinApplication, so
// what differs between plugin versions is only how each single<T>() is registered.
fun allModules() = listOf(
        {module_vals},
)

class AppComponent(val koin: Koin) {{
    fun plugins(): Set<Plugin> = koin.getAll<Plugin>().toSet()
    fun initializers(): Set<Initializer> = koin.getAll<Initializer>().toSet()
}}

fun startOnly(): Koin = koinApplication {{ modules(allModules()) }}.koin

fun createAndInitialize(): AppComponent {{
    val component = AppComponent(koinApplication {{ modules(allModules()) }}.koin)
    component.plugins()
    component.initializers()
    return component
}}
''')
    print(f"scale={SCALE} dsl=True modules={len(modules)} definitions={len(classes)} edges={edge_count} bound={len(bound)} -> {out}")
    sys.exit(0)

module_refs = ",\n        ".join(f"{pkg(m)}.{modules[m][0].capitalize()}{modules[m][1]}Module::class" for m in range(len(modules)))
with open(os.path.join(out, "App.kt"), "w") as f:
    f.write(f'''package bench.gen

import org.koin.core.Koin
import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.koinApplication

@KoinApplication(
    modules = [
        {module_refs},
    ],
)
class BenchKoinApp

// Same shape as the Metro harness' koin-mode AppComponent: multibindings synthesized via getAll.
class AppComponent(val koin: Koin) {{
    fun plugins(): Set<Plugin> = koin.getAll<Plugin>().toSet()
    fun initializers(): Set<Initializer> = koin.getAll<Initializer>().toSet()
}}

fun startOnly(): Koin = koinApplication<BenchKoinApp> {{ }}.koin

fun createAndInitialize(): AppComponent {{
    val component = AppComponent(koinApplication<BenchKoinApp> {{ }}.koin)
    component.plugins()
    component.initializers()
    return component
}}
''')

print(f"scale={SCALE} modules={len(modules)} definitions={len(classes)} edges={edge_count} bound={len(bound)} -> {out}")
