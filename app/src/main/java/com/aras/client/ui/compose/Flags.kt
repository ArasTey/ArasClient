package com.aras.client.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import java.util.Locale

/**
 * Country flag rendered from the bundled SVG set (assets/flags/<iso>.svg)
 * instead of the system emoji font, so it stays sharp at every density.
 */
@Composable
fun FlagIcon(
    iso: String?,
    modifier: Modifier = Modifier,
    width: Dp = 20.dp,
    height: Dp = 15.dp,
) {
    if (iso.isNullOrBlank()) return
    val context = LocalContext.current
    val shape = RoundedCornerShape(3.dp)
    val rim = LocalGlassPalette.current.hairline
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data("file:///android_asset/flags/${iso.lowercase(Locale.ROOT)}.svg")
            .decoderFactory(SvgDecoder.Factory())
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(rim)
            .border(0.5.dp, rim, shape)
    )
}
