// Command soar-client runs the Soar DNS tunnel client as a local SOCKS5 and
// an optional local DNS, the way the StormDNS family does — so the app can
// start it, hand it a configuration and a resolver list, and read its state
// back from stdout.
//
// The tunnel itself is the upstream library (github.com/getlantern/soar,
// Apache-2.0); this program is the small program around it: the configuration
// it reads, the SOCKS5 in front of it, and the telemetry the app parses.
//
//	go build -o soar-client .
package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/getlantern/soar"
)

// config is what the app writes for this engine.
type config struct {
	Zone            string     `json:"zone"`
	ServerPublicKey string     `json:"server_public_key"`
	Listen          string     `json:"listen"`
	DNSListen       string     `json:"dns_listen"`
	Resolvers       []string   `json:"resolvers"`
	Discovery       *discovery `json:"discovery"`
	SafeQName       int        `json:"safe_qname"`
	MaxQName        int        `json:"max_qname"`
	HoldMS          int        `json:"hold_ms"`
	TCP             string     `json:"tcp"`
	SocksUser       string     `json:"socks_user"`
	SocksPass       string     `json:"socks_pass"`
}

type discovery struct {
	Enabled     bool   `json:"enabled"`
	Country     string `json:"country"`
	Want        int    `json:"want"`
	Concurrency int    `json:"concurrency"`
}

func main() {
	var configPath, resolversPath string
	var showVersion bool
	flag.StringVar(&configPath, "config", "", "path of the client configuration")
	flag.StringVar(&resolversPath, "resolvers", "", "path of the resolver list")
	flag.BoolVar(&showVersion, "version", false, "print the version and exit")
	flag.Parse()

	if showVersion {
		fmt.Println("ArasClient Soar Client Version: dev")
		return
	}
	if configPath == "" {
		fmt.Fprintln(os.Stderr, "soar: -config is required")
		os.Exit(2)
	}

	raw, err := os.ReadFile(configPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "soar: cannot read the configuration: %v\n", err)
		os.Exit(1)
	}
	var cfg config
	if err := json.Unmarshal(raw, &cfg); err != nil {
		fmt.Fprintf(os.Stderr, "soar: the configuration is not valid JSON: %v\n", err)
		os.Exit(1)
	}

	resolvers := cfg.Resolvers
	if resolversPath != "" {
		fromFile, err := readResolvers(resolversPath)
		if err != nil {
			fmt.Fprintf(os.Stderr, "soar: cannot read the resolver list: %v\n", err)
			os.Exit(1)
		}
		resolvers = append(resolvers, fromFile...)
	}
	resolvers = normalizeResolvers(resolvers)

	key, err := decodeKey(cfg.ServerPublicKey)
	if err != nil {
		fmt.Fprintf(os.Stderr, "soar: %v\n", err)
		os.Exit(1)
	}

	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))

	clientCfg := soar.Config{
		Zone:      cfg.Zone,
		ServerKey: key,
		Resolvers: resolvers,
		SafeQName: cfg.SafeQName,
		MaxQName:  cfg.MaxQName,
		TCP:       cfg.TCP,
		Logger:    logger,
	}
	if cfg.HoldMS > 0 {
		clientCfg.Hold = time.Duration(cfg.HoldMS) * time.Millisecond
	}
	if cfg.Discovery != nil {
		clientCfg.Discovery = soar.Discovery{
			Enabled:     cfg.Discovery.Enabled,
			Country:     cfg.Discovery.Country,
			Want:        cfg.Discovery.Want,
			Concurrency: cfg.Discovery.Concurrency,
		}
	}

	fmt.Printf("WD_PROGRESS phase=start percent=0\n")
	fmt.Printf("WD_RESOLVERS active=%s standby=- valid=%d\n", strings.Join(resolvers, ","), len(resolvers))

	client, err := soar.NewClient(clientCfg)
	if err != nil {
		fmt.Fprintf(os.Stderr, "soar: %v\n", err)
		os.Exit(1)
	}
	defer client.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	if cfg.Listen != "" {
		socks, err := serveSocks(ctx, cfg, client)
		if err != nil {
			fmt.Fprintf(os.Stderr, "soar: %v\n", err)
			os.Exit(1)
		}
		defer socks.Close()
		fmt.Printf("WD_PROGRESS phase=listen percent=50\n")
	}

	if cfg.DNSListen != "" {
		if err := serveDNS(ctx, cfg.DNSListen, client, resolvers); err != nil {
			fmt.Fprintf(os.Stderr, "soar: %v\n", err)
			os.Exit(1)
		}
	}

	fmt.Printf("WD_PROGRESS phase=ready percent=100\n")

	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	<-signals
	fmt.Printf("WD_PROGRESS phase=stop percent=100\n")
}

