@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.cluvex.zedsecure.ui.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import dev.cluvex.zedsecure.domain.config.PsiphonRegions
import dev.cluvex.zedsecure.ui.components.FlagBadge
import java.util.Locale

@Composable
fun PsiphonCountryField(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val code = selected.trim().uppercase()

    Surface(
        onClick = { open = true },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (code.isEmpty()) {
                Icon(
                    painter = org.jetbrains.compose.resources.painterResource(Res.drawable.ic_public),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                FlagBadge(countryCode = code, size = 26.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.psiphon_country),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (code.isEmpty()) stringResource(Res.string.psiphon_country_auto)
                    else countryName(code),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
            Icon(
                painter = org.jetbrains.compose.resources.painterResource(Res.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (open) {
        CountryPickerSheet(
            selected = code,
            onPick = {
                onSelect(it)
                open = false
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun CountryPickerSheet(
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val autoLabel = stringResource(Res.string.psiphon_country_auto)

    val all = remember {
        listOf("") + PsiphonRegions.CODES.sortedBy { countryName(it).lowercase() }
    }
    val filtered by remember(all) {
        derivedStateOf {
            val q = query.trim().lowercase()
            if (q.isEmpty()) all
            else all.filter { c ->
                val name = if (c.isEmpty()) autoLabel else countryName(c)
                name.lowercase().contains(q) || c.lowercase().contains(q)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
            Text(
                stringResource(Res.string.psiphon_country_pick),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.action_search)) },
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(Modifier.heightIn(max = 460.dp).padding(top = 8.dp)) {
                items(filtered, key = { it }) { c ->
                    CountryRow(
                        code = c,
                        autoLabel = autoLabel,
                        selected = c == selected,
                        onClick = { onPick(c) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CountryRow(
    code: String,
    autoLabel: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.padding(start = 4.dp)) {
            if (code.isEmpty()) {
                Icon(
                    painter = org.jetbrains.compose.resources.painterResource(Res.drawable.ic_public),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                FlagBadge(countryCode = code, size = 26.dp)
            }
        }
        Text(
            if (code.isEmpty()) autoLabel else countryName(code),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        RadioButton(selected = selected, onClick = onClick)
    }
}

private fun countryName(code: String): String {
    val name = runCatching { Locale.Builder().setRegion(code).build() }.getOrNull()
        ?.getDisplayCountry(Locale.getDefault()).orEmpty()
    return name.ifBlank { code }
}
