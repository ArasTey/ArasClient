package dev.cluvex.zedsecure.ui.navigation

import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.ic_dns
import dev.cluvex.zedsecure.shared.resources.ic_encrypted
import dev.cluvex.zedsecure.shared.resources.ic_home
import dev.cluvex.zedsecure.shared.resources.ic_settings
import dev.cluvex.zedsecure.shared.resources.nav_home
import dev.cluvex.zedsecure.shared.resources.nav_locked
import dev.cluvex.zedsecure.shared.resources.nav_servers
import dev.cluvex.zedsecure.shared.resources.nav_settings
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource

enum class TopDestination(
    val labelRes: StringResource,
    val icon: DrawableResource,
) {
    Home(Res.string.nav_home, Res.drawable.ic_home),
    Servers(Res.string.nav_servers, Res.drawable.ic_dns),
    Vault(Res.string.nav_locked, Res.drawable.ic_encrypted),
    Settings(Res.string.nav_settings, Res.drawable.ic_settings),
}
