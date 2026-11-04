package com.aras.client.ui.zx

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aras.client.AngApplication
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.handler.MmkvManager
import com.aras.client.ui.base.BaseComponentActivity
import com.aras.client.ui.main.MainAction
import com.aras.client.ui.main.MainRepository
import com.aras.client.ui.main.MainViewModel
import com.aras.client.ui.map.ConnectionMapActivity
import com.aras.client.ui.monitor.LiveMonitorActivity
import com.aras.client.ui.radar.RadarConfigActivity
import com.aras.client.ui.repair.ConfigRepairActivity
import com.aras.client.ui.dns.DnsChangerActivity
import com.aras.client.ui.settings.SettingsActivity

class ZxUiActivity : BaseComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application, MainRepository(application as AngApplication))
    }

    @Composable
    override fun ScreenContent() {
        ZxUiScreen(
            viewModel = mainViewModel,
            onExit = ::finish,
            onOpen = { activity -> startActivity(Intent(this, activity)) },
        )
    }
}

private enum class ZxTab(val label: String, val marker: String) {
    Home("خانه", "⌂"),
    Servers("سرورها", "◎"),
    Tools("ابزارها", "✦"),
    Settings("تنظیمات", "⚙"),
}

@Composable
private fun ZxUiScreen(
    viewModel: MainViewModel,
    onExit: () -> Unit,
    onOpen: (Class<out android.app.Activity>) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(ZxTab.Home) }

    LaunchedEffect(viewModel) {
        viewModel.setMainScreenActive(true)
        viewModel.onAction(MainAction.Initialize)
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.setMainScreenActive(false) }
    }

    val selectedProfile: ProfileItem? = state.selectedGuid?.let(MmkvManager::decodeServerConfig)
    val serverGuids = MmkvManager.decodeServerList(state.selectedGroupId)

    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = Color(0xFF65F5C6),
            onPrimary = Color(0xFF052019),
            secondary = Color(0xFF9D8CFF),
            background = Color(0xFF070B12),
            surface = Color(0xFF111A26),
            surfaceVariant = Color(0xFF1A2635),
            onSurface = Color(0xFFEAF5F3),
            onSurfaceVariant = Color(0xFFA2B7B8),
        ),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(containerColor = Color(0xF20D1520)) {
                    ZxTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = {
                                if (item == ZxTab.Settings) {
                                    onOpen(SettingsActivity::class.java)
                                } else {
                                    tab = item
                                }
                            },
                            icon = { Text(item.marker, style = MaterialTheme.typography.titleLarge) },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { insets ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF0B1821), Color(0xFF080D16), Color(0xFF090B13)),
                        ),
                    )
                    .padding(insets),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp, bottom = 22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                "ZX VPN",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFFECFFF8),
                            )
                            Text(
                                "محیط رابط مستقل",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF8DA6A8),
                            )
                        }
                        Surface(
                            color = if (state.isRunning) Color(0xFF153D32) else Color(0xFF292333),
                            shape = CircleShape,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .background(
                                            if (state.isRunning) Color(0xFF65F5C6) else Color(0xFFFFB86B),
                                            CircleShape,
                                        ),
                                )
                                Text(
                                    if (state.isRunning) " متصل" else " قطع",
                                    color = Color(0xFFEAF5F3),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }

                    when (tab) {
                        ZxTab.Home -> ZxHomePage(
                            isRunning = state.isRunning,
                            profile = selectedProfile,
                            onToggle = { viewModel.onAction(MainAction.ToggleService) },
                            onServers = { tab = ZxTab.Servers },
                            onMap = { onOpen(ConnectionMapActivity::class.java) },
                            onMonitor = { onOpen(LiveMonitorActivity::class.java) },
                        )
                        ZxTab.Servers -> ZxServersPage(
                            groups = state.groups,
                            selectedGroupId = state.selectedGroupId,
                            serverGuids = serverGuids,
                            selectedGuid = state.selectedGuid,
                            onGroup = { viewModel.onAction(MainAction.SelectGroup(it)) },
                            onServer = { viewModel.onAction(MainAction.SelectServer(it)) },
                        )
                        ZxTab.Tools -> ZxToolsPage(onOpen)
                        ZxTab.Settings -> Unit
                    }
                }
                Text(
                    "بازگشت به رابط RADAR",
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 78.dp, end = 20.dp)
                        .clickable(onClick = onExit)
                        .padding(6.dp),
                    color = Color(0xFF78918F),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun ZxHomePage(
    isRunning: Boolean,
    profile: ProfileItem?,
    onToggle: () -> Unit,
    onServers: () -> Unit,
    onMap: () -> Unit,
    onMonitor: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "اتصال امن، با کنترل کامل",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFE8FFF7),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF142A2C)),
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (isRunning) "تونل فعال است" else "آماده اتصال",
                    color = Color(0xFF65F5C6),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    profile?.remarks?.takeIf { it.isNotBlank() } ?: "سروری انتخاب نشده",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    profile?.let { listOfNotNull(it.server, it.serverPort).joinToString(":") }
                        ?.takeIf { it.isNotBlank() } ?: "یک سرور از فهرست انتخاب کنید",
                    color = Color(0xFF9EB8B6),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onToggle,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(if (isRunning) "قطع اتصال" else "اتصال VPN", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onServers,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("انتخاب سرور")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ZxQuickTile("نقشه اتصال", "نمایش مسیر IP", Modifier.weight(1f), onMap)
            ZxQuickTile("مانیتور زنده", "وضعیت دستگاه", Modifier.weight(1f), onMonitor)
        }
        Text(
            "وضعیت اتصال مستقیماً از موتور فعلی برنامه خوانده می‌شود.",
            color = Color(0xFF819A9A),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ZxServersPage(
    groups: List<com.aras.client.dto.GroupMapItem>,
    selectedGroupId: String,
    serverGuids: List<String>,
    selectedGuid: String?,
    onGroup: (String) -> Unit,
    onServer: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("سرورها", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(groups) { group ->
                val selected = group.id == selectedGroupId
                Surface(
                    color = if (selected) Color(0xFF245344) else Color(0xFF182331),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.clickable { onGroup(group.id) },
                ) {
                    Text(
                        group.remarks,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        color = if (selected) Color(0xFF8BFFCF) else Color(0xFFD5E4E2),
                    )
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            items(serverGuids) { guid ->
                val profile = MmkvManager.decodeServerConfig(guid)
                if (profile != null) {
                    val selected = guid == selectedGuid
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onServer(guid) },
                        color = if (selected) Color(0xFF193C34) else Color(0xFF121D29),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                profile.remarks.ifBlank { "سرور بدون نام" },
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${profile.configType.name}  •  ${profile.server.orEmpty()}${profile.serverPort?.let { ":$it" }.orEmpty()}",
                                color = Color(0xFF9EB8B6),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (selected) Text("انتخاب‌شده", color = Color(0xFF65F5C6), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZxToolsPage(onOpen: (Class<out android.app.Activity>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ابزارهای اتصال", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
        ZxQuickTile("نقشه اتصال", "موقعیت IP و خروجی VPN", Modifier.fillMaxWidth()) { onOpen(ConnectionMapActivity::class.java) }
        ZxQuickTile("مانیتور زنده", "CPU، RAM، باتری و دما", Modifier.fillMaxWidth()) { onOpen(LiveMonitorActivity::class.java) }
        ZxQuickTile("رادار کانفیگ", "کشف و بررسی کانفیگ‌ها", Modifier.fillMaxWidth()) { onOpen(RadarConfigActivity::class.java) }
        ZxQuickTile("تعمیر کانفیگ", "بررسی و اصلاح پروفایل", Modifier.fillMaxWidth()) { onOpen(ConfigRepairActivity::class.java) }
        ZxQuickTile("تغییر DNS", "مدیریت DNS محلی", Modifier.fillMaxWidth()) { onOpen(DnsChangerActivity::class.java) }
    }
}

@Composable
private fun ZxQuickTile(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = Color(0xFF14202C),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Color(0xFFE8FFF7), fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFF8EA8A7), style = MaterialTheme.typography.bodySmall)
        }
    }
}
