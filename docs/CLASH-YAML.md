# Clash YAML import

ArasClient can import inline `proxies:` lists from Clash/Mihomo `.yaml` and `.yml` documents into its existing Xray profiles.

## Import

- In the main screen, open **Add configuration → Import configuration from local file** and select the YAML file.
- Alternatively, copy the YAML text and use **Import from clipboard**.
- Subscription responses containing the same YAML format use the shared batch importer, including base64-encoded responses.

No filename extension change is needed: the existing file chooser accepts all MIME types.

## Supported proxy definitions

- Shadowsocks (`ss`), without plugins.
- VMess (`vmess`), with `alterId: 0`.
- VLESS (`vless`), with `encryption: none`, including TLS and Reality.
- Trojan (`trojan`) with TLS.
- TCP, WebSocket (`ws-opts.path` and `headers.Host`), and gRPC (`grpc-opts.grpc-service-name`) for applicable protocols.
- SNI/servername, ALPN, client fingerprint, and explicit `skip-cert-verify`.

Certificate verification remains enabled unless the YAML explicitly disables it.

This is **proxy-list import**, not a Clash runtime. Rules, proxy groups, DNS settings, listeners, and provider URLs are not applied or fetched. Provider-only documents must first be exported as an inline `proxies:` list. Other proxy types and unsupported per-proxy options are rejected; they are not silently downgraded. A rejected list does not replace existing subscription profiles. Existing subscription filters still apply to successfully parsed profiles.

The importer uses SnakeYAML SafeConstructor, rejects duplicate keys, and limits document size, nesting, alias expansion, and proxy count. Parser diagnostics do not log YAML contents.

## Example

```yaml
proxies:
  - name: Example VLESS
    type: vless
    server: example.com
    port: 443
    uuid: 11111111-1111-1111-1111-111111111111
    tls: true
    servername: example.com
    network: ws
    ws-opts:
      path: /proxy
      headers:
        Host: example.com
```

The example uses placeholder credentials and is not a working server.

## Validation

`ClashYamlFmtTest` covers block/flow YAML, conversion through the existing VLESS dispatch, credential escaping/IPv6, Shadowsocks and VMess link conversion, Reality, rejected unsupported options, malformed/tagged/oversized documents, and ordinary link/JSON detection. These are JVM tests, not an end-to-end file-picker or live connection test.
