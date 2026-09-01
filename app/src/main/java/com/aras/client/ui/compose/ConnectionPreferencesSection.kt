package com.aras.client.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.handler.MmkvManager.rememberMmkvBool

/**
 * The toggles that were previously only offered in the first-run setup,
 * exposed permanently in Settings (issue #1): auto-connect, testing and
 * display preferences. All bind to the same MMKV keys, so they stay in
 * sync with any other screen that edits them.
 */
@Composable
fun ConnectionPreferencesSection(modifier: Modifier = Modifier) {
    var autoConnectFastest by rememberMmkvBool(AppConfig.PREF_AUTO_CONNECT_FASTEST, false)
    var autoSortAfterTest by rememberMmkvBool(AppConfig.PREF_AUTO_SORT_AFTER_TEST, true)
    var autoScrollToTop by rememberMmkvBool(AppConfig.PREF_AUTO_SCROLL_TO_TOP, true)
    var showDelayChips by rememberMmkvBool(AppConfig.PREF_SHOW_DELAY_CHIPS, true)
    var showSubInfo by rememberMmkvBool(AppConfig.PREF_SHOW_SUB_INFO, true)
    var showSubAnnouncement by rememberMmkvBool(AppConfig.PREF_SHOW_SUB_ANNOUNCEMENT, true)

    Column(modifier = modifier.fillMaxWidth()) {
        SettingsSwitchItem(
            title = stringResource(R.string.setup_auto_connect),
            summary = stringResource(R.string.summary_auto_connect_fastest),
            checked = autoConnectFastest,
            onCheckedChange = { autoConnectFastest = it }
        )
        SettingsSwitchItem(
            title = stringResource(R.string.setup_auto_sort),
            summary = stringResource(R.string.summary_pref_auto_sort_after_test),
            checked = autoSortAfterTest,
            onCheckedChange = { autoSortAfterTest = it }
        )
        SettingsSwitchItem(
            title = stringResource(R.string.setup_auto_scroll),
            summary = stringResource(R.string.summary_pref_auto_scroll_to_top),
            checked = autoScrollToTop,
            onCheckedChange = { autoScrollToTop = it }
        )
        SettingsSwitchItem(
            title = stringResource(R.string.setup_show_delay),
            summary = stringResource(R.string.summary_pref_show_delay_chips),
            checked = showDelayChips,
            onCheckedChange = { showDelayChips = it }
        )
        SettingsSwitchItem(
            title = stringResource(R.string.title_pref_show_sub_info),
            summary = stringResource(R.string.summary_pref_show_sub_info),
            checked = showSubInfo,
            onCheckedChange = { showSubInfo = it }
        )
        SettingsSwitchItem(
            title = stringResource(R.string.title_pref_show_sub_announcement),
            summary = stringResource(R.string.summary_pref_show_sub_announcement),
            checked = showSubAnnouncement,
            onCheckedChange = { showSubAnnouncement = it }
        )
    }
}