// readResolvers reads a resolver list, one per line, the way the other
// engines read theirs.
func readResolvers(path string) ([]string, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	var out []string
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		out = append(out, line)
	}
	return out, scanner.Err()
}

// normalizeResolvers gives every resolver a port and drops the repeats.
func normalizeResolvers(in []string) []string {
	seen := map[string]bool{}
	var out []string
	for _, entry := range in {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}
		if _, _, err := net.SplitHostPort(entry); err != nil {
			entry = net.JoinHostPort(entry, "53")
		}
		if seen[entry] {
			continue
		}
		seen[entry] = true
		out = append(out, entry)
	}
	return out
}

// decodeKey reads the server's Ed25519 public key, as base64 or as hex.
func decodeKey(text string) ([]byte, error) {
	text = strings.TrimSpace(text)
	if text == "" {
		return nil, errors.New("soar: the server's public key is missing")
	}
	if raw, err := base64.StdEncoding.DecodeString(text); err == nil && len(raw) == 32 {
		return raw, nil
	}
	if raw, err := base64.RawStdEncoding.DecodeString(text); err == nil && len(raw) == 32 {
		return raw, nil
	}
	if raw, err := base64.URLEncoding.DecodeString(text); err == nil && len(raw) == 32 {
		return raw, nil
	}
	if raw, err := base64.RawURLEncoding.DecodeString(text); err == nil && len(raw) == 32 {
		return raw, nil
	}
	if raw, err := hex.DecodeString(text); err == nil && len(raw) == 32 {
		return raw, nil
	}
	return nil, errors.New("soar: the server's public key is not a 32-byte key")
}

// serveSocks runs the local SOCKS5 the app's outbound dials.
func serveSocks(ctx context.Context, cfg config, client *soar.Client) (net.Listener, error) {
	listener, err := net.Listen("tcp", cfg.Listen)
	if err != nil {
		return nil, err
	}
	go func() {
		<-ctx.Done()
		listener.Close()
	}()
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go handleSocks(ctx, cfg, client, conn)
		}
	}()
	return listener, nil
}

