# RADAR VPN — Config Finder (Top-20 DPI + Real-oriented)

## Scope
Isolated under `com.aras.client.radar.config` and used by `RadarConfigActivity`.
Does not change VPN service, profile persistence, connection manager, or routing.

## Goals
- Prefer configs more likely to survive modern DPI (Reality, Hysteria2, TUIC, XHTTP, Amnezia-style).
- Wide multi-source discovery with source quality bonuses.
- TCP reachability on a quality-biased pool.
- Rank by combined score (DPI + latency; realPing when available).
- Expose only the **Top 20** reachable configs per search.
- One-click import of Top 20 for real core (Real Ping) testing in the main app.

## Pipeline
1. Discover public feeds from a curated, DPI-oriented source catalog (direct feeds preferred).
2. Parse supported URI schemes (VLESS, VMess, Trojan, SS, Hysteria2, TUIC, …).
3. Assign **dpiScore** (0–200) from protocol, Reality/XTLS/XHTTP markers, ports, source quality.
4. Keep a limited high-score pool (~180) for probing.
5. DNS + bounded concurrent TCP samples (public addresses only).
6. Compute **rankScore** = dpiScore × 1.4 + latency score (+ bonus if realPingMs present).
7. Sort reachable results by rankScore and keep **Top 20**.
8. User imports one config or the whole Top-20 set for real core testing.

## Real Ping
TCP is only a pre-filter. Full Real Ping (core handshake + delay) runs after import via the existing main-app RealPingWorkerService path. Ranking already prefers lower latency and can incorporate `realPingMs` when measured.

## Reliability
- One failed repository does not abort others.
- Per-source fetch/parse counts and errors are displayed.
- File sizes, tree depth and concurrent probes are bounded.
- Private / loopback / reserved ranges are never probed.
- Probe results use a short in-memory cache.
- Stopping the scan cancels the coroutine pipeline.
