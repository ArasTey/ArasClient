#!/usr/bin/env bash
set -euo pipefail

required=(
  app/src/main/java/com/aras/client/handler/NpvConfigImporter.kt
  app/src/main/java/com/aras/client/handler/npv/NpvCrypto.kt
  app/src/main/java/com/aras/client/radar/config/RadarConfigDiscovery.kt
  app/src/main/java/com/aras/client/radar/config/RadarConfigParser.kt
  app/src/main/java/com/aras/client/radar/config/RadarConfigProbe.kt
  app/src/main/java/com/aras/client/radar/sense/RadarSenseEngine.kt
  app/src/main/java/com/aras/client/ui/map/ConnectionMapActivity.kt
  app/src/main/java/com/aras/client/ui/monitor/LiveMonitorActivity.kt
  app/src/main/java/com/aras/client/ui/zx/ZxUiActivity.kt
  app/src/main/java/com/aras/client/ui/repair/ConfigRepairActivity.kt
  app/src/main/java/com/aras/client/core/aether/AetherCore.kt
  app/src/main/java/com/aras/client/ui/server/ServerAetherActivity.kt
  app/src/main/assets/npv/gen2_tables.bin.z.b64
  app/src/main/assets/npv/legacy_whitebox_tables.json
  app/src/test/java/com/aras/client/handler/NpvConfigImporterTest.kt
  app/src/test/java/com/aras/client/radar/config/RadarConfigParserTest.kt
  app/src/main/java/com/aras/client/ui/dns/DnsChangerStore.kt
  app/src/main/java/com/aras/client/ui/dns/DnsScannerEngine.kt
  app/src/main/java/com/aras/client/ui/dns/DnsScannerActivity.kt
  app/src/test/java/com/aras/client/ui/dns/DnsScannerEngineTest.kt
  docs/SLIPNET_TOR_DNS_INTEGRATION.md
)
for path in "${required[@]}"; do
  test -s "$path" || { echo "::error::Missing required feature file: $path"; exit 1; }
done

grep -q 'DnsScannerActivity' app/src/main/AndroidManifest.xml
grep -q 'DNS SCANNER & RESOLVER' app/src/main/java/com/aras/client/ui/main/MainImportMenu.kt
grep -q 'Psiphon CDN' app/src/main/java/com/aras/client/ui/main/MainImportMenu.kt
grep -q 'presetPsiphonCdn' app/src/main/java/com/aras/client/ui/server/ServerAetherActivity.kt
grep -q 'menu_item_import_npv_config' app/src/main/java/com/aras/client/ui/main/MainImportMenu.kt
grep -q 'NPVS' app/src/main/java/com/aras/client/handler/NpvConfigImporter.kt
grep -q 'Snowflake' docs/SLIPNET_TOR_DNS_INTEGRATION.md

if [[ ! -f app/src/main/java/com/aras/client/tor/TorRuntimeController.kt ]]; then
  echo "::warning::Tor native runtime is not integrated yet; Tor end-to-end checks are intentionally not reported as passing."
fi

echo "Feature source contracts passed."
