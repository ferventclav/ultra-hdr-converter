package com.example.ultrahdr.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ultrahdr.processing.HdrStrength
import com.example.ultrahdr.ui.theme.DarkUhdrColors
import com.example.ultrahdr.ui.theme.OneUiDarkCard
import com.example.ultrahdr.ui.theme.UltraHdrTheme
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

enum class PreviewMode { HDR, ORIGINAL, BOOST_MAP }

/** A screen takes about this long to switch into HDR after an HDR picture appears. */
private const val HDR_SETTLE_MILLIS = 1200L

/**
 * Full-screen look at one photo before converting it. Always dark, like a gallery viewer, so the
 * bright parts have something to stand out against. It is drawn inside the activity's own window
 * on purpose: only that window is switched to HDR.
 *
 * [screenHeadroom] is how much brighter than white the screen can go right now, or null when the
 * phone can't say.
 */
@Composable
fun PreviewOverlay(
    preview: PreviewUi,
    strength: HdrStrength,
    screenHeadroom: Float?,
    onStrength: (HdrStrength) -> Unit,
    onClose: () -> Unit,
    initialMode: PreviewMode = PreviewMode.HDR
) {
    UltraHdrTheme(darkTheme = true) {
        var mode by remember(preview.item.uri) { mutableStateOf(initialMode) }
        var held by remember { mutableStateOf(false) }
        val ready = preview.hdr != null && !preview.loading && !preview.failed
        val shownMode = if (held && ready) PreviewMode.ORIGINAL else mode

        // Don't complain about the screen before it has had the chance to switch.
        var settled by remember(preview.item.uri) { mutableStateOf(false) }
        LaunchedEffect(preview.item.uri) {
            delay(HDR_SETTLE_MILLIS)
            settled = true
        }
        val screenIsFlat = ready && settled && screenHeadroom != null && screenHeadroom < 1.05f

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                // Swallow touches so nothing behind the preview reacts.
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
        AmbientWash(preview.ambient, Modifier.fillMaxSize())
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        ) {
            val photo: @Composable (Modifier) -> Unit = { modifier ->
                Photo(
                    preview = preview,
                    shownMode = shownMode,
                    ready = ready,
                    onHold = { held = it },
                    onMode = { mode = it },
                    modifier = modifier
                )
            }
            val panel: @Composable (Modifier) -> Unit = { modifier ->
                Panel(
                    preview = preview,
                    mode = shownMode,
                    strength = strength,
                    onStrength = onStrength,
                    showStrength = ready && !preview.alreadyHdr,
                    screenIsFlat = screenIsFlat,
                    modifier = modifier
                )
            }

            if (maxWidth > maxHeight && maxWidth >= 600.dp) {
                // Sideways: the controls sit beside the photo, so the photo keeps the full height.
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        TopBar(preview.item.name, onClose)
                        photo(Modifier.weight(1f).fillMaxWidth())
                    }
                    Surface(
                        color = PanelGlass,
                        border = BorderStroke(1.dp, DarkUhdrColors.glassEdge),
                        shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                        modifier = Modifier.width(340.dp).fillMaxHeight()
                    ) {
                        panel(
                            Modifier
                                .verticalScroll(rememberScrollState())
                                .statusBarsPadding()
                                .navigationBarsPadding()
                                .padding(20.dp)
                        )
                    }
                }
            } else {
                // The photo always keeps at least 45% of the height, however long the text gets.
                val sheetLimit = maxHeight * 0.55f
                Column(Modifier.fillMaxSize()) {
                    TopBar(preview.item.name, onClose)
                    photo(Modifier.weight(1f).fillMaxWidth())
                    Surface(
                        color = PanelGlass,
                        border = BorderStroke(1.dp, DarkUhdrColors.glassEdge),
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(contentAlignment = Alignment.TopCenter) {
                            panel(
                                Modifier
                                    .widthIn(max = 560.dp)
                                    .fillMaxWidth()
                                    .heightIn(max = sheetLimit)
                                    .verticalScroll(rememberScrollState())
                                    .navigationBarsPadding()
                                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp)
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

/** The control panel is see-through, so the blurred wash behind it turns it into frosted glass. */
private val PanelGlass = OneUiDarkCard.copy(alpha = 0.70f)

/**
 * The photo's own colours, blurred and dimmed, filling the screen behind it in place of flat black
 * bars. It stays dark enough that HDR highlights still stand well clear of it.
 */
@Composable
private fun AmbientWash(wash: Bitmap?, modifier: Modifier = Modifier) {
    if (wash == null) return
    val image = remember(wash) { wash.asImageBitmap() }
    // Fades in, because it arrives a moment after the preview opens.
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { arrived = true }
    val shown by animateFloatAsState(if (arrived) 1f else 0f, tween(500), label = "wash")
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        // The picture is only a few dozen pixels wide. Smooth scaling plus the blur leaves colour, no detail.
        filterQuality = FilterQuality.High,
        alpha = 0.46f * shown,
        modifier = modifier.blur(48.dp)
    )
}

@Composable
private fun TopBar(name: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 16.dp)
        )
    }
}

