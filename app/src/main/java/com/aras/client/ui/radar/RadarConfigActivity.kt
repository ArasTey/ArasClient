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

/** Isolated Config Finder screen. It does not alter VPN connection or profile management flows. */
@OptIn(ExperimentalMaterial3Api::class)
class RadarConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                var scanning by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf("برای شروع، اسکن منابع را اجرا کن.") }
                var resultFilter by remember { mutableStateOf("all") }
                var protocolFilter by remember { mutableStateOf("all") }
                var countryFilter by remember { mutableStateOf("all") }
                val results = remember { mutableStateListOf<RadarConfigCheck>() }
                val sourceReports = remember { mutableStateListOf<RadarSourceReport>() }
                var radar by remember { mutableStateOf<RadarScanView?>(null) }

                val protocols = results.map { it.candidate.protocol }.distinct().sorted()
                val countries = results.map { it.candidate.countryIso.ifBlank { "unknown" } }.distinct().sorted()
                val visibleResults = results.filter { check ->
                    val statusMatches = when (resultFilter) {
                        "reachable" -> check.tcpReachable
                        "failed" -> !check.tcpReachable
                        else -> true
                    }
                    val protocolMatches = protocolFilter == "all" || check.candidate.protocol == protocolFilter
                    val country = check.candidate.countryIso.ifBlank { "unknown" }
                    val countryMatches = countryFilter == "all" || country == countryFilter
                    statusMatches && protocolMatches && countryMatches
                }.sortedWith(compareBy<RadarConfigCheck> { !it.tcpReachable }.thenBy { it.tcpLatencyMs ?: Long.MAX_VALUE })

                Scaffold(topBar = { TopAppBar(title = { Text("رادار کانفیگ") }) }) { padding ->
                    LazyColumn(
                        Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("کشف چندمنبعی، اعتبارسنجی ساختار، DNS و دسترسی TCP", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "پاسخ TCP به معنی سالم بودن تونل نیست. برای تست واقعی، کانفیگ را به فهرست سرورها اضافه و از تست داخلی برنامه استفاده کن.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        item {
                            AndroidView(
                                factory = { context -> RadarScanView(context).also {
                                    radar = it
                                    if (scanning) it.startScan() else it.stopScan()
                                } },
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
                                        radar?.clearBlips()
                                        scanning = true
                                        message = "در حال دریافت منابع و کشف کانفیگ‌ها…"
                                    }) { Text("شروع اسکن") }
                                    OutlinedButton(enabled = scanning, onClick = { scanning = false }) { Text("توقف") }
                                }
                                if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(message, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Metric("کشف‌شده", results.size.toString())
                                    Metric("TCP پاسخ‌گو", results.count { it.tcpReachable }.toString(), Color(0xFF00A86B))
                                    Metric("منابع سالم", sourceReports.count { it.error == null }.toString())
                                }
                            }
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("وضعیت دسترسی", style = MaterialTheme.typography.titleSmall)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item { FilterChip(selected = resultFilter == "all", onClick = { resultFilter = "all" }, label = { Text("همه") }) }
                                    item { FilterChip(selected = resultFilter == "reachable", onClick = { resultFilter = "reachable" }, label = { Text("TCP پاسخ‌گو") }) }
                                    item { FilterChip(selected = resultFilter == "failed", onClick = { resultFilter = "failed" }, label = { Text("ناموفق") }) }
                                }
                            }
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("پروتکل", style = MaterialTheme.typography.titleSmall)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item { FilterChip(selected = protocolFilter == "all", onClick = { protocolFilter = "all" }, label = { Text("همه") }) }
                                    items(protocols) { protocol ->
                                        FilterChip(selected = protocolFilter == protocol, onClick = { protocolFilter = protocol }, label = { Text(protocol.uppercase()) })
                                    }
                                }
                            }
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("کشور سرور", style = MaterialTheme.typography.titleSmall)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item { FilterChip(selected = countryFilter == "all", onClick = { countryFilter = "all" }, label = { Text("همه کشورها") }) }
                                    items(countries) { country ->
                                        val label = if (country == "unknown") "نامشخص" else country
                                        FilterChip(selected = countryFilter == country, onClick = { countryFilter = country }, label = { Text(label) })
                                    }
                                }
                            }
                        }
                        item { Text("نتایج (" + visibleResults.size + ")", style = MaterialTheme.typography.titleMedium) }
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
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(report.source, style = MaterialTheme.typography.titleSmall)
                                        Text("فایل: " + report.fetchedFiles + " · کانفیگ یکتا: " + report.parsedCandidates, style = MaterialTheme.typography.bodySmall)
                                        if (!report.error.isNullOrBlank()) {
                                            Text(report.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
                        message = "در حال بررسی فهرست مخازن و فیدهای عمومی…"
                        val report = RadarConfigDiscovery().discover()
                        sourceReports.clear()
                        sourceReports.addAll(report.sources)
                        val candidates = report.candidates.take(MAX_CANDIDATES)
                        if (candidates.isEmpty()) {
                            message = "کانفیگی استخراج نشد. گزارش منابع را بررسی کن؛ ممکن است GitHub محدود یا منبع موقتاً قطع باشد."
                        } else {
                            message = "تشخیص کشور و آماده‌سازی " + candidates.size + " کانفیگ…"
                            GeoIPResolver.refresh(candidates.map { it.host }.distinct())
                            val enriched = candidates.map { it.copy(countryIso = GeoIPResolver.cached(it.host)) }
                            var checked = 0
                            var reachable = 0
                            for (batch in enriched.chunked(PROBE_BATCH_SIZE)) {
                                if (!scanning) break
                                val batchResults = coroutineScope {
                                    batch.map { candidate -> async(Dispatchers.IO) {
                                        RadarConfigProbeCache.get(candidate)
                                            ?: RadarConfigProbe.check(candidate).also { RadarConfigProbeCache.put(it) }
                                    } }.map { it.await() }
                                }
                                batchResults.forEach { result ->
                                    if (result.tcpReachable) reachable++
                                    radar?.addTestedCandidate(result.tcpLatencyMs ?: -1L)
                                    results.add(result)
                                    checked++
                                }
                                message = "بررسی " + checked + "/" + enriched.size +
                                    " · پاسخ TCP: " + reachable + " · تونل واقعی هنوز تأیید نشده"
                            }
                            if (scanning) message = "پایان اسکن: " + checked + " مورد بررسی شد؛ " +
                                reachable + " مورد فقط از نظر TCP پاسخ‌گو بودند."
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
            RadarProbeStatus.PRIVATE_ADDRESS -> Color(0xFFB8860B)
            else -> MaterialTheme.colorScheme.error
        }
        val flag = CountryResolver.flagForCountry(candidate.countryIso.ifBlank { candidate.remark })
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text((if (flag.isBlank()) "" else flag + " ") +
                    candidate.remark.ifBlank { "کانفیگ شماره " + number }, style = MaterialTheme.typography.titleSmall)
                Text(candidate.protocol.uppercase() + " · " + candidate.host + ":" + candidate.port)
                if (!check.resolvedIp.isNullOrBlank()) Text("IP: " + check.resolvedIp, style = MaterialTheme.typography.bodySmall)
                Text("منبع: " + candidate.source, style = MaterialTheme.typography.bodySmall)
                Text(
                    check.detail +
                        (check.dnsLatencyMs?.let { " · DNS " + it + " ms" } ?: "") +
                        (check.tcpLatencyMs?.let { " · TCP " + it + " ms" } ?: ""),
                    color = color, style = MaterialTheme.typography.bodySmall
                )
                if (check.tcpReachable) Button(onClick = onImport) { Text("افزودن به سرورها برای تست واقعی") }
            }
        }
    }

    private companion object {
        const val MAX_CANDIDATES = 600
        const val PROBE_BATCH_SIZE = 8
    }
}
