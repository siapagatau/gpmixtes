#!/usr/bin/env bash
# Kompilasi + jalankan pemeriksaan model murni-Kotlin di JVM biasa (tanpa Android).
# Memakai kompiler Kotlin yang ikut Gradle (lib/kotlin-compiler-embeddable). Contoh:
#   GRADLE_LIB=/opt/gradle-8.14.3/lib ./jvm-tests/run.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
L="${GRADLE_LIB:-/opt/gradle-8.14.3/lib}"
OUT="${TMPDIR:-/tmp}/gpmix-jvm-$$"
mkdir -p "$OUT"
CP="$L/kotlin-compiler-embeddable-2.0.21.jar:$L/kotlin-stdlib-2.0.21.jar:$L/kotlin-script-runtime-2.0.21.jar:$L/kotlin-reflect-2.0.21.jar:$L/kotlin-daemon-embeddable-2.0.21.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-24.0.1.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar"
SRC="$ROOT/app/src/main/java/com/gpaudio/gpmix"
FILES=("$SRC"/model/*.kt "$SRC"/audio/NativeEngine.kt "$SRC"/engine/EngineSync.kt "$HERE"/ModelCheck.kt "$HERE"/SyncCheck.kt "$SRC"/audio/WavLoader.kt "$SRC"/audio/MidiParser.kt "$HERE"/IoCheck.kt)
java -Dfile.encoding=UTF-8 -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
  -cp "$L/kotlin-stdlib-2.0.21.jar" -d "$OUT/classes" "${FILES[@]}" 2>&1 | grep -v "^warning:" || true
java -Dfile.encoding=UTF-8 -cp "$OUT/classes:$L/kotlin-stdlib-2.0.21.jar" ModelCheckKt "$ROOT/app/src/test/resources/effects.txt"
