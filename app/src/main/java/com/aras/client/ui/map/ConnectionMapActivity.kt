package com.aras.client.ui.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.aras.client.ui.base.BaseComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.aras.client.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.compose.ui.draw.clip
import java.net.HttpURLConnection
import java.net.URL

private data class IpPlace(
    val ip: String,
    val city: String,
    val country: String,
    val countryCode: String = "",
    val latitude: Double,
    val longitude: Double
) {
    val label: String get() = listOf(city, country).filter { it.isNotBlank() }.joinToString(", ")

    fun toMapPoint(): MapPoint {
        val grid = WorldMap.gridOf(longitude.toFloat(), latitude.toFloat())
        return MapPoint(grid.first, grid.second, label, countryCode)
    }
}

class ConnectionMapActivity : BaseComponentActivity() {
    @Composable
    override fun ScreenContent() {
        ConnectionMapScreen(onBack = { finish() })
    }
}

@Composable
private fun ConnectionMapScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lookupFailedMessage = stringResource(R.string.map_lookup_failed)
    val scope = rememberCoroutineScope()
    var origin by remember { mutableStateOf(readSavedPlace(context, "origin")) }
    var exit by remember { mutableStateOf(readSavedPlace(context, "exit")) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var countries by remember { mutableStateOf<List<WorldMap.Country>>(emptyList()) }

    LaunchedEffect(context) {
        countries = withContext(Dispatchers.IO) { WorldMap.countries(context) }
    }

    fun locateNetwork() {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val place = withContext(Dispatchers.IO) { lookupPublicIpPlace() }
                val vpnActive = isVpnTransportActive(context)
                if (vpnActive) {
                    exit = place
                    savePlace(context, "exit", place)
                } else {
                    origin = place
                    savePlace(context, "origin", place)
                }
            } catch (exception: Exception) {
                error = exception.message ?: lookupFailedMessage
            } finally {
                loading = false
            }
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.map_title), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onBack) { Text(stringResource(R.string.map_back)) }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.map_network_locations), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.map_capture_instructions))
                    Text(stringResource(R.string.map_origin, origin?.label ?: stringResource(R.string.map_not_captured)))
                    Text(stringResource(R.string.map_exit, exit?.label ?: stringResource(R.string.map_not_captured)))
                    Text(stringResource(R.string.map_ip, exit?.ip ?: origin?.ip ?: stringResource(R.string.map_no_ip)))
                    Text(stringResource(R.string.map_disclaimer), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { locateNetwork() }, enabled = !loading) {
                        Text(if (loading) stringResource(R.string.map_lookup_loading) else if (isVpnTransportActive(context)) stringResource(R.string.map_refresh_exit) else stringResource(R.string.map_capture_network))
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp))
            ) {
                if (countries.isEmpty()) {
                    Text("Loading offline world map…", modifier = Modifier.padding(16.dp))
                } else {
                    WorldMapCanvas(
                        countries = countries,
                        origin = origin?.toMapPoint(),
                        exit = exit?.toMapPoint(),
                        accent = MaterialTheme.colorScheme.primary,
                        originColor = MaterialTheme.colorScheme.secondary,
                        landColor = MaterialTheme.colorScheme.onSurface,
                        reduceMotion = false,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}


private fun savePlace(context: Context, key: String, place: IpPlace) {
    val json = JSONObject()
        .put("ip", place.ip)
        .put("city", place.city)
        .put("country", place.country)
        .put("countryCode", place.countryCode)
        .put("latitude", place.latitude)
        .put("longitude", place.longitude)
        .toString()
    context.getSharedPreferences("vpn_connection_map", Context.MODE_PRIVATE)
        .edit().putString(key, json).apply()
}

private fun readSavedPlace(context: Context, key: String): IpPlace? = runCatching {
    val raw = context.getSharedPreferences("vpn_connection_map", Context.MODE_PRIVATE)
        .getString(key, null) ?: return null
    val json = JSONObject(raw)
    IpPlace(
        ip = json.optString("ip", ""),
        city = json.optString("city", ""),
        country = json.optString("country", ""),
        countryCode = json.optString("countryCode", ""),
        latitude = json.getDouble("latitude"),
        longitude = json.getDouble("longitude")
    )
}.getOrNull()

private fun isVpnTransportActive(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = manager.activeNetwork ?: return false
    return manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
}

private fun lookupPublicIpPlace(): IpPlace {
    val connection = (URL("https://ipwho.is/").openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8000
        readTimeout = 8000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "RADAR-VPN-ConnectionMap")
    }
    try {
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        if (!json.optBoolean("success", false)) {
            throw IllegalStateException(json.optString("message", "IP location lookup failed"))
        }
        val latitude = json.optDouble("latitude", Double.NaN)
        val longitude = json.optDouble("longitude", Double.NaN)
        if (!latitude.isFinite() || !longitude.isFinite()) {
            throw IllegalStateException("Location coordinates are unavailable")
        }
        return IpPlace(
            ip = json.optString("ip", ""),
            city = json.optString("city", ""),
            country = json.optString("country", ""),
            countryCode = json.optString("country_code", ""),
            latitude = latitude,
            longitude = longitude
        )
    } finally {
        connection.disconnect()
    }
}

