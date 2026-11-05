#!/bin/bash
# #144: hint signatures must be unique per KLIB file. `:lib-dup` holds the test-compilation shapes that crashed
# the KLIB serializer ("Different declarations with the same signatures") on 1.2.2; `:lib-dup-public` the
# main-compilation shape that already crashed on 1.2.1. Each module compiles on its own, so one crash never
# hides another.
# Usage: ./verify-dup.sh <koinPluginVersion> [kotlinVersion]   -> PASS per task, or CLASH / FAILED / NOT RUN
set -u
cd "$(dirname "$0")"
PLUGIN=${1:?plugin version}; KOTLIN=${2:-2.4.20}
log=$(mktemp)
../../gradlew --no-configuration-cache -q -PkoinPluginVersion="$PLUGIN" -PkotlinVersion="$KOTLIN" :lib-dup:clean :lib-dup-public:clean >/dev/null 2>&1
tasks=""
for t in Jvm WasmJs IosArm64; do tasks="$tasks :lib-dup:compileKotlin$t :lib-dup:compileTestKotlin$t :lib-dup-public:compileKotlin$t"; done
# --no-build-cache: a task restored from the cache did not run this compiler, and would print as PASS.
../../gradlew --no-configuration-cache --no-build-cache --console=plain --continue -PkoinPluginVersion="$PLUGIN" -PkotlinVersion="$KOTLIN" $tasks >"$log" 2>&1
status=0
for task in $tasks; do
    # A task line with no suffix is a task that ran; any suffix (FAILED, SKIPPED, NO-SOURCE, UP-TO-DATE,
    # FROM-CACHE) means the compiler did not run it, and a missing line means Gradle never reached it.
    r=$(awk -v t="$task" '
        /^> Task / { cur = ($3 == t); if (cur) { outcome = ($4 == "" ? "RAN" : $4) } }
        cur && /same signatures/ { clash = 1 }
        END { if (clash) print "CLASH"; else if (outcome == "") print "NOT RUN"; else if (outcome == "RAN") print "PASS"; else print outcome }' "$log")
    printf "%-38s %s\n" "$task" "$r"
    [ "$r" = PASS ] || status=1
done
if [ $status -ne 0 ]; then echo "log: $log"; else rm -f "$log"; fi; exit $status
