package com.aras.client.ui.radar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aras.client.radar.sense.NetworkAtmosphere
import com.aras.client.radar.sense.RadarSenseEngine
import com.aras.client.radar.sense.RadarSenseReport
import com.aras.client.radar.sense.SenseProbe
import com.aras.client.ui.compose.AppTheme

/** Radar Sense UI — on-device network atmosphere scan. */
@OptIn(ExperimentalMaterial3Api::class)
class DpiTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                var running by remember { mutableStateOf(false) }
                var report by remember { mutableStateOf<RadarSenseReport?>(null) }
                var error by remember { mutableStateOf<String?>(null) }

                Scaffold(topBar = { TopAppBar(title = { Text("Radar Sense · حس رادار") }) }) { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "اسکن چندبرداری روی دستگاه: DNS، TCP، HTTPS و UDP/443. جوّ شبکه را دسته‌بندی می‌کند و استک پیشنهادی می‌دهد.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            enabled = !running,
                            onClick = { report = null; error = null; running = true }
                        ) { Text(if (report == null) "شروع Radar Sense" else "اسکن دوباره") }
                        if (running) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("در حال نمونه‌برداری از جوّ شبکه…", style = MaterialTheme.typography.bodySmall)
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        report?.let { SenseReportCard(it) }
                    }
                }

                LaunchedEffect(running) {
                    if (!running) return@LaunchedEffect
                    try { report = RadarSenseEngine.scan() }
                    catch (e: Exception) { error = "اسکن کامل نشد: " + (e.message ?: "خطای ناشناخته") }
                    finally { running = false }
                }
            }
        }
    }
}

@Composable
private fun SenseReportCard(report: RadarSenseReport) {
    val tone = when (report.atmosphere) {
        NetworkAtmosphere.CLEAR -> Color(0xFF00A86B)
        NetworkAtmosphere.TURBULENT -> Color(0xFFD4A017)
        NetworkAtmosphere.HEAVY_FILTER -> Color(0xFFE85D4C)
        NetworkAtmosphere.OFFLINE -> Color(0xFFB00020)
        NetworkAtmosphere.UNKNOWN -> MaterialTheme.colorScheme.onSurface
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("جوّ شبکه: " + when (report.atmosphere) {
                NetworkAtmosphere.CLEAR -> "باز / CLEAR"
                NetworkAtmosphere.TURBULENT -> "ناپایدار / TURBULENT"
                NetworkAtmosphere.HEAVY_FILTER -> "فیلتر سنگین / HEAVY"
                NetworkAtmosphere.OFFLINE -> "آفلاین / OFFLINE"
                NetworkAtmosphere.UNKNOWN -> "نامشخص"
            }, color = tone, style = MaterialTheme.typography.titleMedium)
            Text(report.summaryFa, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("امتیاز", style = MaterialTheme.typography.labelSmall); Text(report.score.toString(), style = MaterialTheme.typography.titleSmall) }
                Column { Text("زمان", style = MaterialTheme.typography.labelSmall); Text(report.elapsedMs.toString() + " ms", style = MaterialTheme.typography.titleSmall) }
                Column { Text("اثر انگشت", style = MaterialTheme.typography.labelSmall); Text(report.fingerprint, style = MaterialTheme.typography.titleSmall) }
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("استراتژی پیشنهادی", style = MaterialTheme.typography.titleSmall)
            Text("اصلی: " + report.hint.primary, style = MaterialTheme.typography.bodyMedium)
            Text("جایگزین: " + report.hint.alternatives.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            Text(report.hint.reason, style = MaterialTheme.typography.bodySmall)
        }
    }
    Text("جزئیات پروب‌ها", style = MaterialTheme.typography.titleSmall)
    report.probes.forEach { probe ->
        val color = if (probe.ok) Color(0xFF00A86B) else MaterialTheme.colorScheme.error
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(probe.label + " · " + probe.category.uppercase(), style = MaterialTheme.typography.titleSmall)
                Text((if (probe.ok) "OK" else "FAIL") + (probe.latencyMs?.let { " · $it ms" } ?: ""), color = color, style = MaterialTheme.typography.bodySmall)
                Text(probe.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
