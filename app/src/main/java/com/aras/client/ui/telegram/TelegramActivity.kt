package com.aras.client.ui.telegram

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aras.client.handler.MmkvManager
import com.aras.client.ui.base.BaseComponentActivity
import com.aras.client.ui.main.MainActivity

private val TelegramBlue = Color(0xFF229ED9)
private val TelegramBackground = Color(0xFF0E1621)
private val TelegramPanel = Color(0xFF17212B)
private val TelegramText = Color(0xFFE6EDF3)
private val TelegramMuted = Color(0xFF91A4B7)

class TelegramActivity : BaseComponentActivity() {
    @Composable
    override fun ScreenContent() {
        var showApiSettings by rememberSaveable { mutableStateOf(false) }
        val backToVpn = {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            finish()
        }
        BackHandler {
            if (showApiSettings) showApiSettings = false else backToVpn()
        }
        if (showApiSettings) {
            TelegramApiSettingsScreen(onBack = { showApiSettings = false })
        } else {
            TelegramOfflineScreen(
                onBackToVpn = backToVpn,
                onOpenApiSettings = { showApiSettings = true }
            )
        }
    }
}

@Composable
private fun TelegramOfflineScreen(
    onBackToVpn: () -> Unit,
    onOpenApiSettings: () -> Unit
) {
    val context = LocalContext.current
    var started by rememberSaveable { mutableStateOf(false) }
    var localTestSummary by rememberSaveable { mutableStateOf("") }
    val credentialsSaved = remember { TelegramCredentialsStore.load(context) != null }
    val configRows = remember {
        MmkvManager.decodeAllServerList().orEmpty().mapNotNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.let { profile ->
                guid to (profile.remarks.ifBlank { "RADAR Config" })
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(TelegramBackground)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().background(TelegramPanel).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Telegram", color = TelegramText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("RADAR • Offline assistant", color = TelegramMuted, fontSize = 12.sp)
            }
            TextButton(onClick = onOpenApiSettings) { Text("API", color = TelegramBlue) }
            TextButton(onClick = onBackToVpn) { Text("VPN", color = TelegramText) }
        }

        // This proxy announcement strip is intentionally fixed and has no dismiss action.
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(Color(0xFF18364A))
                .border(1.dp, Color(0xFF2D6B8B))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text("RADAR PROXY CHANNEL", color = Color(0xFF8DD8FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("پیشنهادهای پروکسی و وضعیت سرویس‌های RADAR", color = TelegramText, fontSize = 13.sp)
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                ChatBubble(
                    text = "سلام! من دستیار آفلاین RADAR هستم. حتی بدون اینترنت می‌توانم کانفیگ‌های ذخیره‌شده را نمایش بدهم و ساختار محلی آن‌ها را بررسی کنم."
                )
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF29465B), RoundedCornerShape(18.dp)),
                    color = TelegramPanel,
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (started) "RADAR Assistant فعال است" else "برای شروع دستیار، Start را بزنید",
                            color = TelegramText, fontWeight = FontWeight.SemiBold)
                        if (!started) {
                            Button(
                                onClick = { started = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = TelegramBlue)
                            ) { Text("START", color = Color.White) }
                        } else {
                            GlassActionButton("📡  کانفیگ‌های ذخیره‌شده") {
                                localTestSummary = if (configRows.isEmpty()) {
                                    "هنوز کانفیگی در RADAR ذخیره نشده است."
                                } else {
                                    configRows.joinToString("\n") { "• ${it.second}" }
                                }
                            }
                            GlassActionButton("🧪  تست محلی همه کانفیگ‌ها") {
                                val total = MmkvManager.decodeAllServerList().orEmpty().size
                                val valid = configRows.size
                                localTestSummary = "بررسی محلی انجام شد: $valid از $total کانفیگ قابل‌خواندن است. این بررسی بدون اینترنت است و پینگ یا دسترسی سرور را تأیید نمی‌کند."
                            }
                            GlassActionButton("➕  افزودن کانفیگ") { openVpnScreen(context) }
                            GlassActionButton("🔌  اتصال / مدیریت اتصال") { openVpnScreen(context) }
                            GlassActionButton("⚙️  تنظیمات API تلگرام") { onOpenApiSettings() }
                            GlassActionButton("↩  بازگشت به فیلترشکن") { onBackToVpn() }
                        }
                        if (localTestSummary.isNotBlank()) {
                            Text(localTestSummary, color = TelegramMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = TelegramPanel,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("وضعیت حساب تلگرام", color = TelegramText, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (credentialsSaved)
                                "API ID و API Hash به‌صورت رمزگذاری‌شده ذخیره شده‌اند؛ موتور TDLib هنوز به این نسخه متصل نشده و چت آنلاین فعال نیست."
                            else
                                "حالت آفلاین فعال است. برای آماده‌سازی اتصال واقعی، API ID و API Hash خودت را در تنظیمات وارد کن.",
                            color = TelegramMuted, fontSize = 12.sp
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().background(TelegramPanel).padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("حالت آفلاین • داده‌ها روی دستگاه", color = TelegramMuted, fontSize = 11.sp)
            TextButton(onClick = onBackToVpn) { Text("بازگشت", color = TelegramBlue) }
        }
    }
}

@Composable
private fun TelegramApiSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val saved = remember { TelegramCredentialsStore.load(context) }
    var apiId by rememberSaveable { mutableStateOf(saved?.apiId.orEmpty()) }
    var apiHash by rememberSaveable { mutableStateOf(saved?.apiHash.orEmpty()) }
    var status by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().background(TelegramBackground).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ بازگشت", color = TelegramBlue) }
            Text("تنظیمات API", color = TelegramText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "اطلاعات را فقط از my.telegram.org دریافت کن. این کلیدها متعلق به حساب/اپ خودت هستند؛ آن‌ها را با دیگران به اشتراک نگذار.",
            color = TelegramMuted, fontSize = 13.sp
        )
        OutlinedTextField(
            value = apiId,
            onValueChange = { apiId = it.filter(Char::isDigit).take(12) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API ID") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )
        OutlinedTextField(
            value = apiHash,
            onValueChange = { apiHash = it.filter { ch -> ch.isLetterOrDigit() }.take(32) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API Hash (32 characters)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
        )
        Button(
            onClick = {
                status = if (TelegramCredentialsStore.save(context, apiId, apiHash)) {
                    "کلیدها با رمزگذاری Android Keystore ذخیره شدند. اتصال آنلاین پس از افزودن و راه‌اندازی TDLib فعال می‌شود."
                } else {
                    "مقادیر معتبر نیستند؛ API ID باید عدد مثبت و API Hash باید ۳۲ کاراکتر هگزادسیمال باشد."
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = TelegramBlue)
        ) { Text("ذخیره امن", color = Color.White) }
        TextButton(onClick = {
            TelegramCredentialsStore.clear(context)
            apiId = ""
            apiHash = ""
            status = "اطلاعات API از دستگاه پاک شد."
        }) { Text("پاک کردن اطلاعات API", color = Color(0xFFFF7373)) }
        if (status.isNotBlank()) Text(status, color = TelegramMuted, fontSize = 12.sp)
    }
}

@Composable
private fun ChatBubble(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(0.92f),
        color = TelegramPanel,
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
    ) {
        Text(text, modifier = Modifier.padding(14.dp), color = TelegramText, fontSize = 13.sp)
    }
}

@Composable
private fun GlassActionButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF47738D), RoundedCornerShape(14.dp)),
        color = Color(0xFF243A4B),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 2.dp
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            color = TelegramText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

private fun openVpnScreen(context: android.content.Context) {
    context.startActivity(Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    })
}