/** The picture, with the switch that says which version is on screen floating over its lower edge. */
@Composable
private fun Photo(
    preview: PreviewUi,
    shownMode: PreviewMode,
    ready: Boolean,
    onHold: (Boolean) -> Unit,
    onMode: (PreviewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.pointerInput(ready) {
            if (ready) {
                detectTapGestures(onPress = {
                    onHold(true)
                    tryAwaitRelease()
                    onHold(false)
                })
            }
        },
        contentAlignment = Alignment.Center
    ) {
        when {
            preview.loading -> CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            preview.failed -> Text(
                "This photo couldn't be loaded.",
                style = MaterialTheme.typography.bodyMedium,
                color = DarkUhdrColors.textSecondary
            )
            else -> {
                val bitmap: Bitmap? = when (shownMode) {
                    PreviewMode.HDR -> preview.hdr
                    PreviewMode.ORIGINAL -> preview.original
                    PreviewMode.BOOST_MAP -> preview.boostMap ?: preview.hdr
                }
                if (bitmap != null) {
                    val image = remember(bitmap) { bitmap.asImageBitmap() }
                    Image(
                        bitmap = image,
                        contentDescription = when (shownMode) {
                            PreviewMode.HDR -> "HDR version"
                            PreviewMode.ORIGINAL -> "Original photo"
                            PreviewMode.BOOST_MAP -> "Map of the parts that get brighter"
                        },
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.Medium,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                val floating = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                when {
                    preview.alreadyHdr -> VersionLabel(
                        text = if (shownMode == PreviewMode.ORIGINAL) "Without HDR" else "HDR",
                        modifier = floating
                    )
                    preview.noHighlights -> Unit
                    else -> SegmentedPicker(
                        options = listOf(
                            PreviewMode.HDR to "HDR",
                            PreviewMode.ORIGINAL to "Original",
                            PreviewMode.BOOST_MAP to "Boost map"
                        ),
                        // While the photo is held this moves to Original, so the comparison is unmistakable.
                        selected = shownMode,
                        onSelect = onMode,
                        modifier = floating.widthIn(max = 340.dp).fillMaxWidth(),
                        // Dark grey, not black, so the switch still reads as one control over the
                        // black bars above and below a wide photo.
                        trackColor = Color(0xE626262A),
                        thumbColor = Color(0xFF5A5A62),
                        textColor = Color.White,
                        mutedTextColor = Color.White.copy(alpha = 0.78f)
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionLabel(text: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = 0.62f), modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

/** What will happen to this photo, in words, and how strong to make it. */
@Composable
private fun Panel(
    preview: PreviewUi,
    mode: PreviewMode,
    strength: HdrStrength,
    onStrength: (HdrStrength) -> Unit,
    showStrength: Boolean,
    screenIsFlat: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            text = previewHeadline(preview),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White
        )
        previewDetail(preview, mode)?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = DarkUhdrColors.textSecondary)
        }

        if (showStrength) {
            Spacer(Modifier.height(16.dp))
            SegmentedPicker(
                options = listOf(
                    HdrStrength.SUBTLE to "Subtle",
                    HdrStrength.BALANCED to "Balanced",
                    HdrStrength.VIVID to "Vivid"
                ),
                selected = strength,
                onSelect = onStrength,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (screenIsFlat) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Rounded.BrightnessLow,
                    contentDescription = null,
                    tint = DarkUhdrColors.warning,
                    modifier = Modifier.padding(top = 1.dp).size(16.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Your screen isn't showing HDR right now, so HDR and Original look the same. " +
                        "Turn the brightness up and power saving off. The boost map still shows what will change.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DarkUhdrColors.textSecondary
                )
            }
        }
    }
}

internal fun previewHeadline(preview: PreviewUi): String = when {
    preview.loading -> "Working out the HDR version"
    preview.failed -> "No preview"
    preview.alreadyHdr -> "This photo is already HDR"
    preview.noHighlights -> "Nothing here to boost"
    else -> "Highlights up to ${formatBoost(preview.peakBoost)} brighter"
}

internal fun previewDetail(preview: PreviewUi, mode: PreviewMode = PreviewMode.HDR): String? = when {
    preview.loading || preview.failed -> null
    preview.alreadyHdr -> "It will be skipped. Hold the photo to see it without HDR."
    preview.noHighlights -> "It has no bright highlights at this strength, so it will be left as it is."
    mode == PreviewMode.BOOST_MAP ->
        "White marks the parts that get brighter, ${formatShare(preview.boostedShare).lowercase(Locale.US)} of the photo. Black stays as it is."
    else -> "${formatShare(preview.boostedShare)} of the photo gets brighter. Hold the photo to compare with the original."
}

internal fun formatBoost(boost: Float): String {
    val rounded = (boost * 10f).roundToInt() / 10f
    return if (rounded == rounded.toInt().toFloat()) "${rounded.toInt()}x" else String.format(Locale.US, "%.1fx", rounded)
}

internal fun formatShare(share: Float): String {
    val percent = share * 100f
    return when {
        percent < 1f -> "Less than 1%"
        else -> "${percent.roundToInt()}%"
    }
}
