#!/bin/bash
# Checks the plugin's compiled bytecode against a target kotlin-compiler version:
# every org.jetbrains.kotlin member reference must resolve (JVM-style, hierarchy-aware).
# Catches binary-only breaks that source recompiles can't see (e.g. receiver-specialized
# or removed members) BEFORE users hit NoSuchMethodError/ClassCastException.
#
# Checks BOTH halves of what actually runs on <kotlin-version>:
#   1. the plugin core
#   2. the ONE adapter module that <kotlin-version> selects, per the registry
# Other adapters are skipped on purpose — only the selected class is ever loaded, so
# the 2.3.20 adapter having breaks against 2.4.20 is correct, not a finding. Checking
# the core alone would miss an adapter-level break entirely (GH #89 / #99 review gap).
#
# Usage: ./tools/abi-check/check-kotlin-abi.sh <kotlin-version>   (jar must be in ~/.gradle cache)
set -e
VERSION=${1:?usage: check-kotlin-abi.sh <kotlin-version>}
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
REGISTRY="$ROOT/koin-compiler-version-adapter/resources/META-INF/koin/kotlin-version-adapters.properties"

JAR=$(find ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler/"$VERSION" -name "kotlin-compiler-$VERSION.jar" 2>/dev/null | head -1)
STDLIB=$(find ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/"$VERSION" -name "kotlin-stdlib-$VERSION.jar" ! -name "*sources*" 2>/dev/null | head -1)
[ -n "$JAR" ] || { echo "kotlin-compiler $VERSION not in gradle cache"; exit 1; }

# Which adapter CLASS does VERSION select? Highest registry key <= VERSION, same rule as
# KotlinAdapterLoader.decide — so an unverified version is checked against what it would
# actually load. Note keys and modules are not 1:1: several verified versions can share one
# class (2.4.10 and 2.4.20 both select Kotlin240Adapter, which lives in kotlin-2.4.0).
select_adapter_class() {
  local target="$1" best=""
  while IFS='=' read -r key value; do
    case "$key" in ''|\#*) continue ;; esac
    if [ "$(printf '%s\n%s\n' "$key" "$target" | sort -V | head -1)" = "$key" ]; then best="$value"; fi
  done < "$REGISTRY"
  echo "$best"
}

# The module whose build output actually contains that class — located, not inferred from
# the version, since key and module version differ whenever a class is shared.
module_dir_containing() {
  local class="$1" rel
  rel="$(echo "$class" | tr '.' '/').class"
  for candidate in "$ROOT"/koin-compiler-version-adapter/kotlin-*/build/classes/kotlin/main; do
    [ -f "$candidate/$rel" ] && { echo "$candidate"; return; }
  done
}

refs_of() {
  find "$1" -name "*.class" -print0 | xargs -0 javap -c -p 2>/dev/null \
    | grep -oE '// (Method|InterfaceMethod|Field) org/jetbrains/kotlin[^ ]+' \
    | sed -E 's|// (Method\|InterfaceMethod\|Field) ||' | sort -u
}

STATUS=0
check() { # <label> <classes-dir>
  local label="$1" classes="$2" refs out
  if [ ! -d "$classes" ]; then
    echo "  $label: NOT COMPILED ($classes) — run ./gradlew build"
    STATUS=1
    return
  fi
  refs=$(mktemp); refs_of "$classes" > "$refs"
  echo "  $label: $(wc -l < "$refs" | tr -d ' ') refs"
  out=$(java "$ROOT/tools/abi-check/AbiCheck.java" "$refs" "$JAR" "$STDLIB")
  echo "$out" | sed 's/^/    /'
  echo "$out" | tail -1 | grep -q "missing 0, unresolvable 0" || STATUS=1
}

echo "abi-check against Kotlin $VERSION"
check "core   " "$ROOT/koin-compiler-plugin/build/classes/kotlin/main"

ADAPTER_CLASS=$(select_adapter_class "$VERSION")
if [ -z "$ADAPTER_CLASS" ]; then
  echo "  adapter: none registered at or below $VERSION — below the supported floor"
  STATUS=1
else
  ADAPTER_DIR=$(module_dir_containing "$ADAPTER_CLASS")
  if [ -z "$ADAPTER_DIR" ]; then
    echo "  adapter ${ADAPTER_CLASS##*.}: NOT COMPILED in any kotlin-* module — run ./gradlew build"
    STATUS=1
  else
    check "adapter ${ADAPTER_CLASS##*.}" "$ADAPTER_DIR"
  fi
  check "adapter core  " "$ROOT/koin-compiler-version-adapter/build/classes/kotlin/main"
fi

[ $STATUS -eq 0 ] && echo "OK — Kotlin $VERSION resolves every reference" || echo "FAIL — see MISSING lines above"
exit $STATUS
