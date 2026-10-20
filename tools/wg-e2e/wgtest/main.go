// The end-to-end check for the WireGuard and AmneziaWG outbounds.
//
// It writes the configs for two runs against the real core:
//
//   Plain WireGuard — the core's WireGuard *inbound* is the server, the app's
//   own outbound (dumped by the app's unit test) is the client. A real HTTP
//   request through the SOCKS inbound proves the tunnel carries traffic.
//
//   AmneziaWG — a real amneziawg-go device is the server, because the core's
//   inbound writes the plain WireGuard UAPI and never applies the obfuscation
//   parameters, so it cannot serve as the AWG side. The app's outbound is the
//   client; the server reports the packets it decrypts.
//
// Not part of the app build. See ../README.md.
package main

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"os"
	"strings"

	"golang.org/x/crypto/curve25519"
)

func newKey() (priv, pub string) {
	key := make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		panic(err)
	}
	key[0] &= 248
	key[31] &= 127
	key[31] |= 64
	pubKey, err := curve25519.X25519(key, curve25519.Basepoint)
	if err != nil {
		panic(err)
	}
	return hex.EncodeToString(key), hex.EncodeToString(pubKey)
}

func write(path, content string) {
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		panic(err)
	}
}

// patch takes the app's outbound and puts this run's keys and endpoint in it.
func patch(dump string, secret, public, endpoint string) string {
	out := strings.TrimSpace(dump)
	out = strings.ReplaceAll(out, "secretkey", secret)
	out = strings.ReplaceAll(out, "pubkey", public)
	out = strings.ReplaceAll(out, "1.2.3.4:443", endpoint)
	return out
}

func main() {
	serverPriv, serverPub := newKey()
	clientPriv, clientPub := newKey()

	// ── Plain WireGuard ──────────────────────────────────────────────────────
	write("/tmp/wgtest/server.json", fmt.Sprintf(`{
  "log": {"loglevel": "info"},
  "inbounds": [
    {
      "tag": "wg-in",
      "protocol": "wireguard",
      "listen": "127.0.0.1",
      "port": 51820,
      "settings": {
        "secretKey": "%s",
        "address": ["172.16.0.1/32"],
        "peers": [
          {"publicKey": "%s", "allowedIPs": ["172.16.0.2/32"], "keepAlive": 25}
        ],
        "mtu": 1420,
        "noKernelTun": true
      }
    }
  ],
  "outbounds": [
    {
      "tag": "direct",
      "protocol": "freedom",
      "settings": {"finalRules": [{"action": "Allow", "networks": ["tcp", "udp"]}]}
    }
  ]
}`, serverPriv, clientPub))

	wgOutbound := `{
      "tag": "proxy",
      "protocol": "wireguard",
      "settings": {
        "secretKey": "` + clientPriv + `",
        "address": ["172.16.0.2/32"],
        "peers": [
          {
            "publicKey": "` + serverPub + `",
            "endpoint": "127.0.0.1:51820",
            "allowedIPs": ["0.0.0.0/0"],
            "keepAlive": 25
          }
        ],
        "mtu": 1420,
        "noKernelTun": true
      }
    }`
	if dump, err := os.ReadFile("/tmp/wgtest/app-wg-outbound.json"); err == nil {
		wgOutbound = patch(string(dump), clientPriv, serverPub, "127.0.0.1:51820")
		fmt.Println("wireguard: using the app's outbound as dumped by the unit test")
	}

	write("/tmp/wgtest/wg-client.json", fmt.Sprintf(`{
  "log": {"loglevel": "info"},
  "inbounds": [
    {
      "tag": "socks",
      "protocol": "socks",
      "listen": "127.0.0.1",
      "port": 10808,
      "settings": {"auth": "noauth", "udp": true},
      "sniffing": {"enabled": true, "destOverride": ["http", "tls"]}
    }
  ],
  "outbounds": [
%s,
    {"tag": "direct", "protocol": "freedom"}
  ],
  "routing": {"rules": [{"type": "field", "inboundTag": ["socks"], "outboundTag": "proxy"}]}
}`, wgOutbound))

	// ── AmneziaWG ────────────────────────────────────────────────────────────
	uapi := strings.Join([]string{
		"private_key=" + serverPriv,
		"jc=4",
		"jmin=40",
		"jmax=70",
		"s1=15",
		"s2=20",
		"h1=1",
		"h2=2",
		"h3=3",
		"h4=4",
		"listen_port=51821",
		"replace_peers=true",
		"public_key=" + clientPub,
		"allowed_ip=0.0.0.0/0",
		"endpoint=127.0.0.1:1",
		"",
	}, "\n")
	write("/tmp/wgtest/awg-server.uapi", uapi)

	awgOutbound := `{
      "tag": "proxy",
      "protocol": "wireguard",
      "settings": {
        "secretKey": "` + clientPriv + `",
        "address": ["172.16.0.2/32"],
        "peers": [
          {
            "publicKey": "` + serverPub + `",
            "endpoint": "127.0.0.1:51821",
            "allowedIPs": ["0.0.0.0/0"],
            "keepAlive": 25,
            "junkPacketCount": 4,
            "junkPacketMinSize": 40,
            "junkPacketMaxSize": 70,
            "initPacketJunkSize": 15,
            "responsePacketJunkSize": 20,
            "initPacketJunkHeader": [1],
            "responsePacketJunkHeader": [2],
            "cookiePacketJunkHeader": [3],
            "transportPacketJunkHeader": [4]
          }
        ],
        "mtu": 1420,
        "noKernelTun": true
      }
    }`
	if dump, err := os.ReadFile("/tmp/wgtest/app-awg-outbound.json"); err == nil {
		awgOutbound = patch(string(dump), clientPriv, serverPub, "127.0.0.1:51821")
		fmt.Println("amneziawg: using the app's outbound as dumped by the unit test")
	}

	write("/tmp/wgtest/awg-client.json", fmt.Sprintf(`{
  "log": {"loglevel": "info"},
  "inbounds": [
    {
      "tag": "socks",
      "protocol": "socks",
      "listen": "127.0.0.1",
      "port": 10809,
      "settings": {"auth": "noauth", "udp": true}
    }
  ],
  "outbounds": [
%s,
    {"tag": "direct", "protocol": "freedom"}
  ],
  "routing": {"rules": [{"type": "field", "inboundTag": ["socks"], "outboundTag": "proxy"}]}
}`, awgOutbound))

	fmt.Println("wrote the wireguard and amneziawg test configs")
}
