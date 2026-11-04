#!/usr/bin/env bash
set -euo pipefail

required=(
  app/src/main/java/com/aras/client/handler/NpvConfigImporter.kt
  app/src/main/java/com/aras/client/handler/npv/NpvCrypto.kt
  app/src/main/java/com/aras/client/radar/config/RadarConfigDiscovery.kt
  app/src/main/java/com/aras/client/radar/config/RadarConfigParser.kt
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
grep -q 'menu_item_import_npv_config' app/src/main/java/com/aras/client/ui/main/MainImportMenu.kt
grep -q 'NPVS' app/src/main/java/com/aras/client/handler/NpvConfigImporter.kt
grep -q 'Snowflake' docs/SLIPNET_TOR_DNS_INTEGRATION.md

if [[ ! -f app/src/main/java/com/aras/client/tor/TorRuntimeController.kt ]]; then
  echo "::warning::Tor native runtime is not integrated yet; Tor end-to-end checks are intentionally not reported as passing."
fi

echo "Feature source contracts passed."
