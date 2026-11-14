package com.example.ultrahdr.ui

import android.net.Uri
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.ultrahdr.processing.Outcome
import com.example.ultrahdr.processing.PhotoItem
import com.example.ultrahdr.processing.PhotoResult
import com.example.ultrahdr.ui.theme.UhdrTheme

/** One UI's standard motion curve. */
val OneUiEasing = CubicBezierEasing(0.22f, 0.25f, 0f, 1f)

/** A rounded card on the page background, with the hairline One UI uses instead of a shadow. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, UhdrTheme.colors.hairline),
        content = content
    )
}

/** A row of choices where exactly one is picked. The highlight slides to the chosen one. */
@Composable
fun <T> SegmentedPicker(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackColor: Color = UhdrTheme.colors.segmentTrack,
    thumbColor: Color = UhdrTheme.colors.segmentThumb,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    mutedTextColor: Color = UhdrTheme.colors.textSecondary
) {
    val thumbShape = RoundedCornerShape(11.dp)
    BoxWithConstraints(
        modifier = modifier
            .height(44.dp)
            .clip(MaterialTheme.shapes.small)
            .background(trackColor)
            .padding(3.dp)
    ) {
        val segmentWidth = maxWidth / options.size
        val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
        val thumbOffset by animateDpAsState(
            targetValue = segmentWidth * index,
            animationSpec = tween(durationMillis = 260, easing = OneUiEasing),
            label = "segment"
        )
        Box(
            Modifier
                .offset(x = thumbOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .shadow(elevation = 1.dp, shape = thumbShape)
                .background(thumbColor, thumbShape)
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(thumbShape)
                        .selectable(
                            selected = isSelected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onSelect(value) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) textColor else mutedTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
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

enum class TileState { IDLE, WAITING, WORKING, DONE }

/** One photo in the grid: the picture, what will happen or has happened to it, and a remove button. */
@Composable
fun PhotoTile(
    item: PhotoItem,
    state: TileState,
    result: PhotoResult?,
    willBeCopied: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
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
            .clickable(onClickLabel = if (result?.converted == true) "Open" else "Preview", onClick = onClick)
            .semantics { contentDescription = item.name }
    ) {
        thumbnail(shown, Modifier.fillMaxSize())

        when (state) {
            TileState.WAITING -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)))
            TileState.WORKING -> Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(Modifier.size(26.dp), color = Color.White, strokeWidth = 2.5.dp)
            }
            else -> Unit
        }

        val badge = Modifier.align(Alignment.BottomStart).padding(6.dp)
        when {
            result != null -> when (result.outcome) {
                Outcome.UPDATED, Outcome.COPIED -> Box(
                    modifier = badge.size(22.dp).background(UhdrTheme.colors.success, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = "Converted", tint = Color.White, modifier = Modifier.size(15.dp))
                }
                Outcome.ALREADY_HDR -> TileChip("HDR", badge)
                Outcome.NO_HIGHLIGHTS -> TileChip("Skipped", badge)
                Outcome.LEFT_ALONE -> TileChip("Left alone", badge)
                Outcome.FAILED -> TileChip("Failed", badge, color = UhdrTheme.colors.danger)
            }
            state == TileState.IDLE && item.alreadyHdr -> TileChip("HDR", badge)
            state == TileState.IDLE && willBeCopied -> TileChip("Copy", badge)
        }

        if (onRemove != null) {
            // 40dp touch target around a small visible button.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Remove", onClick = onRemove)
                    .semantics { contentDescription = "Remove ${item.name}" },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(22.dp).background(Color.Black.copy(alpha = 0.58f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun TileChip(label: String, modifier: Modifier = Modifier, color: Color = Color.Black.copy(alpha = 0.74f)) {
    Box(modifier.background(color, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}
