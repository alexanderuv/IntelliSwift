#!/usr/bin/env bash
# Runs the swift-syntax conformance test.
#
# 1. Builds tools/swift-syntax-oracle (needs a Swift 6.0+ toolchain).
# 2. Fetches a pinned corpus of open-source Swift packages into build/corpus (or uses CORPUS_DIR).
# 3. Runs the oracle over every .swift file and then the Gradle conformance test.
#
# Usage: scripts/conformance.sh [extra gradle args]
# Environment:
#   CORPUS_DIR     use this directory of .swift files instead of the pinned corpus
#   MAX_FAILURES   number of differing files tolerated (default 0)
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
work="$root/build/corpus"
mkdir -p "$work"

swift build -c release --package-path "$root/tools/swift-syntax-oracle"
oracle="$root/tools/swift-syntax-oracle/.build/release/swift-syntax-oracle"

if [[ -z "${CORPUS_DIR:-}" ]]; then
  CORPUS_DIR="$work/src"
  mkdir -p "$CORPUS_DIR"
  # name url tag
  while read -r name url tag; do
    [[ -z "$name" || "$name" == \#* ]] && continue
    if [[ ! -d "$CORPUS_DIR/$name" ]]; then
      git clone --quiet --depth 1 --branch "$tag" "$url" "$CORPUS_DIR/$name"
    fi
  done < "$root/scripts/corpus.txt"
fi

{
  find "$CORPUS_DIR" -name '*.swift' -type f -not -path '*/.build/*' | sort
  # The parser snapshot sources must be accepted (or, for Recovery.swift, rejected) by swift-syntax too.
  find "$root/src/test/testData/parser" -name '*.swift' -type f | sort
} > "$work/files.txt"
echo "Corpus: $(wc -l < "$work/files.txt" | tr -d ' ') files"
"$oracle" --files-from "$work/files.txt" --out "$work/oracle.jsonl"

cd "$root"
./gradlew test --tests '*SwiftConformanceTest*' \
  -PoracleOutput="$work/oracle.jsonl" \
  -PconformanceMaxFailures="${MAX_FAILURES:-0}" \
  "$@"
