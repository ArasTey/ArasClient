@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.cluvex.zedsecure.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import dev.cluvex.zedsecure.ui.platform.LocalPlatform
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun SupportPage(
    contentPadding: PaddingValues,
    modifier: Modifier,
) {
    val platform = LocalPlatform.current
    val copied = stringResource(Res.string.support_copied)

    SettingsPageScaffold(
        title = stringResource(Res.string.support_title),
        subtitle = stringResource(Res.string.support_subtitle),
        contentPadding = contentPadding,
        modifier = modifier,
        art = SettingsArts.Support,
    ) {
        Surface(
            shape = MaterialTheme.shapes.largeIncreased,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    painterResource(Res.drawable.ic_favorite),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    stringResource(Res.string.support_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WalletCard(
                icon = Res.drawable.ic_coin_tether,
                label = stringResource(Res.string.support_usdt),
                address = "TRxVSHcoADZnBfztFmFb2TQopusAwWYEVR",
                onCopy = { platform.copyToClipboard(it); platform.toast(copied) },
            )
            WalletCard(
                icon = Res.drawable.ic_coin_ton,
                label = stringResource(Res.string.support_ton),
                address = "UQAH75bXaaRUhZMwiF0ZujOXFDDmvLSPASKoOsWF0HNasiaM",
                onCopy = { platform.copyToClipboard(it); platform.toast(copied) },
            )
            WalletCard(
                icon = Res.drawable.ic_coin_tron,
                label = stringResource(Res.string.support_tron),
                address = "TRxVSHcoADZnBfztFmFb2TQopusAwWYEVR",
                onCopy = { platform.copyToClipboard(it); platform.toast(copied) },
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(Res.string.support_usdt_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun WalletCard(
    icon: DrawableResource,
    label: String,
    address: String,
    onCopy: (String) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable { onCopy(address) },
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.Unspecified,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { onCopy(address) }) {
                Icon(
                    painterResource(Res.drawable.ic_content_paste),
                    contentDescription = stringResource(Res.string.action_copy),
                )
            }
        }
    }
}
