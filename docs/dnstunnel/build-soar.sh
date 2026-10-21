#!/usr/bin/env bash
# Builds the Soar client wrapper for Android.
#
# The wrapper is our own program around the upstream Soar library
# (github.com/getlantern/soar, Apache-2.0): it reads a configuration, serves
# the local SOCKS5 and the optional local DNS, and prints the telemetry the
# app parses. The 32-bit ARM target needs external linking, so the NDK's
# compiler is used the way the other engines are built.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${OUT:-$HERE/out}"
NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/29.0.14206865}"
TC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
API=26

abi_cc() {
  case "$1" in
    arm64-v8a)   echo "arm64|aarch64-linux-android${API}-clang" ;;
    armeabi-v7a) echo "arm|armv7a-linux-androideabi${API}-clang" ;;
    x86_64)      echo "amd64|x86_64-linux-android${API}-clang" ;;
    x86)         echo "386|i686-linux-android${API}-clang" ;;
  esac
}

build() {
  local abi="$1" arch="$2" arm="${3:-}"
  mkdir -p "$OUT/$abi"
  local spec cc
  spec="$(abi_cc "$abi")"
  arch="${spec%%|*}"
  cc="${spec##*|}"
  local -a goenv=(
    "GOCACHE=$HERE/.gocache"
    "CGO_ENABLED=1"
    "GOOS=android"
    "GOARCH=$arch"
    "CC=$TC/$cc"
    "GOPROXY=${GOPROXY:-https://goproxy.cn,direct}"
    "GOSUMDB=off"
    "GOFLAGS=-mod=mod"
  )
  [ -n "$arm" ] && goenv+=("GOARM=$arm")
  ( cd "$HERE/soar-engine" && env "${goenv[@]}" \
    go build -trimpath -buildvcs=false \
      -ldflags="-s -w -linkmode external -extldflags \"-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384\"" \
      -o "$OUT/$abi/libsoar_client.so" . )
  chmod 755 "$OUT/$abi/libsoar_client.so"
  echo "built $abi/libsoar_client.so ($(stat -f%z "$OUT/$abi/libsoar_client.so") bytes)"
}

build arm64-v8a arm64
build armeabi-v7a arm 7
build x86_64 amd64
build x86 386

echo "--- done"
