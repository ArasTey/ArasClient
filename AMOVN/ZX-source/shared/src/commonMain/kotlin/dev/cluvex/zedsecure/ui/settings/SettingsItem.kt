@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.cluvex.zedsecure.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import dev.cluvex.zedsecure.ui.components.PageBackButton
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val DisabledAlpha = 0.38f
private val SegmentGap = 2.dp
private val SegmentOuter = 26.dp
private val SegmentInner = 6.dp
private val PressedRadius = 22.dp
private val HoveredRadius = 12.dp

@Composable
fun segmentColor(): Color =
    if (dev.cluvex.zedsecure.ui.theme.LocalAppDesign.current == dev.cluvex.zedsecure.domain.model.AppDesign.Liquid) {
        dev.cluvex.zedsecure.ui.theme.liquidFill()
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

@Composable
private fun choiceIdleColor(): Color {
    val cs = MaterialTheme.colorScheme
    return if (cs.surface.luminance() < 0.5f) cs.surfaceContainerHighest else cs.surfaceBright
}

private class SegmentShapes(outer: Dp, inner: Dp) {
    private fun block(shape: Shape): GraphicsLayerScope.() -> Unit = {
        this.shape = shape
        clip = true
    }

    val single = block(RoundedCornerShape(outer))
    val first = block(RoundedCornerShape(topStart = outer, topEnd = outer, bottomEnd = inner, bottomStart = inner))
    val middle = block(RoundedCornerShape(inner))
    val last = block(RoundedCornerShape(topStart = inner, topEnd = inner, bottomEnd = outer, bottomStart = outer))

    fun at(index: Int, count: Int): GraphicsLayerScope.() -> Unit = when {
        count == 1 -> single
        index == 0 -> first
        index == count - 1 -> last
        else -> middle
    }
}

@Composable
private fun SegmentedColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shapes = remember { SegmentShapes(SegmentOuter, SegmentInner) }
    Layout(content, modifier) { measurables, constraints ->
        val bounded = constraints.hasBoundedWidth
        val child = if (bounded) {
            Constraints(minWidth = constraints.maxWidth, maxWidth = constraints.maxWidth)
        } else {
            Constraints()
        }
        val placeables = measurables.map { it.measure(child) }
        val width = if (bounded) constraints.maxWidth else placeables.maxOfOrNull { it.width } ?: 0
        val gap = SegmentGap.roundToPx()
        val visible = placeables.count { it.height > 0 }
        val height = placeables.sumOf { it.height } + gap * (visible - 1).coerceAtLeast(0)
        layout(width, height) {
            var y = 0
            var index = 0
            placeables.forEach { p ->
                if (p.height == 0) {
                    p.place(0, y)
                } else {
                    p.placeWithLayer(0, y, layerBlock = shapes.at(index, visible))
                    y += p.height + gap
                    index++
                }
            }
        }
    }
}

@Composable
fun SettingsGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    inset: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(top = if (title == null) 14.dp else 6.dp)) {
        if (title != null) SettingsGroupLabel(title)
        SegmentedColumn(Modifier.fillMaxWidth().padding(horizontal = inset), content)
    }
}

@Composable
fun SettingsGroupLabel(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.labelLargeEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 10.dp),
    )
}

private const val Idle = 0
private const val Hovered = 1
private const val Pressed = 2

@Composable
private fun rememberInteractionLevel(source: InteractionSource): State<Int> {
    val level = remember(source) { mutableIntStateOf(Idle) }
    LaunchedEffect(source) {
        val presses = mutableListOf<PressInteraction.Press>()
        val hovers = mutableListOf<HoverInteraction.Enter>()
        val focuses = mutableListOf<FocusInteraction.Focus>()
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> presses += interaction
                is PressInteraction.Release -> presses -= interaction.press
                is PressInteraction.Cancel -> presses -= interaction.press
                is HoverInteraction.Enter -> hovers += interaction
                is HoverInteraction.Exit -> hovers -= interaction.enter
                is FocusInteraction.Focus -> focuses += interaction
                is FocusInteraction.Unfocus -> focuses -= interaction.focus
            }
            level.intValue = when {
                presses.isNotEmpty() -> Pressed
                hovers.isNotEmpty() || focuses.isNotEmpty() -> Hovered
                else -> Idle
            }
        }
    }
    return level
}

