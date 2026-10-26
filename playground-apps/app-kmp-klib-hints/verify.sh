#!/bin/bash
# RED/GREEN matrix for KLIB hint visibility (#105, #106, #113).
# Usage: ./verify.sh <koinPluginVersion> [kotlinVersion...]
#   valid graph  (:app)               -> expect PASS on every target
#   broken graph (:app-falsify, -Pfalsify=true) -> expect KOIN-D001 on every target
set -u
cd "$(dirname "$0")"
PLUGIN=${1:?plugin version}; shift
KOTLINS=${*:-2.4.20}
TARGETS="Jvm WasmJs IosArm64"

run() { # $1 kotlin, $2 project, $3 extra args
    local tasks=""
    for t in $TARGETS; do tasks="$tasks :$2:compileKotlin$t"; done
    ../../gradlew --no-configuration-cache --console=plain --continue -PkoinPluginVersion="$PLUGIN" -PkotlinVersion="$1" $3 $tasks 2>&1
}

classify() { # $1 log, $2 target -> PASS | D00x list | ERROR
    awk -v t="compileKotlin$2" '
        /^> Task / { cur = ($0 ~ t"( |$)") }
        cur && /KOIN-D00[0-9]/ { match($0, /KOIN-D00[0-9][^:]*: [^ ]+/); s = s substr($0, RSTART, RLENGTH) "; " }
        cur && /^e: / && !/KOIN-|Fix with AI/ { other = 1 }
        END { if (s != "") print s; else if (other) print "ERROR"; else print "PASS" }' "$1"
}

printf "%-8s %-14s %-9s %s\n" kotlin graph target result
for k in $KOTLINS; do
    valid=$(mktemp); broken=$(mktemp)
    ../../gradlew --no-configuration-cache -q -PkoinPluginVersion="$PLUGIN" -PkotlinVersion="$k" clean >/dev/null 2>&1
    run "$k" app "" >"$valid"
    run "$k" app-falsify "-Pfalsify=true" >"$broken"
    for t in $TARGETS; do
        printf "%-8s %-14s %-9s %s\n" "$k" valid "$t" "$(classify "$valid" "$t")"
        printf "%-8s %-14s %-9s %s\n" "$k" "broken(D001)" "$t" "$(classify "$broken" "$t")"
    done
    rm -f "$valid" "$broken"
done
