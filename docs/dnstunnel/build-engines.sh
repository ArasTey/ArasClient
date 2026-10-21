#!/usr/bin/env bash
# Builds the DNS-tunnel client engines for Android, from the pinned upstream
# sources in vendor/. The outputs are the engine executables, packaged into the
# app as native libraries and started as child processes (the same way the
# bundled Aether and Mieru engines already work in this project).
#
# Sources and licenses (see ../DNS-TUNNEL.md and the LICENSE files beside this
# script):
#   StormDNS  - MIT, Copyright (c) 2026 Amin Mahmoudi (fork: NullRoute1970)
#   CottenDns - MIT with the three-way attribution chain, Copyright (c) 2026
#               Amin Mahmoudi, NullRoute1970, tajirax
#
# Usage:
#   git clone --depth 1 --branch feat/whitedns-android-client \
#       https://github.com/iampedii/StormDNS.git vendor/stormdns
#   (cd vendor/stormdns && git fetch --depth 1 origin 74385bc9854d4f4aab9cb7d78dc8c90de75860bd \
#       && git checkout 74385bc9854d4f4aab9cb7d78dc8c90de75860bd)
#   git clone --depth 1 https://github.com/WhiteDNS/CottenDns.git vendor/cottendns
#   (cd vendor/cottendns && git fetch --depth 1 origin 1f3cc524dddbc9d59ca2b312f4408a813efa8f71 \
#       && git checkout 1f3cc524dddbc9d59ca2b312f4408a813efa8f71)
#   ./build-engines.sh
#
# The module proxy is not reachable from the network this is usually built on,
# so the Go modules are fetched straight from their repositories and checked
# against the engines' own go.sum.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENDOR="${VENDOR:-$HERE/vendor}"
OUT="${OUT:-$HERE/out}"
NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/29.0.14206865}"
TC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
API=26

# The 32-bit ARM target needs its own toolchain triple; the others match the
# architecture name, so the mapping is per-ABI rather than per-arch.
abi_cc() {
  case "$1" in
    arm64-v8a)   echo "arm64|aarch64-linux-android${API}-clang" ;;
    armeabi-v7a) echo "arm|armv7a-linux-androideabi${API}-clang" ;;
    x86_64)      echo "amd64|x86_64-linux-android${API}-clang" ;;
    x86)         echo "386|i686-linux-android${API}-clang" ;;
  esac
}

build_engine() {
  local src="$1" pkg="$2" name="$3" abi="$4" goarm="${5:-}"
  local spec arch cc
  spec="$(abi_cc "$abi")"
  arch="${spec%%|*}"
  cc="${spec##*|}"
  mkdir -p "$OUT/$abi"
  # The env prefix is built as an array: an empty expansion in the middle of a
  # command prefix would end the assignment list and the shell would try to run
  # the next assignment as the command.
  local -a goenv=(
    "GOCACHE=$src/.gocache"
    "CGO_ENABLED=1"
    "GOOS=android"
    "GOARCH=$arch"
    "GOPROXY=${GOPROXY:-direct}"
    "GOSUMDB=off"
    "GOFLAGS=-mod=mod"
  )
  [ -n "$goarm" ] && goenv+=("GOARM=$goarm")
  goenv+=("CC=$TC/$cc")
  ( cd "$src" && env "${goenv[@]}" \
    go build -trimpath -buildvcs=false \
      -ldflags="-s -w -linkmode external -extldflags \"-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384\"" \
      -o "$OUT/$abi/$name" "$pkg" )
  chmod 755 "$OUT/$abi/$name"
  echo "built $abi/$name ($(stat -f%z "$OUT/$abi/$name" 2>/dev/null || stat -c%s "$OUT/$abi/$name") bytes)"
}

for abi in arm64-v8a armeabi-v7a x86_64 x86; do
  goarm=""
  [ "$abi" = "armeabi-v7a" ] && goarm=7
  build_engine "$VENDOR/stormdns" ./cmd/client stormdns-client "$abi" "$goarm"
  cp "$OUT/$abi/stormdns-client" "$OUT/$abi/libstormdns_client.so"
  build_engine "$VENDOR/cottendns" ./cmd/client cottendns-client "$abi" "$goarm"
  cp "$OUT/$abi/cottendns-client" "$OUT/$abi/libcottendns_client.so"
done

echo "--- done"
ls -la "$OUT"/*/