@Composable
fun SettingsSegment(
    modifier: Modifier = Modifier,
    interactionSource: InteractionSource? = null,
    color: Color = segmentColor(),
    brush: Brush? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SegmentSurface(modifier, interactionSource?.let { rememberInteractionLevel(it) }, color, brush, content)
}

@Composable
private fun SegmentSurface(
    modifier: Modifier,
    level: State<Int>?,
    color: Color,
    brush: Brush?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val radius = level?.let {
        animateDpAsState(
            when (it.value) {
                Pressed -> PressedRadius
                Hovered -> HoveredRadius
                else -> 0.dp
            },
            MaterialTheme.motionScheme.fastSpatialSpec(),
            label = "segment-press",
        )
    }
    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (radius == null) {
                    Modifier
                } else {
                    Modifier.graphicsLayer {
                        val r = radius.value.coerceAtLeast(0.dp)
                        shape = RoundedCornerShape(r)
                        clip = r > 0.dp
                    }
                },
            )
            .then(if (brush != null) Modifier.background(brush) else Modifier.background(color)),
        content = content,
    )
}

@Composable
private fun RowBody(
    title: String,
    description: String?,
    enabled: Boolean,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    descriptionColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val alpha = if (enabled) 1f else DisabledAlpha
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLargeEmphasized, color = titleColor.copy(alpha = titleColor.alpha * alpha))
            if (!description.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = descriptionColor.copy(alpha = descriptionColor.alpha * alpha),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
private fun ClickableSegment(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.Button,
    brush: Brush? = null,
    content: @Composable (pressed: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val level = rememberInteractionLevel(source)
    SegmentSurface(modifier, level, segmentColor(), brush) {
        Box(
            Modifier
                .fillMaxWidth()
                .clickable(source, LocalIndication.current, enabled = enabled, role = role, onClick = onClick),
        ) { content(level.value == Pressed) }
    }
}

@Composable
private fun Chevron(tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(
        painterResource(Res.drawable.ic_chevron_right),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(22.dp),
    )
}

@Composable
fun SettingsRow(
    title: String,
    description: String? = null,
    enabled: Boolean = true,
    art: SettingsArt? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    ClickableSegment(enabled, onClick) { pressed ->
        RowBody(
            title = title,
            description = description,
            enabled = enabled,
            leading = art?.let { { PressableArtBadge(it, pressed) } },
            trailing = trailing,
        )
    }
}

@Composable
fun SettingsActionRow(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    art: SettingsArt? = null,
    onClick: () -> Unit,
) {
    SettingsRow(title = title, description = summary, enabled = enabled, art = art, onClick = onClick)
}

@Composable
fun SettingsMenuRow(
    title: String,
    subtitle: String? = null,
    leadingIcon: DrawableResource? = null,
    iconTint: Color? = null,
    art: SettingsArt? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    ClickableSegment(true, onClick) { pressed ->
        RowBody(
            title = title,
            description = subtitle,
            enabled = true,
            leading = when {
                art != null -> { { PressableArtBadge(art, pressed) } }
                leadingIcon != null -> {
                    {
                        Icon(
                            painterResource(leadingIcon),
                            contentDescription = null,
                            tint = iconTint ?: MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                else -> null
            },
            trailing = trailing ?: { Chevron() },
        )
    }
}

@Composable
fun SettingsBannerRow(
    title: String,
    subtitle: String,
    art: SettingsArt,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val brush = remember(cs.tertiaryContainer, cs.primaryContainer) {
        Brush.linearGradient(listOf(cs.tertiaryContainer, cs.primaryContainer))
    }
    ClickableSegment(true, onClick, brush = brush) { pressed ->
        RowBody(
            title = title,
            description = subtitle,
            enabled = true,
            leading = { PressableArtBadge(art, pressed, size = 48.dp, colors = ArtColors(cs.surface.copy(alpha = 0.55f), cs.tertiary)) },
            trailing = { Chevron(cs.onTertiaryContainer) },
            titleColor = cs.onTertiaryContainer,
            descriptionColor = cs.onTertiaryContainer.copy(alpha = 0.78f),
        )
    }
}

@Composable
fun SettingsSwitchRow(
    title: String,
    summary: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    SettingsSegment(interactionSource = source) {
        Box(
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    interactionSource = source,
                    indication = LocalIndication.current,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ),
        ) {
            RowBody(
                title = title,
                description = summary,
                enabled = enabled,
                trailing = {
                    Switch(
                        checked = checked,
                        onCheckedChange = null,
                        enabled = enabled,
                        thumbContent = if (checked) {
                            {
                                Icon(
                                    painterResource(Res.drawable.ic_check),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            }
                        } else {
                            null
                        },
                    )
                },
            )
        }
    }
}

@Composable
fun SettingsEditRow(
    title: String,
    value: String,
    enabled: Boolean = true,
    isPassword: Boolean = false,
    numeric: Boolean = false,
    placeholder: String? = null,
    onValueChanged: (String) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val description = when {
        isPassword && value.isNotEmpty() -> "••••••"
        value.isNotEmpty() -> value
        else -> placeholder
    }

    ClickableSegment(enabled, { showDialog = true }) {
        RowBody(
            title = title,
            description = description,
            enabled = enabled,
            trailing = {
                Icon(
                    painterResource(Res.drawable.ic_edit),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.8f else DisabledAlpha),
                    modifier = Modifier.size(20.dp),
                )
            },
        )
    }

    if (showDialog) {
        var text by remember { mutableStateOf(value) }
        var reveal by remember { mutableStateOf(false) }
        val save = {
            showDialog = false
            onValueChanged(text.trim())
        }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            icon = {
                Icon(
                    painterResource(Res.drawable.ic_edit),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
            },
            title = { Text(title, style = MaterialTheme.typography.headlineSmallEmphasized) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    placeholder = placeholder?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    visualTransformation = if (isPassword && !reveal) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    trailingIcon = if (isPassword) {
                        {
                            Icon(
                                painterResource(
                                    if (reveal) Res.drawable.ic_visibility_off
                                    else Res.drawable.ic_visibility,
                                ),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(22.dp)
                                    .clickable { reveal = !reveal },
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = save) { Text(stringResource(Res.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

private const val InlineChoiceLimit = 3
private const val InlineLabelLimit = 13

@Composable
fun <T> SettingsListRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean = true,
    onSelected: (T) -> Unit,
) {
    val noneLabel = stringResource(Res.string.option_none)
    val labelled = options.map { (value, label) -> value to label.ifBlank { noneLabel } }
    val inline = labelled.size in 2..InlineChoiceLimit && labelled.all { it.second.length <= InlineLabelLimit }
    if (inline) {
        SettingsChoiceRow(title, null, labelled, selected, enabled, onSelected = onSelected)
        return
    }
    val selectedLabel = labelled.firstOrNull { it.first == selected }?.second ?: labelled.firstOrNull()?.second.orEmpty()
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val turn by animateFloatAsState(if (open && enabled) 180f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "list-turn")
    SettingsSegment(interactionSource = source) {
        Box(
            Modifier
                .fillMaxWidth()
                .clickable(source, LocalIndication.current, enabled = enabled, role = Role.DropdownList) { open = !open },
        ) {
            RowBody(
                title = title,
                description = selectedLabel,
                enabled = enabled,
                descriptionColor = MaterialTheme.colorScheme.primary,
                trailing = {
                    Icon(
                        painterResource(Res.drawable.ic_keyboard_arrow_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else DisabledAlpha),
                        modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = turn },
                    )
                },
            )
        }
        AnimatedVisibility(
            visible = open && enabled,
            enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                labelled.forEach { (value, label) ->
                    ChoiceChip(label, value == selected) { onSelected(value) }
                }
            }
        }
    }
}

@Composable
fun ChoiceChip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(
        when {
            selected -> 20.dp
            pressed -> 16.dp
            else -> 12.dp
        },
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "chip-corner",
    )
    val container by animateColorAsState(if (selected) cs.primary else choiceIdleColor(), MaterialTheme.motionScheme.fastEffectsSpec(), label = "chip-bg")
    val content by animateColorAsState(if (selected) cs.onPrimary else cs.onSurface, MaterialTheme.motionScheme.fastEffectsSpec(), label = "chip-fg")
    val outline = if (selected) Color.Transparent else cs.outlineVariant.copy(alpha = 0.6f)
    Row(
        Modifier
            .height(40.dp)
            .graphicsLayer { alpha = if (enabled) 1f else DisabledAlpha }
            .clip(RoundedCornerShape(corner))
            .background(container)
            .border(1.dp, outline, RoundedCornerShape(corner))
            .selectable(selected, source, LocalIndication.current, enabled, Role.RadioButton, onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = selected,
            enter = expandHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(),
            exit = shrinkHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(Res.drawable.ic_check), contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

@Composable
fun <T> SettingsChoiceRow(
    title: String,
    summary: String?,
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean = true,
    icons: List<DrawableResource>? = null,
    onSelected: (T) -> Unit,
) {
    SettingsSegment {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
            val alpha = if (enabled) 1f else DisabledAlpha
            Text(title, style = MaterialTheme.typography.bodyLargeEmphasized, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            if (!summary.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
            Spacer(Modifier.height(12.dp))
            ConnectedChoices(options, selected, enabled, icons, onSelected)
        }
    }
}

@Composable
fun <T> ConnectedChoices(
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean = true,
    icons: List<DrawableResource>? = null,
    onSelected: (T) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val style = MaterialTheme.typography.labelLarge
        val density = LocalDensity.current
        val gap = 3.dp
        val slot = (maxWidth - gap * (options.size - 1)) / options.size.coerceAtLeast(1)
        val labels = options.map { it.second }
        val fits = remember(labels, style, slot, density) {
            labels.all { label ->
                val text = with(density) { measurer.measure(label, style, maxLines = 1, softWrap = false).size.width.toDp() }
                text + ChoiceGlyphRoom <= slot
            }
        }
        if (fits) {
            Row(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                options.forEachIndexed { i, (value, label) ->
                    ConnectedChoice(
                        label = label,
                        selected = value == selected,
                        first = i == 0,
                        last = i == options.lastIndex,
                        enabled = enabled,
                        icon = icons?.getOrNull(i),
                        modifier = Modifier.weight(1f),
                    ) { onSelected(value) }
                }
            }
        } else {
            FlowRow(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { (value, label) ->
                    ChoiceChip(label, value == selected, enabled) { onSelected(value) }
                }
            }
        }
    }
}

private val ChoiceGlyphRoom = 44.dp

@Composable
private fun ConnectedChoice(
    label: String,
    selected: Boolean,
    first: Boolean,
    last: Boolean,
    enabled: Boolean,
    icon: DrawableResource?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val full = 24.dp
    val small = 8.dp
    val squeeze = 14.dp
    val start by animateDpAsState(
        when {
            selected || first -> full
            pressed -> squeeze
            else -> small
        },
        spec,
        label = "choice-start",
    )
    val end by animateDpAsState(
        when {
            selected || last -> full
            pressed -> squeeze
            else -> small
        },
        spec,
        label = "choice-end",
    )
    val container by animateColorAsState(if (selected) cs.primary else choiceIdleColor(), MaterialTheme.motionScheme.fastEffectsSpec(), label = "choice-bg")
    val content by animateColorAsState(if (selected) cs.onPrimary else cs.onSurface, MaterialTheme.motionScheme.fastEffectsSpec(), label = "choice-fg")
    val shape = RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end)
    Row(
        modifier
            .height(48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else DisabledAlpha }
            .clip(shape)
            .background(container)
            .then(if (selected) Modifier else Modifier.border(1.dp, cs.outlineVariant.copy(alpha = 0.6f), shape))
            .selectable(selected, source, LocalIndication.current, enabled, Role.RadioButton, onClick)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val glyph = if (selected && icon == null) Res.drawable.ic_check else icon
        if (glyph != null) {
            Icon(painterResource(glyph), contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SettingsInfoRow(title: String, body: String) {
    SettingsSegment {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(Res.drawable.ic_fill_info),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun SettingsBusyIndicator() {
    LoadingIndicator(Modifier.size(32.dp))
}

typealias ColumnScopeAlias = ColumnScope

@Composable
fun SettingsHeader(
    title: String,
    subtitle: String? = null,
    art: SettingsArt? = null,
    titleIcon: DrawableResource? = null,
    scroll: ScrollState? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(start = 22.dp, end = 18.dp)) {
        PageBackButton()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = if (title.length > 22) {
                        MaterialTheme.typography.headlineMediumEmphasized
                    } else {
                        MaterialTheme.typography.headlineLargeEmphasized
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                art != null -> {
                    Spacer(Modifier.width(16.dp))
                    ArtBadge(art, size = 72.dp, spin = { (scroll?.value ?: 0) * 0.15f })
                }
                titleIcon != null -> {
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        painterResource(titleIcon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsPageScaffold(
    title: String,
    subtitle: String? = null,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    titleIcon: DrawableResource? = null,
    art: SettingsArt? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        SettingsHeader(title = title, subtitle = subtitle, art = art, titleIcon = titleIcon, scroll = scroll)
        Spacer(Modifier.height(6.dp))
        content()
        Spacer(Modifier.height(28.dp))
    }
}
