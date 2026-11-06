@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class,
)

package dev.cluvex.zedsecure.ui.settings

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.materialkolor.ktx.toHct
import com.materialkolor.palettes.TonalPalette
import com.materialkolor.ktx.toneColor
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Immutable
class SettingsArt(
    val key: String,
    val icon: DrawableResource,
    val shape: RoundedPolygon,
    val hue: Int,
)

object SettingsArts {
    val Support = SettingsArt("support", Res.drawable.ic_fill_favorite, MaterialShapes.Heart, 1)
    val Appearance = SettingsArt("appearance", Res.drawable.ic_fill_palette, MaterialShapes.Flower, 0)
    val Mode = SettingsArt("mode", Res.drawable.ic_fill_toggle_on, MaterialShapes.Pill, 2)
    val Monitor = SettingsArt("monitor", Res.drawable.ic_fill_monitor_heart, MaterialShapes.Sunny, 3)
    val Ai = SettingsArt("ai", Res.drawable.ic_fill_auto_awesome, MaterialShapes.SoftBurst, 4)
    val Tour = SettingsArt("tour", Res.drawable.ic_fill_explore, MaterialShapes.Arch, 5)
    val Routing = SettingsArt("routing", Res.drawable.ic_fill_alt_route, MaterialShapes.Diamond, 3)
    val PerApp = SettingsArt("per-app", Res.drawable.ic_fill_apps, MaterialShapes.Cookie4Sided, 0)
    val Assets = SettingsArt("assets", Res.drawable.ic_fill_database, MaterialShapes.Gem, 2)
    val Dns = SettingsArt("dns", Res.drawable.ic_fill_dns, MaterialShapes.Clover4Leaf, 5)
    val Tor = SettingsArt("tor", Res.drawable.ic_fill_hub, MaterialShapes.Ghostish, 4)
    val Ssh = SettingsArt("ssh", Res.drawable.ic_fill_terminal, MaterialShapes.Square, 1)
    val Vpn = SettingsArt("vpn", Res.drawable.ic_fill_vpn_key, MaterialShapes.PuffyDiamond, 0)
    val Core = SettingsArt("core", Res.drawable.ic_fill_memory, MaterialShapes.Cookie12Sided, 3)
    val Mux = SettingsArt("mux", Res.drawable.ic_fill_merge, MaterialShapes.Clover8Leaf, 5)
    val Fragment = SettingsArt("fragment", Res.drawable.ic_fill_grain, MaterialShapes.PixelCircle, 1)
    val SingBox = SettingsArt("sing-box", Res.drawable.ic_fill_deployed_code, MaterialShapes.Bun, 2)
    val Observatory = SettingsArt("observatory", Res.drawable.ic_fill_radar, MaterialShapes.VerySunny, 4)
    val Advanced = SettingsArt("advanced", Res.drawable.ic_fill_tune, MaterialShapes.Puffy, 0)
    val Map = SettingsArt("map", Res.drawable.ic_fill_map, MaterialShapes.Oval, 2)
    val SpeedTest = SettingsArt("speed-test", Res.drawable.ic_fill_speed, MaterialShapes.Slanted, 3)
    val Update = SettingsArt("update", Res.drawable.ic_fill_sync, MaterialShapes.SoftBoom, 5)
    val Rate = SettingsArt("rate", Res.drawable.ic_fill_star, MaterialShapes.Burst, 1)
    val Privacy = SettingsArt("privacy", Res.drawable.ic_fill_policy, MaterialShapes.Pentagon, 0)
    val About = SettingsArt("about", Res.drawable.ic_fill_info, MaterialShapes.Circle, 4)
}

@Immutable
data class ArtColors(val container: Color, val content: Color)

private const val HUE_STEP = 60.0

@Composable
fun artColors(hue: Int): ArtColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return remember(scheme.primary, dark, hue) {
        val base = scheme.primary.toHct()
        val chroma = base.chroma.coerceAtMost(40.0).let { if (it < 8.0) it else it.coerceAtLeast(28.0) }
        val palette = TonalPalette.fromHueAndChroma((base.hue + hue * HUE_STEP) % 360.0, chroma)
        if (dark) ArtColors(palette.toneColor(30), palette.toneColor(90))
        else ArtColors(palette.toneColor(90), palette.toneColor(25))
    }
}

data class ArtTransition(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

val LocalArtTransition = staticCompositionLocalOf<ArtTransition?> { null }

@Composable
fun Modifier.sharedArt(art: SettingsArt): Modifier {
    val transition = LocalArtTransition.current ?: return this
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    val bounds = remember(spec) { BoundsTransform { _, _ -> spec } }
    return with(transition.shared) {
        this@sharedArt.sharedElement(
            rememberSharedContentState("settings-art-${art.key}"),
            transition.visibility,
            boundsTransform = bounds,
        )
    }
}

@Composable
fun ArtBadge(
    art: SettingsArt,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    spin: () -> Float = { 0f },
    squash: () -> Float = { 1f },
    colors: ArtColors = artColors(art.hue),
) {
    val shape = art.shape.toShape()
    Box(modifier.sharedArt(art).size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    rotationZ = spin()
                    val s = squash()
                    scaleX = s
                    scaleY = s
                }
                .clip(shape)
                .background(colors.container),
        )
        Icon(
            painterResource(art.icon),
            contentDescription = null,
            tint = colors.content,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

@Composable
fun PressableArtBadge(
    art: SettingsArt,
    pressed: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    colors: ArtColors = artColors(art.hue),
) {
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val spin = animateFloatAsState(if (pressed) 40f else 0f, spec, label = "art-spin")
    val squash = animateFloatAsState(if (pressed) 0.86f else 1f, spec, label = "art-squash")
    ArtBadge(art, modifier, size, { spin.value }, { squash.value }, colors)
}
