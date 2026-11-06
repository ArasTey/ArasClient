@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.cluvex.zedsecure.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import dev.cluvex.zedsecure.data.assets.GeoAsset
import dev.cluvex.zedsecure.data.assets.GeoAssets
import dev.cluvex.zedsecure.domain.model.AppSettings
import dev.cluvex.zedsecure.domain.model.GeoFilesSource
import dev.cluvex.zedsecure.ui.platform.LocalPlatform
import kotlinx.coroutines.launch

@Composable
fun AssetsScreen(
    settings: AppSettings,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit = {},
) {
    val platform = LocalPlatform.current
    val geo = platform.geoAssets
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        Spacer(Modifier.height(8.dp))
        SettingsHeader(
            title = stringResource(Res.string.assets_title),
            subtitle = stringResource(Res.string.assets_subtitle),
            art = SettingsArts.Assets,
        )
        Spacer(Modifier.height(14.dp))

        if (geo == null) {
            Text(
                stringResource(Res.string.assets_subtitle),
                Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        var assets by remember { mutableStateOf(geo.state()) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            assets.forEach { asset ->
                AssetCard(
                    asset = asset,
                    onImport = {
                        scope.launch {
                            val pick = platform.pickFileBytes() ?: return@launch
                            busy = true
                            val ok = runCatching { geo.importBytes(pick.bytes, asset.name) }.getOrDefault(false)
                            assets = geo.state()
                            busy = false
                            message = getString(if (ok) Res.string.assets_done else Res.string.assets_failed)
                        }
                    },
                )
            }

            Spacer(Modifier.height(4.dp))

            SettingsGroup(inset = 0.dp) {
                SettingsListRow(
                    title = stringResource(Res.string.assets_source),
                    options = GeoFilesSource.entries.map { it to it.repo },
                    selected = settings.geoFilesSource,
                    onSelected = { v -> onUpdate { it.copy(geoFilesSource = v) } },
                )
            }
            Text(
                stringResource(Res.string.assets_source_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(Res.string.assets_bundled_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            if (busy) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ContainedLoadingIndicator()
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(Res.string.assets_downloading))
                }
            } else {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            message = null
                            val ok = geo.downloadAll(settings.geoFilesSource)
                            assets = geo.state()
                            busy = false
                            message = getString(if (ok) Res.string.assets_done else Res.string.assets_failed)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.assets_download))
                }
            }

            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024
    var i = 0
    while (value >= 1024 && i < units.size - 1) { value /= 1024; i++ }
    val rounded = (value * 10).toLong() / 10.0
    return "$rounded ${units[i]}"
}

@Composable
private fun AssetCard(asset: GeoAsset, onImport: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(Res.drawable.ic_description),
                contentDescription = null,
                tint = if (asset.present) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    asset.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (asset.present) {
                        stringResource(Res.string.assets_size, formatBytes(asset.sizeBytes), "")
                    } else {
                        stringResource(Res.string.assets_missing)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onImport) {
                Text(stringResource(Res.string.assets_import))
            }
        }
    }
}
