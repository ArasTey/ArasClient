# Radar Sense — On-device network atmosphere

## What it is
A lightweight, modern multi-vector probe that classifies the **current network atmosphere** and suggests a protocol strategy for RADAR VPN.

Inspired by ideas from open diagnostic projects (multi-host HTTPS/DNS/TCP/UDP reachability) but designed as a product feature, not a lab tool.

## What it measures
- DNS resolution to public resolvers
- TCP reachability on 443
- HTTPS/TLS to global and CDN targets
- Best-effort UDP/443 probes (QUIC path signal)

## Outputs
- **Atmosphere**: CLEAR · TURBULENT · HEAVY_FILTER · OFFLINE
- **Score**: 0–100
- **Fingerprint**: short hash of coarse probe outcomes (for future Smart Connect memory)
- **Protocol hint**: primary + alternatives + reason (FA summary in UI)

## Non-goals
- Does not prove DPI in a forensic sense
- Does not change VPN connection state
- Does not require root

## Code
- `com.aras.client.radar.sense.RadarSenseEngine`
- `com.aras.client.radar.sense.RadarSenseModels`
- UI: existing `DpiTestActivity` (title: Radar Sense)