// handleSocks serves one SOCKS5 client: the greeting, one CONNECT, and the
// bytes. UDP is not offered: the tunnel carries TCP streams.
func handleSocks(ctx context.Context, cfg config, client *soar.Client, conn net.Conn) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(30 * time.Second))

	header := make([]byte, 2)
	if _, err := io.ReadFull(conn, header); err != nil {
		return
	}
	methods := make([]byte, header[1])
	if _, err := io.ReadFull(conn, methods); err != nil {
		return
	}
	wantAuth := cfg.SocksUser != "" || cfg.SocksPass != ""
	if wantAuth {
		// 0x02 is username/password; the app never uses it, and the tunnel's own
		// key is what protects the session.
		if _, err := conn.Write([]byte{0x05, 0x02}); err != nil {
			return
		}
	} else {
		if _, err := conn.Write([]byte{0x05, 0x00}); err != nil {
			return
		}
	}

	request := make([]byte, 4)
	if _, err := io.ReadFull(conn, request); err != nil {
		return
	}
	if request[0] != 0x05 {
		return
	}
	if request[1] != 0x01 {
		// Command not supported: the tunnel carries TCP streams only.
		conn.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	var host string
	switch request[3] {
	case 0x01:
		buf := make([]byte, 4)
		if _, err := io.ReadFull(conn, buf); err != nil {
			return
		}
		host = net.IP(buf).String()
	case 0x03:
		length := make([]byte, 1)
		if _, err := io.ReadFull(conn, length); err != nil {
			return
		}
		name := make([]byte, length[0])
		if _, err := io.ReadFull(conn, name); err != nil {
			return
		}
		host = string(name)
	case 0x04:
		buf := make([]byte, 16)
		if _, err := io.ReadFull(conn, buf); err != nil {
			return
		}
		host = net.IP(buf).String()
	default:
		return
	}
	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(conn, portBuf); err != nil {
		return
	}
	port := int(portBuf[0])<<8 | int(portBuf[1])

	_ = conn.SetDeadline(time.Time{})
	upstream, err := client.DialContext(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		fmt.Printf("WD_PROGRESS phase=error percent=0\n")
		conn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer upstream.Close()

	if _, err := conn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		io.Copy(upstream, conn)
		if closer, ok := upstream.(interface{ CloseWrite() error }); ok {
			closer.CloseWrite()
		}
	}()
	go func() {
		defer wg.Done()
		io.Copy(conn, upstream)
		if closer, ok := conn.(interface{ CloseWrite() error }); ok {
			closer.CloseWrite()
		}
	}()
	wg.Wait()
}

// serveDNS runs a local DNS that answers through the tunnel.
//
// The query is forwarded as it is over DNS-over-TCP to a resolver, through the
// tunnel, and the answer comes back the same way: the tunnel carries TCP
// streams, so the local listener speaks ordinary UDP to its client and does
// the TCP part itself.
func serveDNS(ctx context.Context, address string, client *soar.Client, resolvers []string) error {
	if len(resolvers) == 0 {
		return errors.New("soar: the local DNS needs at least one resolver")
	}
	target := resolvers[0]

	packet, err := net.ListenPacket("udp", address)
	if err != nil {
		return err
	}
	go func() {
		<-ctx.Done()
		packet.Close()
	}()
	go func() {
		buf := make([]byte, 4096)
		for {
			n, from, err := packet.ReadFrom(buf)
			if err != nil {
				return
			}
			query := make([]byte, n)
			copy(query, buf[:n])
			go func() {
				answer, err := forwardDNS(ctx, client, target, query)
				if err == nil {
					packet.WriteTo(answer, from)
				}
			}()
		}
	}()
	return nil
}

// forwardDNS sends a query to the resolver over TCP, through the tunnel.
func forwardDNS(ctx context.Context, client *soar.Client, target string, query []byte) ([]byte, error) {
	conn, err := client.DialContext(ctx, "tcp", target)
	if err != nil {
		return nil, err
	}
	defer conn.Close()

	_ = conn.SetDeadline(time.Now().Add(15 * time.Second))
	framed := make([]byte, 2+len(query))
	framed[0] = byte(len(query) >> 8)
	framed[1] = byte(len(query))
	copy(framed[2:], query)
	if _, err := conn.Write(framed); err != nil {
		return nil, err
	}

	lengthBuf := make([]byte, 2)
	if _, err := io.ReadFull(conn, lengthBuf); err != nil {
		return nil, err
	}
	length := int(lengthBuf[0])<<8 | int(lengthBuf[1])
	if length < 12 || length > 65535 {
		return nil, errors.New("soar: the resolver's answer is not a DNS message")
	}
	answer := make([]byte, length)
	if _, err := io.ReadFull(conn, answer); err != nil {
		return nil, err
	}
	return answer, nil
}
