package com.example.ultrahdr.ui

import android.net.Uri
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.ultrahdr.processing.Outcome
import com.example.ultrahdr.processing.PhotoItem
import com.example.ultrahdr.processing.PhotoResult
import com.example.ultrahdr.ui.theme.OneUiBlue
import com.example.ultrahdr.ui.theme.UhdrTheme

/** One UI's standard motion curve. */
val OneUiEasing = CubicBezierEasing(0.22f, 0.25f, 0f, 1f)

/** One UI spacing: 10dp from the screen edge, 20dp inside a card. */
val ScreenInset = 10.dp
val CardPadding = 20.dp

/** A One UI container: near-white (or near-black) on the grey page, 28dp corners, no outline. */
@Composable
fun OneUiCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface),
        content = content
    )
}

/** The small grey heading at the top of a card ("How it works"). */
@Composable
fun CardHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = UhdrTheme.colors.textSecondary,
        modifier = Modifier.padding(start = CardPadding, end = CardPadding, top = CardPadding, bottom = 4.dp)
    )
}

/**
 * A One UI list row: a 24dp icon in the accent colour, an 18sp title and one 14sp info line.
 * Rows keep the same padding whatever they hold, so a card never shifts.
 */
@Composable
fun InfoRow(
    icon: ImageVector?,
    title: String,
    info: String?,
    modifier: Modifier = Modifier,
    iconTint: Color = OneUiBlue,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = CardPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (info != null) {
                Text(info, style = MaterialTheme.typography.bodyMedium, color = UhdrTheme.colors.textSecondary)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** The hairline between rows, inset to start under the row text. */
@Composable
fun RowDivider(start: Dp = CardPadding + 44.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = start, end = CardPadding),
        thickness = 1.dp,
        color = UhdrTheme.colors.hairline
    )
}

/** Pressed buttons shrink a little, the way One UI's do. */
@Composable
private fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(180, easing = OneUiEasing), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** The accent button: One UI blue, white label, fully rounded. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 52.dp
) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = OneUiBlue, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 24.dp),
        interactionSource = interaction,
        elevation = null,
        modifier = modifier.height(height).pressScale(interaction)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The quieter button: blue text on a pale blue tint. */
@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    textColor: Color = UhdrTheme.colors.accentInk
) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = UhdrTheme.colors.accentTint, contentColor = textColor),
        contentPadding = PaddingValues(horizontal = 22.dp),
        interactionSource = interaction,
        elevation = null,
        modifier = modifier.height(height).pressScale(interaction)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Default thumbnail: loads the photo itself. Tests swap this for a drawn stand-in. */
@Composable
fun PhotoThumbnail(uri: Uri, modifier: Modifier = Modifier) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(uri).crossfade(true).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
    )
}

enum class TileState { WAITING, WORKING, DONE }

/** One photo in the grid: the picture and what has happened to it. */
@Composable
fun PhotoTile(
    item: PhotoItem,
    state: TileState,
    result: PhotoResult?,
    onClick: () -> Unit,
    thumbnail: @Composable (Uri, Modifier) -> Unit,
    modifier: Modifier = Modifier
) {
    // A finished photo shows its HDR version.
    val shown = result?.output?.takeIf { result.converted && it.scheme == "content" } ?: item.uri
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = result?.converted == true, onClickLabel = "Open in Gallery", onClick = onClick)
            .semantics { contentDescription = item.name }
    ) {
        thumbnail(shown, Modifier.fillMaxSize())

        when (state) {
            TileState.WAITING -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.34f)))
            TileState.WORKING -> Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(Modifier.size(26.dp), color = Color.White, strokeWidth = 2.5.dp)
            }
            TileState.DONE -> Unit
        }

        val badge = Modifier.align(Alignment.BottomStart).padding(6.dp)
        when (result?.outcome) {
            Outcome.UPDATED, Outcome.COPIED -> Box(
                modifier = badge.size(22.dp).background(OneUiBlue, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Check, contentDescription = "Converted", tint = Color.White, modifier = Modifier.size(15.dp))
            }
            Outcome.ALREADY_HDR -> TileChip("HDR", badge)
            Outcome.NO_HIGHLIGHTS -> TileChip("Skipped", badge)
            Outcome.LEFT_ALONE -> TileChip("Left alone", badge)
            Outcome.FAILED -> TileChip("Failed", badge, color = UhdrTheme.colors.danger)
            null -> if (item.alreadyHdr && !item.redoable && state == TileState.WAITING) TileChip("HDR", badge)
        }
    }
}

/** A small label over a photo, on the kit's photo glass. */
@Composable
fun TileChip(label: String, modifier: Modifier = Modifier, color: Color = Color.Black.copy(alpha = 0.62f)) {
    Box(modifier.background(color, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}
