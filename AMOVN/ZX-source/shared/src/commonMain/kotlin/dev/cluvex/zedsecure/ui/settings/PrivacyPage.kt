@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.cluvex.zedsecure.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import dev.cluvex.zedsecure.ui.platform.LocalPlatform
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val POLICY_URL = "https://cluvexstudio.github.io/ZedSecure-PrivacyPolicy/"

@Composable
fun PrivacyPage(
    contentPadding: PaddingValues,
    modifier: Modifier,
) {
    val platform = LocalPlatform.current

    SettingsPageScaffold(
        title = stringResource(Res.string.privacy_title),
        subtitle = stringResource(Res.string.privacy_subtitle),
        contentPadding = contentPadding,
        modifier = modifier,
        art = SettingsArts.Privacy,
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
                    painterResource(Res.drawable.ic_policy),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    stringResource(Res.string.privacy_headline),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Section(
                stringResource(Res.string.privacy_client_title),
                stringResource(Res.string.privacy_client_body),
            )
            Section(
                stringResource(Res.string.privacy_device_title),
                stringResource(Res.string.privacy_device_body),
            )
            Section(
                stringResource(Res.string.privacy_nocollect_title),
                stringResource(Res.string.privacy_nocollect_body),
            )
            Section(
                stringResource(Res.string.privacy_third_title),
                stringResource(Res.string.privacy_third_body),
            )
            Section(
                stringResource(Res.string.privacy_perm_title),
                stringResource(Res.string.privacy_perm_body),
            )
            Section(
                stringResource(Res.string.privacy_deviceid_title),
                stringResource(Res.string.privacy_deviceid_body),
            )
            Section(
                stringResource(Res.string.privacy_limits_title),
                stringResource(Res.string.privacy_limits_body),
            )
        }

        Spacer(Modifier.height(14.dp))

        Surface(
            shape = MaterialTheme.shapes.largeIncreased,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clickable { platform.openUri(POLICY_URL) },
        ) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(Res.drawable.ic_description), contentDescription = null)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.privacy_read_full),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(Res.string.privacy_read_full_sub),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Icon(painterResource(Res.drawable.ic_chevron_right), contentDescription = null)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Section(title: String, body: String) {
    Surface(
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
