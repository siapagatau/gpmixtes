#!/usr/bin/env bash
# Build & jalankan tes native di host (tanpa Android). Pakai: native-tests/run.sh [san]
# "san" = aktifkan AddressSanitizer + UBSan.
set -euo pipefail
cd "$(dirname "$0")"
SRC=../app/src/main/cpp
OUT=${TMPDIR:-/tmp}/gpmix-native-tests
mkdir -p "$OUT"
FLAGS="-std=c++17 -O2 -g -Wall -Wextra -Wno-unused-parameter -I$SRC"
if [ "${1:-}" = "san" ]; then FLAGS="$FLAGS -fsanitize=address,undefined -fno-sanitize-recover=undefined"; fi
for t in test_fx test_mixer; do
  [ -f "$t.cpp" ] || continue
  SRCS="$t.cpp $SRC/fx/fx_registry.cpp"
  [ "$t" = test_mixer ] && SRCS="$SRCS $SRC/mixer.cpp"
  g++ $FLAGS $SRCS -o "$OUT/$t" -lpthread
  echo "== $t"
  "$OUT/$t"
done
