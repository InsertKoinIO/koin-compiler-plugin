#!/bin/bash
# Checks the plugin's compiled bytecode against a target kotlin-compiler version:
# every org.jetbrains.kotlin member reference must resolve (JVM-style, hierarchy-aware).
# Catches binary-only breaks that source recompiles can't see (e.g. receiver-specialized
# or removed members) BEFORE users hit NoSuchMethodError/ClassCastException.
#
# Usage: ./tools/abi-check/check-kotlin-abi.sh <kotlin-version>   (jar must be in ~/.gradle cache)
set -e
VERSION=${1:?usage: check-kotlin-abi.sh <kotlin-version>}
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CLASSES="$ROOT/koin-compiler-plugin/build/classes/kotlin/main"
[ -d "$CLASSES" ] || { echo "compile first: ./gradlew :koin-compiler-plugin:compileKotlin"; exit 1; }
JAR=$(find ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler/$VERSION -name "kotlin-compiler-$VERSION.jar" | head -1)
STDLIB=$(find ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/$VERSION -name "kotlin-stdlib-$VERSION.jar" ! -name "*sources*" | head -1)
[ -n "$JAR" ] || { echo "kotlin-compiler $VERSION not in gradle cache"; exit 1; }

REFS=$(mktemp)
find "$CLASSES" -name "*.class" -print0 | xargs -0 javap -c -p 2>/dev/null \
  | grep -oE '// (Method|InterfaceMethod|Field) org/jetbrains/kotlin[^ ]+' \
  | sed -E 's|// (Method\|InterfaceMethod\|Field) ||' | sort -u > "$REFS"
echo "checking $(wc -l < "$REFS" | tr -d ' ') unique compiler-API refs against Kotlin $VERSION"
java "$ROOT/tools/abi-check/AbiCheck.java" "$REFS" "$JAR" "$STDLIB"
