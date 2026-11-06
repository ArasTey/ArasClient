@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.cluvex.zedsecure.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import dev.cluvex.zedsecure.domain.config.PsiphonConfigBuilder
import dev.cluvex.zedsecure.domain.config.PsiphonProfile
import dev.cluvex.zedsecure.ui.components.PickerField

@Composable
fun PsiphonSheet(
    initial: PsiphonProfile? = null,
    initialName: String = "",
    onDismiss: () -> Unit,
    onSave: (name: String, PsiphonProfile) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var country by remember { mutableStateOf(initial?.country ?: "") }
    var mode by remember { mutableStateOf(initial?.mode ?: PsiphonConfigBuilder.MODE_AUTO) }
    var cdnIps by remember { mutableStateOf(initial?.cdnIps ?: "") }
    var cdnSni by remember { mutableStateOf(initial?.cdnSni ?: "") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,

        sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(Res.string.psiphon_add_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.manual_remark)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PsiphonCountryField(
                selected = country,
                onSelect = { country = it },
            )
            PickerField(
                label = stringResource(Res.string.psiphon_mode),
                options = PsiphonConfigBuilder.MODES.map { it to it },
                selected = mode,
                onSelect = { mode = it },
            )
            if (mode == PsiphonConfigBuilder.MODE_CDN) {
                OutlinedTextField(
                    value = cdnIps,
                    onValueChange = { cdnIps = it },
                    label = { Text(stringResource(Res.string.psiphon_cdn_ips)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cdnSni,
                    onValueChange = { cdnSni = it },
                    label = { Text(stringResource(Res.string.psiphon_cdn_sni)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = {
                    onSave(
                        name.trim(),
                        PsiphonProfile(
                            country = country.trim(),
                            mode = mode,
                            cdnIps = cdnIps.trim(),
                            cdnSni = cdnSni.trim(),
                        ),
                    )
                },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Text(stringResource(Res.string.action_save), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
