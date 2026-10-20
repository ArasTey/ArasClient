// An AmneziaWG server for the end-to-end check.
//
// The core's WireGuard *inbound* cannot serve as the AWG side of the check: it
// writes the plain WireGuard UAPI and never applies the obfuscation
// parameters, so an AWG client cannot complete a handshake with it. This
// program is the other half — a real amneziawg-go device with the junk
// parameters applied and a TUN that only reports what it receives.
//
// What it proves: the parameters the app puts on the peer make the core's
// outbound complete a handshake with a server that has the same parameters, and
// carry a TCP segment through the tunnel.
//
// Not part of the app build. See ../README.md.
package main

import (
	"fmt"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
)

// fakeTun is the TUN of the server: it never produces packets, it only reports
// the ones the device decrypts.
type fakeTun struct {
	events   chan tun.Event
	closed   chan struct{}
	closeOne sync.Once
	mu       sync.Mutex
	packets  int
}

func newFakeTun() *fakeTun {
	return &fakeTun{
		events: make(chan tun.Event, 4),
		closed: make(chan struct{}),
	}
}

func (t *fakeTun) File() *os.File { return nil }

func (t *fakeTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	<-t.closed
	return 0, os.ErrClosed
}

func (t *fakeTun) Write(bufs [][]byte, offset int) (int, error) {
	for _, buf := range bufs {
		if len(buf) <= offset {
			continue
		}
		packet := buf[offset:]
		t.mu.Lock()
		t.packets++
		count := t.packets
		t.mu.Unlock()
		describe(packet)
		if count == 1 {
			fmt.Println("SERVER: first decrypted packet arrived")
		}
	}
	return len(bufs), nil
}

// describe prints what the packet carries: its IPv4 header and, for TCP, the
// flags — enough to tell a tunnel that carries traffic from one that does not.
func describe(packet []byte) {
	if len(packet) < 20 {
		fmt.Printf("SERVER: short packet (%d bytes)\n", len(packet))
		return
	}
	version := packet[0] >> 4
	if version != 4 {
		fmt.Printf("SERVER: packet with ip version %d\n", version)
		return
	}
	headerLen := int(packet[0]&0x0f) * 4
	protocol := packet[9]
	source := fmt.Sprintf("%d.%d.%d.%d", packet[12], packet[13], packet[14], packet[15])
	destination := fmt.Sprintf("%d.%d.%d.%d", packet[16], packet[17], packet[18], packet[19])
	if protocol == 6 && len(packet) >= headerLen+14 {
		flags := packet[headerLen+13]
		fmt.Printf("SERVER: TCP %s -> %s flags=0x%02x\n", source, destination, flags)
		return
	}
	fmt.Printf("SERVER: proto=%d %s -> %s\n", protocol, source, destination)
}

func (t *fakeTun) MTU() (int, error) { return 1420, nil }

func (t *fakeTun) Name() (string, error) { return "awg0", nil }

func (t *fakeTun) Events() <-chan tun.Event { return t.events }

func (t *fakeTun) Close() error {
	t.closeOne.Do(func() {
		close(t.events)
		close(t.closed)
	})
	return nil
}

func (t *fakeTun) BatchSize() int { return 1 }

// count returns the number of packets the device handed to the TUN.
func (t *fakeTun) count() int {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.packets
}

var _ tun.Device = (*fakeTun)(nil)

func main() {
	if len(os.Args) < 2 {
		fmt.Println("usage: awgserver <uapi-file>")
		os.Exit(2)
	}
	uapi, err := os.ReadFile(os.Args[1])
	if err != nil {
		fmt.Println("cannot read uapi file:", err)
		os.Exit(1)
	}

	logger := &device.Logger{
		Verbosef: func(format string, args ...any) { fmt.Printf("SERVER: "+format+"\n", args...) },
		Errorf:   func(format string, args ...any) { fmt.Printf("SERVER: "+format+"\n", args...) },
	}

	tunDevice := newFakeTun()
	dev := device.NewDevice(tunDevice, conn.NewDefaultBind(), logger)
	if err := dev.IpcSet(string(uapi)); err != nil {
		fmt.Println("SERVER: ipc set failed:", err)
		os.Exit(1)
	}
	if err := dev.Up(); err != nil {
		fmt.Println("SERVER: up failed:", err)
		os.Exit(1)
	}
	fmt.Println("SERVER: AmneziaWG tunnel up")

	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)

	deadline := time.After(60 * time.Second)
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-signals:
			dev.Close()
			return
		case <-deadline:
			if tunDevice.count() > 0 {
				fmt.Println("SERVER: OK — traffic carried through the AmneziaWG tunnel")
				dev.Close()
				os.Exit(0)
			}
			fmt.Println("SERVER: FAILED — no traffic in 60s")
			dev.Close()
			os.Exit(1)
		case <-ticker.C:
			if tunDevice.count() > 0 {
				fmt.Println("SERVER: OK — traffic carried through the AmneziaWG tunnel")
				dev.Close()
				os.Exit(0)
			}
		}
	}
}
