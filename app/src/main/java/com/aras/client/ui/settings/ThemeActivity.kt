package com.aras.client.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.handler.MmkvManager.rememberMmkvBool
import com.aras.client.handler.SettingsChangeManager
import com.aras.client.ui.base.BaseComponentActivity
import com.aras.client.ui.compose.AppTopBar
import com.aras.client.ui.compose.NavigationBarsSpacer
import com.aras.client.ui.compose.ThemeManager
import com.aras.client.ui.compose.ThemePicker

/**
 * Standalone theme chooser: real captures of this app, so the preview you pick
 * is the screen you get.
 */
class ThemeActivity : BaseComponentActivity() {

    @Composable
    override fun ScreenContent() = ThemeScreen(onBackClick = { finish() })
}

@Composable
fun ThemeScreen(onBackClick: () -> Unit) {
    val appTheme by ThemeManager.theme.collectAsStateWithLifecycle()
    var twoColumn by rememberMmkvBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)
    val labels = stringArrayResource(R.array.app_theme).toList()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(title = stringResource(R.string.title_pref_app_theme), onBackClick = onBackClick)
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Keep the phone-shaped hero readable on tablets and landscape.
            ThemePicker(
                selected = appTheme,
                twoColumn = twoColumn,
                labels = labels,
                modifier = Modifier.widthIn(max = 460.dp),
                onSelect = { ThemeManager.setTheme(it) },
                onTwoColumnChange = {
                    twoColumn = it
                    SettingsChangeManager.makeSetupGroupTab()
                }
            )
            NavigationBarsSpacer()
        }
    }
}
