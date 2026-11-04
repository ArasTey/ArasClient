package com.aras.client.ui.dns

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aras.client.core.LauncherManager
import com.aras.client.core.CoreServiceManager
import com.aras.client.ui.base.BaseComponentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DnsChangerActivity : BaseComponentActivity() {
    @Composable
    override fun ScreenContent() {
        DnsChangerScreen(onBack = { finish() })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsChangerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles = remember {
        mutableStateListOf<DnsProfile>().apply {
            addAll(DnsChangerStore.presets)
            addAll(DnsChangerStore.customProfiles())
        }
    }
    val active = DnsChangerStore.activeProfile()
    var primary by remember { mutableStateOf(active?.primary.orEmpty()) }
    var secondary by remember { mutableStateOf(active?.secondary.orEmpty()) }
    var customName by remember { mutableStateOf("My DNS") }
    var testing by remember { mutableStateOf(false) }
    var testingAll by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showStandaloneInfo by remember { mutableStateOf(false) }
    var selectedName by remember { mutableStateOf(active?.name) }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DNS CHANGER", fontWeight = FontWeight.Black)
                        Text("Resolver profiles • latency • tunnel DNS", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (DnsChangerStore.isEnabled()) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Dns, contentDescription = null, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (DnsChangerStore.isEnabled()) "DNS profile selected" else "Choose your resolver", fontWeight = FontWeight.Bold)
                            Text(
                                if (DnsChangerStore.isEnabled()) "${primary}  ${secondary}".trim()
                                else "Custom resolver profiles for the Radar VPN tunnel",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (DnsChangerStore.isEnabled()) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item {
                Text("DNS SERVER LIBRARY", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            items(profiles, key = { "${it.name}-${it.primary}" }) { profile ->
                val isSelected = selectedName == profile.name && primary == profile.primary
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        primary = profile.primary
                        secondary = profile.secondary
                        selectedName = profile.name
                        testResult = null
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(profile.name, fontWeight = FontWeight.SemiBold)
                            Text("${profile.primary}  •  ${profile.secondary}", style = MaterialTheme.typography.bodySmall)
                            Text(profile.region, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isSelected) Icon(Icons.Rounded.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                        if (profile.custom) {
                            IconButton(onClick = {
                                DnsChangerStore.removeCustom(profile)
                                profiles.remove(profile)
                            }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete profile")
                            }
                        }
                    }
                }
            }
            item {
                HorizontalDivider()
                Text("CUSTOM DNS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    label = { Text("Profile name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = primary,
                    onValueChange = { primary = it.trim(); selectedName = null; testResult = null },
                    label = { Text("Primary DNS (IPv4 / IPv6)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = secondary,
                    onValueChange = { secondary = it.trim(); selectedName = null },
                    label = { Text("Secondary DNS (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !testing && DnsChangerStore.isIpAddress(primary),
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                testResult = runCatching {
                                    val ms = withContext(Dispatchers.IO) { DnsLatencyProbe.test(primary) }
                                    "DNS response: ${ms} ms"
                                }.getOrElse { "Test failed: ${it.message ?: "No response"}" }
                                testing = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Speed, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("Test DNS")
                    }
                    Button(
                        enabled = DnsChangerStore.isIpAddress(primary) &&
                            (secondary.isBlank() || DnsChangerStore.isIpAddress(secondary)),
                        onClick = {
                            val profile = DnsProfile(customName.ifBlank { "My DNS" }, primary, secondary, "Custom", true)
                            DnsChangerStore.saveCustom(profile)
                            profiles.removeAll { it.custom && it.name.equals(profile.name, true) }
                            profiles.add(profile)
                            selectedName = profile.name
                            notice = "Custom DNS profile saved"
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("Save")
                    }
                }
                Button(
                    enabled = !testing && !testingAll && profiles.isNotEmpty(),
                    onClick = {
                        testingAll = true
                        testResult = "Testing resolver catalogue…"
                        val candidates = profiles.toList()
                        scope.launch {
                            val results = withContext(Dispatchers.IO) {
                                candidates.map { profile ->
                                    async {
                                        profile to runCatching {
                                            DnsLatencyProbe.test(profile.primary, 1400)
                                        }.getOrNull()
                                    }
                                }.awaitAll()
                            }
                            val fastest = results.filter { it.second != null }
                                .minByOrNull { it.second!! }
                            if (fastest != null) {
                                primary = fastest.first.primary
                                secondary = fastest.first.secondary
                                selectedName = fastest.first.name
                                testResult = "Fastest responding DNS: ${fastest.first.name} • ${fastest.second} ms"
                            } else {
                                testResult = "No DNS resolver answered on UDP/53"
                            }
                            testingAll = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (testingAll) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Speed, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("AUTO FASTEST • TEST ALL")
                }
                testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            item {
                Button(
                    onClick = {
                        if (!DnsChangerStore.isIpAddress(primary) ||
                            (secondary.isNotBlank() && !DnsChangerStore.isIpAddress(secondary))) {
                            notice = "Enter valid IPv4 or IPv6 DNS addresses"
                        } else {
                            DnsChangerStore.activate(DnsProfile(selectedName ?: "Custom DNS", primary, secondary))
                            if (CoreServiceManager.isRunning()) {
                                runCatching { LauncherManager.restartService(context) }
                                notice = "DNS selected and the active Radar tunnel is restarting"
                            } else {
                                showStandaloneInfo = true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("APPLY DNS TO VPN TUNNEL")
                }
                if (DnsChangerStore.isEnabled()) {
                    TextButton(
                        onClick = {
                            DnsChangerStore.deactivate()
                            if (CoreServiceManager.isRunning()) LauncherManager.restartService(context)
                            notice = "DNS override disabled"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("DISCONNECT DNS")
                    }
                }
                Text(
                    "When Radar's VPN tunnel is running, this profile replaces its DNS resolvers. " +
                        "The standalone no-config tunnel is not enabled in this build yet; DNS is never reported as connected when no tunnel exists.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showStandaloneInfo) {
        AlertDialog(
            onDismissRequest = { showStandaloneInfo = false },
            title = { Text("DNS profile saved") },
            text = {
                Text("The DNS profile is saved. It can be applied to the Radar VPN tunnel. Standalone mode still needs its own local packet-forwarding engine before it can carry game traffic without a VPN configuration.")
            },
            confirmButton = {
                TextButton(onClick = { showStandaloneInfo = false }) { Text("OK") }
            }
        )
    }
    notice?.let { message ->
        AlertDialog(
            onDismissRequest = { notice = null },
            title = { Text("DNS CHANGER") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } }
        )
    }
}
