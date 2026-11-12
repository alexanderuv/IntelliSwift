#!/usr/bin/env bash
# Captures `swift build` output from the fixture packages for the build output parser tests
# (SwiftBuildOutputParserTest). Rerun it when a new toolchain changes the output format.
#
# Each sample is written to src/test/testData/swiftpm/build-output/<name>.txt, with the fixture
# directory replaced by /FIXTURE, temporary directories by /TMP and the home directory by /HOME.
#
# Usage: scripts/capture-build-output.sh
set -uo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
fixtures="$root/src/test/testData/swiftpm"
out="$fixtures/build-output"
packages="$out/packages"

sanitize() {
  local tmp_dir
  tmp_dir="$(cd "${TMPDIR:-/tmp}" && pwd -P)"
  sed -e "s|$(cd "$fixtures" && pwd -P)|/FIXTURE|g" \
      -e "s|$fixtures|/FIXTURE|g" \
      -e "s|/private$tmp_dir|/TMP|g" \
      -e "s|$tmp_dir|/TMP|g" \
      -e "s|/private/var/folders/[^ \"/]*/[^ \"/]*/T|/TMP|g" \
      -e "s|/var/folders/[^ \"/]*/[^ \"/]*/T|/TMP|g" \
      -e "s|$HOME|/HOME|g"
}

# capture <sample name> <package dir> [env assignments...] -- <swift build args...>
capture() {
  local name="$1" dir="$2"
  shift 2
  local env_args=()
  while [[ "$1" != "--" ]]; do env_args+=("$1"); shift; done
  shift
  rm -rf "$dir/.build"
  echo "$name: swift build $*"
  (cd "$dir" && env "${env_args[@]+"${env_args[@]}"}" swift build "$@" 2>&1) | sanitize > "$out/$name.txt"
}

swift --version

capture compile-errors "$packages/Broken" -- --target Broken
capture compile-errors-nocolor "$packages/Broken" NO_COLOR=1 -- --target Broken
capture compile-errors-native "$packages/Broken" NO_COLOR=1 -- --build-system native --target Broken
capture compile-errors-llvm "$packages/Broken" NO_COLOR=1 -- --target Broken -Xswiftc -diagnostic-style -Xswiftc llvm
capture cross-module-note "$packages/Broken" NO_COLOR=1 -- --product brokenmain
capture clang-error "$packages/ClangError" --
capture link-error "$packages/LinkError" NO_COLOR=1 --
capture link-error-native "$packages/LinkError" NO_COLOR=1 -- --build-system native
capture manifest-error "$packages/BadManifest" NO_COLOR=1 --
capture manifest-warnings "$packages/MissingTarget" NO_COLOR=1 --
capture success "$fixtures/Greeter" NO_COLOR=1 -- --build-tests
capture success-native "$fixtures/Greeter" NO_COLOR=1 -- --build-system native

for dir in "$packages"/* "$fixtures/Greeter" "$fixtures/Utils"; do rm -rf "$dir/.build"; done
