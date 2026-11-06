package dev.cluvex.zedsecure.ui.settings

import dev.cluvex.zedsecure.domain.model.ConnectButtonStyle
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.connect_style_bar
import dev.cluvex.zedsecure.shared.resources.connect_style_hero
import dev.cluvex.zedsecure.shared.resources.connect_style_icon
import dev.cluvex.zedsecure.shared.resources.connect_style_pill
import dev.cluvex.zedsecure.shared.resources.connect_style_ring
import dev.cluvex.zedsecure.shared.resources.connect_style_switch
import org.jetbrains.compose.resources.StringResource

fun ConnectButtonStyle.labelRes(): StringResource = when (this) {
    ConnectButtonStyle.Pill -> Res.string.connect_style_pill
    ConnectButtonStyle.Hero -> Res.string.connect_style_hero
    ConnectButtonStyle.Ring -> Res.string.connect_style_ring
    ConnectButtonStyle.Bar -> Res.string.connect_style_bar
    ConnectButtonStyle.Icon -> Res.string.connect_style_icon
    ConnectButtonStyle.Switch -> Res.string.connect_style_switch
}
