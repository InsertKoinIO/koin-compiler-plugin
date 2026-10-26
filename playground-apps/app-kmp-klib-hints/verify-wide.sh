#!/bin/bash
# Every hint kind crossed once (:lib-wide -> :app-wide). Each KLIB target must report exactly what jvm reports.
# Usage: ./verify-wide.sh <koinPluginVersion> [kotlinVersion]
set -u
cd "$(dirname "$0")"
PLUGIN=${1:?plugin version}; KOTLIN=${2:-2.4.20}
log=$(mktemp)
../../gradlew --no-configuration-cache -q -PkoinPluginVersion="$PLUGIN" -PkotlinVersion="$KOTLIN" :lib-wide:clean :app-wide:clean >/dev/null 2>&1
../../gradlew --no-configuration-cache --console=plain --continue -PkoinPluginVersion="$PLUGIN" -PkotlinVersion="$KOTLIN" \
    :app-wide:compileKotlinJvm :app-wide:compileKotlinWasmJs :app-wide:compileKotlinIosArm64 >"$log" 2>&1
diags() { awk -v t="compileKotlin$1" '/^> Task /{cur=($0 ~ t"( |$)")} cur && /KOIN-[DW][0-9]+/ {match($0,/KOIN-[DW][0-9]+\].*/); print substr($0,RSTART,RLENGTH)}' "$log" \
    | sed -E 's/(KOIN-[DW][0-9]+)\] ([^ ]+ [^ ]+ [^ ]+).*/\1 \2/' | sort -u; }
jvm=$(diags Jvm)
echo "jvm:"; echo "$jvm" | sed 's/^/  /'
status=0
for t in WasmJs IosArm64; do
    d=$(diags $t)
    if [ "$d" == "$jvm" ]; then echo "$t: SAME AS JVM"; else echo "$t: DIFFERS"; diff <(echo "$jvm") <(echo "$d") | sed 's/^/  /'; status=1; fi
done
rm -f "$log"; exit $status
