# WireGuard / AmneziaWG end-to-end check

Two harnesses that run the app's **own** WireGuard and AmneziaWG outbound
through the **real** core, so a regression in the JSON the app hands to the
core is caught on the host instead of on a device.

Nothing here is part of the app build. Both programs are Go and need the core
source, so they are run from a checkout of `ArasTey/xray-core`.

## What is checked

| | server | client | pass condition |
|---|---|---|---|
| WireGuard | the core's WireGuard *inbound* | the app's outbound | an HTTP request through the SOCKS inbound returns 200 |
| AmneziaWG | a real `amneziawg-go` device | the app's outbound | the handshake completes and a TCP segment arrives decrypted |

The core's WireGuard inbound cannot serve as the AmneziaWG side: it writes the
plain WireGuard UAPI and never applies the obfuscation parameters, so an AWG
client cannot complete a handshake with it. That is why `awgserver` exists.

## Running it

```bash
# 1. the app's own outbounds — the unit test dumps them
cd <app>
JAVA_HOME=<jdk17> \
  ARAS_DUMP_OUTBOUND=/tmp/wgtest/app-awg-outbound.json \
  ARAS_DUMP_WIREGUARD_OUTBOUND=/tmp/wgtest/app-wg-outbound.json \
  ./gradlew :app:testDebugUnitTest --no-daemon

# 2. the configs
mkdir -p /tmp/wgtest
cd <core source>            # a checkout with the app's core patches
cp <app>/tools/wg-e2e/wgtest/main.go   ./tmp_wgtest_main.go   # as its own package
go run ./tmp_wgtest_main.go            # writes /tmp/wgtest/*.json and the AWG UAPI

# 3. the WireGuard run
cd /tmp/wgtest
./xray run -c server.json &            # the core's WireGuard inbound
./xray run -c wg-client.json &         # the app's outbound
python3 -m http.server 18080 --bind <a non-loopback address of this host> &
curl -s --socks5-hostname 127.0.0.1:10808 http://<that address>:18080/   # 200

# 4. the AmneziaWG run
./awgserver awg-server.uapi &          # go build ./awgserver first
./xray run -c awg-client.json &
curl -s --socks5-hostname 127.0.0.1:10809 http://<that address>:18080/
# the server prints "TCP 172.16.0.2 -> <address> flags=0x02" and "OK — traffic
# carried through the AmneziaWG tunnel"
```

Two things the runs need:

- **A destination that is not loopback.** The core's gVisor stack drops
  packets whose destination is a loopback address ("martian packet"), and the
  server's freedom outbound blocks private ranges unless a `finalRules` allow
  rule says otherwise. A non-loopback address of the host itself works.
- **The keys.** The dump carries placeholder keys; `wgtest` puts this run's
  keys and endpoint in their place.

## Why the app's outbound and not a copy

The JSON the core reads is what the app builds, character for character: the
unit test writes `CoreOutboundBuilder.buildWireguardOutbound` /
`buildAmneziawgOutbound` to disk, and the harness runs that file. A copy in
the harness could drift from the app without anything noticing.
