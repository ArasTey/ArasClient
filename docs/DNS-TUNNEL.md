# DNS tunnel (StormDNS / CottenDns)

The app can carry a profile's traffic through ordinary DNS queries, with the
DNS-tunnel engines shipped alongside the aether and mieru clients.

## What is in the app

| File | What it is |
| :--- | :--- |
| `app/libs/<abi>/libstormdns_client.so` | The StormDNS client, built for that ABI |
| `app/libs/<abi>/libcottendns_client.so` | The CottenDns client, built for that ABI |
| `app/src/main/java/com/aras/client/core/dnstunnel/` | The engine configuration and the process that runs it |
| `app/src/main/java/com/aras/client/fmt/DnsTunnelFmt.kt` | The `dnstunnel://` share link |
| `app/src/main/java/com/aras/client/ui/server/ServerDnsTunnelActivity.kt` | The editor |

The engines are programs, not libraries: the app writes the engine's own TOML
configuration and its resolver list, starts the program, and reads its progress
back from stdout. The engine serves a local SOCKS5, and the app's outbound is a
hop to that listener — the same shape an Aether or a Mieru profile has.

A DNS tunnel is a transport, not a protocol: it carries the traffic of another
profile, named in the editor. That profile's outbound is dialled through the
tunnel's SOCKS, so the first hop of the connection is a DNS query.

## Where the engines come from

Both engines are built from their own upstream sources, pinned:

| Engine | Source | Pin | Licence |
| :--- | :--- | :--- | :--- |
| StormDNS | https://github.com/iampedii/StormDNS | `74385bc9854d4f4aab9cb7d78dc8c90de75860bd` | MIT, Copyright (c) 2026 Amin Mahmoudi |
| CottenDns | https://github.com/WhiteDNS/CottenDns | `1f3cc524dddbc9d59ca2b312f4408a813efa8f71` | MIT, Copyright (c) 2026 Amin Mahmoudi, NullRoute1970, tajirax |

The licence of each is MIT, which is compatible with this app's GPL-3. The
notices travel with the app: `app/src/main/assets/open_source_licenses.html`
carries both texts and the attribution chain, and is shown in About.

The build recipe is `docs/dnstunnel/build-engines.sh`. It needs the NDK
(29.0.14206865), a Go toolchain, and the pinned sources checked out under
`vendor/`; it writes one program per ABI into `out/<abi>/`, which is then
copied into `app/libs/<abi>/`.

The engine's own source is not vendored into this repository: the app ships the
built programs, and the script above rebuilds them from the pinned upstream
commits.

## What is *not* taken from anywhere

The engines are not the only DNS-tunnel client for Android. The app's own
integration — the profile type, the editor, the share link, the process
management — is written here, against the engines' documented configuration
keys and their command line. No other application's code, user interface,
assets or profile format is used.

## Configuration keys

The editor writes the engine's own keys, so a profile can be read by the engine
directly:

```
DOMAINS = ["v.example.com"]
DATA_ENCRYPTION_METHOD = 1
ENCRYPTION_KEY = "..."
PROTOCOL_TYPE = "SOCKS5"
LISTEN_IP = "127.0.0.1"
LISTEN_PORT = 10821
LOCAL_DNS_ENABLED = true
LOCAL_DNS_IP = "127.0.0.1"
LOCAL_DNS_PORT = 10822
```

`Extra engine settings` appends the engine's own TOML keys to the file, for the
settings the editor does not have a field for. The CottenDns fork also takes
`RESOLVER_TRANSPORT` and the other keys it added; the StormDNS engine refuses a
configuration carrying a key it does not know, so each engine is given only the
keys it understands.

## The share link

```
dnstunnel://v.example.com?engine=storm&key=…&method=3&resolvers=1.1.1.1&port=10821#remarks
```

The resolvers carry newlines, percent-encoded. The key may hold an "=" of its
own, so the separator is the first one in a parameter, not the last.
