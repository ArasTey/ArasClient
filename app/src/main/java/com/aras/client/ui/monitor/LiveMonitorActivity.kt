package com.aras.client.ui.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import javax.crypto.Cipher
import java.util.Locale

class LiveMonitorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LiveMonitorScreen(onBack = { finish() })
            }
        }
    }
}

private data class MonitorSample(
    val cpu: Float,
    val ramMb: Float,
    val battery: Int,
    val batteryCurrentMa: Int?,
    val batteryTempC: Float?,
    val thermalStatus: String,
    val thermalHeadroom: Float?,
    val threads: Int,
    val uptime: String,
    val cryptoProvider: String
)

@Composable
private fun LiveMonitorScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var sample by remember { mutableStateOf<MonitorSample?>(null) }
    val cpuHistory = remember { mutableStateListOf<Float>() }
    val ramHistory = remember { mutableStateListOf<Float>() }

    LaunchedEffect(Unit) {
        var previousCpu = Process.getElapsedCpuTime()
        var previousWall = SystemClock.elapsedRealtime()
        var ticks = 0
        while (true) {
            val nowCpu = Process.getElapsedCpuTime()
            val nowWall = SystemClock.elapsedRealtime()
            val wallDelta = (nowWall - previousWall).coerceAtLeast(1L)
            val cpuDelta = (nowCpu - previousCpu).coerceAtLeast(0L)
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val cpu = ((cpuDelta.toFloat() / wallDelta.toFloat()) * 100f / cores)
                .coerceIn(0f, 100f)
            previousCpu = nowCpu
            previousWall = nowWall

            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memory = manager.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
            val ramMb = (memory?.totalPss ?: 0) / 1024f

            val batteryIntent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val battery = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
            val tempRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val temp = if (tempRaw >= 0) tempRaw / 10f else null
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val current = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                        .takeIf { it != Int.MIN_VALUE && it != 0 }?.div(1000)
                } else null
            }.getOrNull()

            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                when (power.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> "Normal"
                    PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                    PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                    PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                    PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                    PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
                    else -> "Unavailable"
                }
            } else "Android 10+ required"
            ticks++
            val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ticks % 10 == 0) {
                runCatching { power.getThermalHeadroom(10).takeIf { it.isFinite() && it >= 0f } }
                    .getOrNull()
            } else sample?.thermalHeadroom
            val threads = runCatching { java.io.File("/proc/self/task").list()?.size ?: 0 }.getOrDefault(0)
            val elapsed = SystemClock.elapsedRealtime() / 1000
            val uptime = String.format(
                Locale.getDefault(), "%02d:%02d:%02d",
                elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60
            )
            val provider = runCatching {
                Cipher.getInstance("AES/GCM/NoPadding").provider?.name ?: "Unavailable"
            }.getOrDefault("Unavailable")

            sample = MonitorSample(cpu, ramMb, battery, current, temp, thermal, headroom, threads, uptime, provider)
            cpuHistory.add(cpu)
            ramHistory.add(ramMb)
            while (cpuHistory.size > 120) cpuHistory.removeAt(0)
            while (ramHistory.size > 120) ramHistory.removeAt(0)
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Live Monitor", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onBack) { Text("Back") }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val current = sample
            if (current == null) {
                item { Text("Collecting device metrics…", modifier = Modifier.padding(12.dp)) }
            } else {
                item {
                    MetricCard("CPU · app process", String.format(Locale.getDefault(), "%.1f%%", current.cpu)) {
                        HistoryChart(cpuHistory.toList(), 100f, "%")
                    }
                }
                item {
                    MetricCard("RAM · app process", String.format(Locale.getDefault(), "%.1f MB", current.ramMb)) {
                        HistoryChart(ramHistory.toList(), (ramHistory.maxOrNull() ?: 1f).coerceAtLeast(1f), "MB")
                    }
                }
                item {
                    MetricCard("Battery", if (current.battery >= 0) "${current.battery}%" else "Unavailable") {
                        Text("Current: ${current.batteryCurrentMa?.let { "$it mA" } ?: "Unavailable"}")
                        Text("Temperature: ${current.batteryTempC?.let { String.format(Locale.getDefault(), "%.1f °C", it) } ?: "Unavailable"}")
                    }
                }
                item {
                    MetricCard("Thermal", current.thermalStatus) {
                        Text("Thermal headroom: ${current.thermalHeadroom?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "Not available yet"}")
                        Text("Headroom is sampled every 10 seconds where supported by Android.")
                    }
                }
                item {
                    MetricCard("Process", "${current.threads} threads") {
                        Text("Process uptime: ${current.uptime}")
                        Text("CPU and RAM values refer to RADAR VPN, not total device usage.")
                    }
                }
                item {
                    MetricCard("Cryptography provider", current.cryptoProvider) {
                        Text("Provider availability is shown; Android does not expose a reliable generic flag for hardware acceleration.")
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(value, style = MaterialTheme.typography.headlineMedium)
            content()
        }
    }
}

@Composable
private fun HistoryChart(values: List<Float>, maxValue: Float, unit: String) {
    Canvas(Modifier.fillMaxWidth().height(74.dp)) {
        if (values.size < 2) return@Canvas
        val step = size.width / (values.size - 1)
        val points = values.mapIndexed { index, value ->
            Offset(index * step, size.height - (value / maxValue.coerceAtLeast(1f)).coerceIn(0f, 1f) * size.height)
        }
        for (index in 0 until points.lastIndex) {
            drawLine(
                color = androidx.compose.ui.graphics.Color(0xFF36C6A5),
                start = points[index],
                end = points[index + 1],
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        drawRect(
            color = androidx.compose.ui.graphics.Color(0xFF36C6A5).copy(alpha = 0.12f),
            style = Stroke(width = 1.dp.toPx())
        )
    }
}
