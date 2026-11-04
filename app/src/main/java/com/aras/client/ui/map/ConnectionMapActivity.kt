package com.aras.client.ui.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import com.aras.client.ui.base.BaseComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private data class IpPlace(
    val ip: String,
    val city: String,
    val country: String,
    val latitude: Double,
    val longitude: Double
) {
    val label: String get() = listOf(city, country).filter { it.isNotBlank() }.joinToString(", ")
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
    val scope = rememberCoroutineScope()
    var origin by remember { mutableStateOf(readSavedPlace(context, "origin")) }
    var exit by remember { mutableStateOf(readSavedPlace(context, "exit")) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var mapView by remember { mutableStateOf<WebView?>(null) }

    fun refreshMap() {
        val from = origin
        val to = exit
        val script = if (from != null && to != null) {
            "window.setConnectionPoints(${from.latitude},${from.longitude},${to.latitude},${to.longitude});"
        } else if (from != null) {
            "window.setConnectionPoints(${from.latitude},${from.longitude},null,null);"
        } else if (to != null) {
            "window.setConnectionPoints(null,null,${to.latitude},${to.longitude});"
        } else "window.setConnectionPoints(null,null,null,null);"
        mapView?.evaluateJavascript(script, null)
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
                refreshMap()
            } catch (exception: Exception) {
                error = exception.message ?: context.getString(R.string.map_lookup_failed)
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
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { viewContext ->
                    WebView(viewContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                mapView = view
                                refreshMap()
                            }
                        }
                        loadDataWithBaseURL(
                            "https://unpkg.com/",
                            MAP_HTML,
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                },
                update = { mapView = it }
            )
        }
    }
}


private fun savePlace(context: Context, key: String, place: IpPlace) {
    val json = JSONObject()
        .put("ip", place.ip)
        .put("city", place.city)
        .put("country", place.country)
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
            latitude = latitude,
            longitude = longitude
        )
    } finally {
        connection.disconnect()
    }
}

private val MAP_HTML = """
<!doctype html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<style>
html,body,#map{height:100%;width:100%;margin:0;background:#101820}
.leaflet-container{background:#101820}
.animated-route{stroke-dasharray:8 10;animation:route-flow 1.2s linear infinite}
@keyframes route-flow{to{stroke-dashoffset:-18}}
</style>
</head>
<body>
<div id="map"></div>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
const map=L.map('map',{worldCopyJump:true}).setView([22,8],2);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{
 maxZoom:18, attribution:'&copy; OpenStreetMap contributors'
}).addTo(map);
let originMarker=null,exitMarker=null,route=null;
window.setConnectionPoints=function(olat,olon,elat,elon){
 if(originMarker){map.removeLayer(originMarker);originMarker=null;}
 if(exitMarker){map.removeLayer(exitMarker);exitMarker=null;}
 if(route){map.removeLayer(route);route=null;}
 const points=[];
 if(olat!==null && olon!==null){
  const p=[olat,olon]; points.push(p);
  originMarker=L.marker(p).addTo(map).bindPopup('Origin network');
 }
 if(elat!==null && elon!==null){
  const p=[elat,elon]; points.push(p);
  exitMarker=L.marker(p).addTo(map).bindPopup('VPN exit');
 }
 if(points.length===2){
  route=L.polyline(points,{color:'#31d6b0',weight:3,className:'animated-route'}).addTo(map);
  map.fitBounds(route.getBounds().pad(0.25));
 }else if(points.length===1){map.setView(points[0],5);}
};
</script>
</body>
</html>
""".trimIndent()
