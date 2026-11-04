#!/bin/bash
# Real-Gradle checks the compiler test harness can't run: cross-module hint files under Kotlin IC
#, and the hidden Koin dependency task. Each builds a throwaway multi-module
# Gradle project and edits it between builds.
#
# Usage: tools/ic-check/check-hint-ic.sh <plugin-version> [kotlin-version]
#   The plugin version must be in mavenLocal (./gradlew publishToMavenLocal -PpluginVersion=...).
set -u
PLUGIN_VERSION=${1:?usage: $0 <plugin-version> [kotlin-version]}
KOTLIN_VERSION=${2:-2.4.20}
REPO=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d "${TMPDIR:-/tmp}/kcp-ic-check.XXXXXX")
FAILED=0

# new_project <name> [modules]: modules as "name:dep1,dep2 …" (default "lib app:lib"), in order.
new_project() {
    local name=$1 modules=${2:-"lib app:lib"} dir=$WORK/$1 includes=""
    mkdir -p "$dir"
    cp -R "$REPO/gradle" "$REPO/gradlew" "$dir/"
    for spec in $modules; do
        local module=${spec%%:*} deps=""
        [ "$spec" != "$module" ] && deps=${spec#*:}
        includes="$includes, \":$module\""
        mkdir -p "$dir/$module/src/main/kotlin"
        local depLines=""
        for dep in ${deps//,/ }; do depLines="$depLines    implementation(project(\":$dep\"))
"; done
        cat > "$dir/$module/build.gradle.kts" <<EOF
plugins {
    kotlin("jvm")
    id("io.insert-koin.compiler.plugin") version "$PLUGIN_VERSION"
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation("io.insert-koin:koin-core:4.2.2")
    implementation("io.insert-koin:koin-annotations:4.2.2")
$depLines}
EOF
    done
    cat > "$dir/settings.gradle.kts" <<EOF
pluginManagement {
    plugins { kotlin("jvm") version "$KOTLIN_VERSION" }
    repositories { mavenLocal(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement { repositories { mavenLocal(); mavenCentral() } }
rootProject.name = "$name"
include(${includes#, })
EOF
    echo "$dir"
}

build() { (cd "$1" && ./gradlew :app:compileKotlin --console=plain 2>&1); }

expect() {
    local name=$1 condition=$2
    if [ "$condition" = ok ]; then echo "PASS  $name"; else echo "FAIL  $name"; FAILED=1; fi
}

# 1. A module with no definitions of its own (`includes()` only) must not take an unrelated file as
#    its hint's IC owner: editing that file would delete the hint and leave a persistent false D001.
scenario_unrelated_edit() {
    local dir; dir=$(new_project unrelated-edit)
    local src=$dir/lib/src/main/kotlin/feat
    mkdir -p "$src"
    printf 'package feat\n\nclass Alpha { fun v() = 1 }\n' > "$src/Alpha.kt"
    printf 'package feat\n\nimport org.koin.dsl.module\n\nval midModule = module { includes(zedModule) }\n' > "$src/Mid.kt"
    printf 'package feat\n\nimport org.koin.dsl.module\nimport org.koin.plugin.module.dsl.single\n\nclass Zed\nclass Yan\nval zedModule = module { single<Yan>() }\n' > "$src/Zed.kt"
    printf 'package feat\n\nimport org.koin.dsl.module\nimport org.koin.plugin.module.dsl.single\n\nval otherModule = module { single<Zed>() }\n' > "$src/Other.kt"
    cat > "$dir/app/src/main/kotlin/App.kt" <<'EOF'
import feat.Zed
import feat.midModule
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class AppThing(val z: Zed)

fun main() {
    startKoin { modules(module { single<AppThing>() }, midModule) }
}
EOF
    build "$dir" | grep -q "KOIN-D001.*feat.Zed" && r=ok || r=ko
    expect "unrelated-edit: Zed missing from the loaded graph is reported" $r
    sed -i.bak 's/v() = 1/v() = 2/' "$src/Alpha.kt" && rm "$src/Alpha.kt.bak"
    build "$dir" > /dev/null
    printf 'package feat\n\nimport org.koin.dsl.module\nimport org.koin.plugin.module.dsl.single\n\nclass Zed\nclass Yan\nval zedModule = module { single<Yan>(); single<Zed>() }\n' > "$src/Zed.kt"
    printf 'package feat\n\nimport org.koin.dsl.module\nimport org.koin.plugin.module.dsl.single\n\nval otherModule = module { single<Yan>() }\n' > "$src/Other.kt"
    build "$dir" | grep -q "KOIN-D001" && r=ko || r=ok
    expect "unrelated-edit: no false D001 after an unrelated file was edited" $r
}

# 2. Moving a module to another package must not leave the old package's hint class behind.
scenario_package_move() {
    local dir; dir=$(new_project package-move)
    local src=$dir/lib/src/main/kotlin
    mkdir -p "$src/feat"
    printf 'package feat\n\nimport org.koin.dsl.module\nimport org.koin.plugin.module.dsl.single\n\nclass Zed\nclass Yan\nval zedModule = module { single<Zed>() }\n' > "$src/feat/Zed.kt"
    printf 'package feat\n\nimport org.koin.dsl.module\nimport org.koin.plugin.module.dsl.single\n\nval otherModule = module { single<Yan>() }\n' > "$src/feat/Other.kt"
    cat > "$dir/app/src/main/kotlin/App.kt" <<'EOF'
import feat.Zed
import feat.zedModule
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class AppThing(val z: Zed)

fun main() {
    startKoin { modules(module { single<AppThing>() }, zedModule) }
}
EOF
    build "$dir" > /dev/null
    rm "$src/feat/Other.kt"
    mkdir -p "$src/feat2"
    printf 'package feat2\n\nimport feat.Yan\nimport org.koin.dsl.module\nimport org.koin.plugin.module.dsl.single\n\nval otherModule = module { single<Yan>() }\n' > "$src/feat2/Other.kt"
    build "$dir" > /dev/null
    local hints=$dir/lib/build/classes/kotlin/main/org/koin/plugin/hints
    ls "$hints" | grep -q "_feat_otherModule_" && r=ko || r=ok
    expect "package-move: no stale hint class for the old package" $r
    ls "$hints" | grep -q "_feat2_otherModule_" && r=ok || r=ko
    expect "package-move: hint class for the new package exists" $r
}

# 3. A relaying module must not read back its own previous relay: an includes() edge removed two
#    hops away would otherwise live on, and a now-missing dependency go unreported.
scenario_relay_self_read() {
    local dir; dir=$(new_project relay-self-read "data feature:data mid:feature,data app:mid")
    mkdir -p "$dir/data/src/main/kotlin/data" "$dir/feature/src/main/kotlin/feature" "$dir/mid/src/main/kotlin/mid" "$dir/app/src/main/kotlin/app"
    printf 'package data\n\nimport org.koin.dsl.module\nimport org.koin.plugin.module.dsl.single\n\nclass Repo\n\nval dataModule = module { single<Repo>() }\n' > "$dir/data/src/main/kotlin/data/Data.kt"
    printf 'package feature\n\nimport data.dataModule\nimport org.koin.dsl.module\n\nval otherModule = module { }\n\nval featureModule = module { includes(dataModule) }\n' > "$dir/feature/src/main/kotlin/feature/Feature.kt"
    printf 'package mid\n\nimport data.Repo\nimport feature.featureModule\nimport org.koin.dsl.module\nimport org.koin.plugin.module.dsl.single\n\nclass MidThing(val repo: Repo)\n\nval midModule = module {\n    includes(featureModule)\n    single<MidThing>()\n}\n' > "$dir/mid/src/main/kotlin/mid/Mid.kt"
    printf 'package app\n\nimport mid.midModule\nimport org.koin.core.context.startKoin\nimport org.koin.dsl.module\nimport org.koin.plugin.module.dsl.single\n\nclass AppThing\n\nfun main() {\n    startKoin { modules(module { single<AppThing>() }, midModule) }\n}\n' > "$dir/app/src/main/kotlin/app/App.kt"
    build "$dir" | grep -q "KOIN-" && r=ko || r=ok
    expect "relay-self-read: the complete graph is clean" $r
    sed -i.bak 's/includes(dataModule)/includes(otherModule)/' "$dir/feature/src/main/kotlin/feature/Feature.kt" && rm "$dir/feature/src/main/kotlin/feature/Feature.kt.bak"
    build "$dir" | grep -q "KOIN-D001.*data.Repo" && r=ok || r=ko
    expect "relay-self-read: dropping dataModule two hops away is reported incrementally" $r
}

# 4. Opted in, a module that starts Koin and reaches definitions only through an `implementation`
#    dependency of a dependency gets warned on every build until it depends on that module directly.
scenario_hidden_dependency() {
    local dir; dir=$(new_project hidden-dependency "data feature:data tool:feature app:feature")
    mkdir -p "$dir/data/src/main/kotlin/data" "$dir/feature/src/main/kotlin/feature" "$dir/tool/src/main/kotlin/tool" "$dir/app/src/main/kotlin/app"
    printf 'package tool\n\nimport feature.featureModule\n\nval toolModules = listOf(featureModule)\n' > "$dir/tool/src/main/kotlin/tool/Tool.kt"
    # A file dependency under the configuration cache: the check's dependency filters must be storable.
    printf 'org.gradle.configuration-cache=true\n' > "$dir/gradle.properties"
    mkdir -p "$dir/data/libs" && (cd "$dir/data/libs" && printf 'x' > x.txt && zip -q plain.jar x.txt && rm x.txt)
    printf 'dependencies { implementation(fileTree("libs") { include("*.jar") }) }\n' >> "$dir/data/build.gradle.kts"
    printf 'package data\n\nimport org.koin.dsl.module\nimport org.koin.plugin.module.dsl.single\n\nclass Repo\n\nval dataModule = module { single<Repo>() }\n' > "$dir/data/src/main/kotlin/data/Data.kt"
    printf 'package feature\n\nimport data.dataModule\nimport org.koin.dsl.module\n\nval featureModule = module { includes(dataModule) }\n' > "$dir/feature/src/main/kotlin/feature/Feature.kt"
    printf 'package app\n\nimport feature.featureModule\nimport org.koin.core.context.startKoin\n\nfun main() {\n    startKoin { modules(featureModule) }\n}\n' > "$dir/app/src/main/kotlin/app/App.kt"
    local warning='starts Koin, but these modules'
    local out; out=$(build "$dir")
    echo "$out" | grep -q "BUILD SUCCESSFUL" && ! echo "$out" | grep -qE "$warning|KoinHiddenDependencies" && r=ok || r=ko
    expect "hidden-dependency: off by default, the check doesn't run" $r
    for module in app tool; do
        printf 'koinCompiler { hiddenDependencyCheck = "warning" }\n' >> "$dir/$module/build.gradle.kts"
    done
    out=$(build "$dir")
    echo "$out" | grep -q "$warning" && echo "$out" | grep -q 'project(":data")' && r=ok || r=ko
    expect "hidden-dependency: :data, two hops from the entry module, is reported" $r
    echo "$out" | grep -q "BUILD SUCCESSFUL" && r=ok || r=ko
    expect "hidden-dependency: a file dependency doesn't break the configuration cache" $r
    build "$dir" | grep -q "$warning" && r=ok || r=ko
    expect "hidden-dependency: the warning repeats on an unchanged build" $r
    printf 'koinCompiler { hiddenDependencyCheck = "error" }\n' >> "$dir/app/build.gradle.kts"
    out=$(build "$dir")
    echo "$out" | grep -q "BUILD FAILED" && echo "$out" | grep -q "$warning" && r=ok || r=ko
    expect "hidden-dependency: hiddenDependencyCheck = \"error\" fails the build with the report" $r
    printf 'dependencies { implementation(project(":data")) }\n' >> "$dir/app/build.gradle.kts"
    out=$(build "$dir")
    echo "$out" | grep -q "BUILD SUCCESSFUL" && ! echo "$out" | grep -q "$warning" && r=ok || r=ko
    expect "hidden-dependency: no report once the entry module depends on :data directly" $r
    (cd "$dir" && ./gradlew :tool:compileKotlin --console=plain 2>&1) | grep -q "$warning" && r=ko || r=ok
    expect "hidden-dependency: a module that doesn't start Koin is never reported" $r
}

echo "Koin compiler plugin $PLUGIN_VERSION, Kotlin $KOTLIN_VERSION — work dir $WORK"
scenario_unrelated_edit
scenario_package_move
scenario_relay_self_read
scenario_hidden_dependency
exit $FAILED
