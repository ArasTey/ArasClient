package com.aras.client.ui.radar

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aras.client.radar.config.RadarConfigCheck
import com.aras.client.radar.config.RadarConfigDiscovery
import com.aras.client.radar.config.RadarConfigProbe
import com.aras.client.radar.config.RadarConfigProbeCache
import com.aras.client.radar.config.RadarProbeStatus
import com.aras.client.radar.config.RadarSourceReport
import com.aras.client.radar.ui.RadarScanView
import com.aras.client.ui.compose.AppTheme
import com.aras.client.ui.main.MainActivity
import com.aras.client.util.CountryResolver
import com.aras.client.util.GeoIPResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Config Finder focused on quality over quantity.
 * Pipeline: discover → DPI-score filter → TCP probe → rank → keep Top-20 only.
 * Real core test stays in the main app after import (isolation preserved).
 */
@OptIn(ExperimentalMaterial3Api::class)
class RadarConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                var scanning by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf("اسکن هوشمند: فقط بهترین کانفیگ‌های ضد DPI انتخاب می‌شوند.") }
                var resultFilter by remember { mutableStateOf("reachable") }
                var protocolFilter by remember { mutableStateOf("all") }
                var countryFilter by remember { mutableStateOf("all") }
                val results = remember { mutableStateListOf<RadarConfigCheck>() }
                val sourceReports = remember { mutableStateListOf<RadarSourceReport>() }
                var radar by remember { mutableStateOf<RadarScanView?>(null) }
                var top20Ready by remember { mutableStateOf(false) }

                val protocols = results.map { it.candidate.protocol }.distinct().sorted()
                val countries = results.map { it.candidate.countryIso.ifBlank { "unknown" } }.distinct().sorted()

                val visibleResults = results.filter { check ->
                    val statusMatches = when (resultFilter) {
                        "reachable" -> check.isImportable
                        "failed" -> !check.isImportable
                        else -> true
                    }
                    val protocolMatches = protocolFilter == "all" || check.candidate.protocol == protocolFilter
                    val country = check.candidate.countryIso.ifBlank { "unknown" }
                    val countryMatches = countryFilter == "all" || country == countryFilter
                    statusMatches && protocolMatches && countryMatches
                }.sortedByDescending { it.rankScore }

                Scaffold(topBar = { TopAppBar(title = { Text("رادار کانفیگ · Top 20") }) }) { padding ->
                    LazyColumn(
                        Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "کشف چندمنبعی + امتیاز ضد DPI + تست TCP → فقط ۲۰ تای برتر",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    "امتیاز Reality / Hysteria2 / TUIC / XHTTP بالاتر است. TCP فقط برای پروتکل‌های TCP بررسی می‌شود؛ کانفیگ‌های UDP/QUIC پس از بررسی DNS برای تست واقعی هسته نگه داشته می‌شوند.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        item {
                            AndroidView(
                                factory = { context ->
                                    RadarScanView(context).also {
                                        radar = it
                                        if (scanning) it.startScan() else it.stopScan()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(230.dp),
                                update = { if (scanning) it.startScan() else it.stopScan() }
                            )
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(enabled = !scanning, onClick = {
                                        results.clear()
                                        sourceReports.clear()
                                        top20Ready = false
                                        radar?.clearBlips()
                                        scanning = true
                                        message = "در حال دریافت منابع با اولویت ضد DPI…"
                                    }) { Text("شروع اسکن هوشمند") }
                                    OutlinedButton(enabled = scanning, onClick = { scanning = false }) {
                                        Text("توقف")
                                    }
                                }
                                if (top20Ready && results.any { it.isImportable }) {
                                    Button(
                                        onClick = { importTop20(results.filter { it.isImportable }.take(TOP_N)) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("افزودن ${minOf(TOP_N, results.count { it.isImportable })}تای برتر برای تست هسته")
                                    }
                                }
                                if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(message, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Metric("کشف‌شده", results.size.toString())
                                    Metric(
                                        "TCP زنده",
                                        results.count { it.tcpReachable }.toString(),
                                        Color(0xFF00A86B)
                                    )
                                    Metric("منابع سالم", sourceReports.count { it.error == null }.toString())
                                }
                            }
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("وضعیت", style = MaterialTheme.typography.titleSmall)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item {
                                        FilterChip(
                                            selected = resultFilter == "reachable",
                                            onClick = { resultFilter = "reachable" },
                                            label = { Text("فقط پاسخ‌گو") }
                                        )
                                    }
                                    item {
                                        FilterChip(
                                            selected = resultFilter == "all",
                                            onClick = { resultFilter = "all" },
                                            label = { Text("همه") }
                                        )
                                    }
                                    item {
                                        FilterChip(
                                            selected = resultFilter == "failed",
                                            onClick = { resultFilter = "failed" },
                                            label = { Text("ناموفق") }
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("پروتکل", style = MaterialTheme.typography.titleSmall)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item {
                                        FilterChip(
                                            selected = protocolFilter == "all",
                                            onClick = { protocolFilter = "all" },
                                            label = { Text("همه") }
                                        )
                                    }
                                    items(protocols) { protocol ->
                                        FilterChip(
                                            selected = protocolFilter == protocol,
                                            onClick = { protocolFilter = protocol },
                                            label = { Text(protocol.uppercase()) }
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("کشور سرور", style = MaterialTheme.typography.titleSmall)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item {
                                        FilterChip(
                                            selected = countryFilter == "all",
                                            onClick = { countryFilter = "all" },
                                            label = { Text("همه کشورها") }
                                        )
                                    }
                                    items(countries) { country ->
                                        val label = if (country == "unknown") "نامشخص" else country
                                        FilterChip(
                                            selected = countryFilter == country,
                                            onClick = { countryFilter = country },
                                            label = { Text(label) }
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            Text(
                                "۲۰ تای برتر (" + visibleResults.size + ")",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        itemsIndexed(visibleResults, key = { _, check -> check.candidate.identity }) { index, check ->
                            CandidateCard(check, index + 1) {
                                startActivity(Intent(this@RadarConfigActivity, MainActivity::class.java).apply {
                                    action = "com.aras.client.action.IMPORT_RADAR_CONFIG"
                                    putExtra("radar_config", check.candidate.uri)
                                })
                                finish()
                            }
                        }
                        if (sourceReports.isNotEmpty()) {
                            item { Text("گزارش سلامت منابع", style = MaterialTheme.typography.titleMedium) }
                            items(sourceReports, key = { it.source }) { report ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(
                                        Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text(report.source, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "فایل: ${report.fetchedFiles} · کانفیگ یکتا: ${report.parsedCandidates}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        if (!report.error.isNullOrBlank()) {
                                            Text(
                                                report.error,
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(scanning) {
                    if (!scanning) return@LaunchedEffect
                    try {
                        message = "در حال بررسی مخازن با اولویت Reality / Hysteria2 / TUIC…"
                        val report = RadarConfigDiscovery().discover()
                        sourceReports.clear()
                        sourceReports.addAll(report.sources)

                        val preFiltered = report.candidates
                            .sortedByDescending { it.dpiScore }
                            .take(MAX_CANDIDATES_FOR_PROBE)

                        if (preFiltered.isEmpty()) {
                            message = "کانفیگی استخراج نشد. ممکن است GitHub محدود یا منابع موقتاً قطع باشند."
                        } else {
                            message = "آماده‌سازی ${preFiltered.size} کاندیدای با امتیاز بالا…"
                            GeoIPResolver.refresh(preFiltered.map { it.host }.distinct())
                            val enriched = preFiltered.map {
                                it.copy(countryIso = GeoIPResolver.cached(it.host))
                            }

                            val probed = mutableListOf<RadarConfigCheck>()
                            var checked = 0
                            var reachable = 0

                            for (batch in enriched.chunked(PROBE_BATCH_SIZE)) {
                                if (!scanning) break
                                val batchResults = coroutineScope {
                                    batch.map { candidate ->
                                        async(Dispatchers.IO) {
                                            RadarConfigProbeCache.get(candidate)
                                                ?: RadarConfigProbe.check(candidate)
                                                    .also { RadarConfigProbeCache.put(it) }
                                        }
                                    }.map { it.await() }
                                }
                                batchResults.forEach { result ->
                                    val ranked = result.copy(
                                        rankScore = computeRankScore(result)
                                    )
                                    if (ranked.tcpReachable) reachable++
                                    radar?.addTestedCandidate(ranked.tcpLatencyMs ?: -1L)
                                    probed.add(ranked)
                                    checked++
                                }
                                message = "بررسی $checked/${enriched.size} · TCP زنده: $reachable"
                            }

                            val top = probed
                                .filter { it.isImportable }
                                .sortedByDescending { it.rankScore }
                                .take(TOP_N)

                            results.clear()
                            results.addAll(top)
                            top20Ready = top.isNotEmpty()

                            if (scanning) {
                                message = if (top.isEmpty()) {
                                    "هیچ گزینه قابل تست پیدا نشد؛ گزارش منابع را بررسی کن یا شبکه را تغییر بده."
                                } else {
                                    "۲۰ تای برتر انتخاب شد (${top.size} مورد). می‌تونی همه را برای تست هسته اضافه کنی."
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        message = "خطا در Config Finder: " + (error.message ?: "خطای ناشناخته")
                    } finally {
                        scanning = false
                        radar?.stopScan()
                    }
                }
            }
        }
    }

    private fun importTop20(top: List<RadarConfigCheck>) {
        if (top.isEmpty()) return
        top.forEachIndexed { index, check ->
            val intent = Intent(this, MainActivity::class.java).apply {
                action = "com.aras.client.action.IMPORT_RADAR_CONFIG"
                putExtra("radar_config", check.candidate.uri)
                putExtra("radar_batch_index", index)
                putExtra("radar_batch_total", top.size)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }
        finish()
    }

    private fun computeRankScore(check: RadarConfigCheck): Double {
        if (!check.tcpReachable) return 0.0
        val dpi = check.candidate.dpiScore.toDouble()
        val latency = (check.realPingMs ?: check.tcpLatencyMs ?: 9999L).toDouble().coerceAtLeast(1.0)
        val latencyScore = (1000.0 / latency).coerceIn(0.0, 100.0)
        val realBonus = if (check.realPingMs != null && check.realPingMs > 0) 25.0 else 0.0
        return dpi * 1.4 + latencyScore + realBonus
    }

    @Composable
    private fun Metric(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleLarge, color = color)
        }
    }

    @Composable
    private fun CandidateCard(check: RadarConfigCheck, number: Int, onImport: () -> Unit) {
        val candidate = check.candidate
        val color = when (check.status) {
            RadarProbeStatus.TCP_REACHABLE -> Color(0xFF00A86B)
            RadarProbeStatus.CORE_TEST_REQUIRED -> Color(0xFFB8860B)
            RadarProbeStatus.PRIVATE_ADDRESS -> Color(0xFFB8860B)
            else -> MaterialTheme.colorScheme.error
        }
        val flag = CountryResolver.flagForCountry(candidate.countryIso.ifBlank { candidate.remark })
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    (if (flag.isBlank()) "" else "$flag ") +
                        candidate.remark.ifBlank { "کانفیگ شماره $number" },
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    candidate.protocol.uppercase() +
                        " · " + candidate.host + ":" + candidate.port +
                        " · امتیاز DPI: " + candidate.dpiScore +
                        " · رتبه: " + "%.0f".format(check.rankScore)
                )
                if (!check.resolvedIp.isNullOrBlank()) {
                    Text("IP: " + check.resolvedIp, style = MaterialTheme.typography.bodySmall)
                }
                Text("منبع: " + candidate.source, style = MaterialTheme.typography.bodySmall)
                Text(
                    check.detail +
                        (check.dnsLatencyMs?.let { " · DNS $it ms" } ?: "") +
                        (check.tcpLatencyMs?.let { " · TCP $it ms" } ?: ""),
                    color = color,
                    style = MaterialTheme.typography.bodySmall
                )
                if (check.isImportable) {
                    Button(onClick = onImport) { Text("افزودن برای تست واقعی هسته") }
                }
            }
        }
    }

    private companion object {
        const val MAX_CANDIDATES_FOR_PROBE = 180
        const val PROBE_BATCH_SIZE = 10
        const val TOP_N = 20
    }
}
