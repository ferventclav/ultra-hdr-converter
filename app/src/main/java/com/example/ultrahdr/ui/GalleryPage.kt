package com.example.ultrahdr.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.HdrOn
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ultrahdr.processing.LocalPhoto
import com.example.ultrahdr.processing.LocalPhotos
import com.example.ultrahdr.processing.PhotoAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/** Everything the photo grid can ask for. */
class GalleryActions(
    val close: () -> Unit = {},
    val chooseAlbum: (String?) -> Unit = {},
    val toggle: (Long) -> Unit = {},
    val toggleAll: (List<Long>) -> Unit = {},
    val clearSelection: () -> Unit = {},
    val convert: () -> Unit = {},
    val allowAccess: () -> Unit = {},
    val googlePhotos: () -> Unit = {}
)

/**
 * The app's own photo grid, built to Galaxy Enhance-X's home screen: a navy header with the app
 * name, a black sheet with a handle, pill tabs, a three-column grid and a floating pill bar.
 * Sizes are Enhance-X's own, which grow with the screen's width from a 360 dp base.
 * It lists only photos stored on the phone, so each one converts at full quality in place.
 */
@Composable
fun GalleryPage(
    state: GalleryState,
    actions: GalleryActions,
    modifier: Modifier = Modifier,
    thumbnail: @Composable (LocalPhoto, Modifier) -> Unit = { photo, m -> GalleryThumb(photo, m) }
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val m = Metrics((maxWidth.value / 360f).coerceIn(0.88f, 1.143f))
        val look = rememberLook()
        val backdrop = rememberGlassBackdrop()
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val albums = remember(state.photos) { LocalPhotos.albums(state.photos) }
        val shown = remember(state.photos, state.album) {
            if (state.album == null) state.photos else state.photos.filter { it.album == state.album }
        }
        val shownIds = remember(shown) { shown.map { it.id } }
        val grid = rememberLazyGridState()
        LaunchedEffect(state.album) { grid.scrollToItem(0) }
        val count = state.selected.size

        Box(
            Modifier
                .fillMaxSize()
                .background(look.header)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        ) {
            Header(m, look, statusTop, count, actions.googlePhotos)

            // The sheet: black, rounded at the top, 96 dp down.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = m(96f))
                    .clip(RoundedCornerShape(topStart = m(26f), topEnd = m(26f)))
                    .background(look.sheet)
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = m(104f)),
                    state = grid,
                    modifier = Modifier.fillMaxSize().glassSource(backdrop),
                    contentPadding = PaddingValues(
                        start = m(10f),
                        end = m(10f),
                        top = m(96f),
                        bottom = navBottom + m(56f + 10f + 24f)
                    ),
                    horizontalArrangement = Arrangement.spacedBy(m(2f)),
                    verticalArrangement = Arrangement.spacedBy(m(2f))
                ) {
                    if (state.access == PhotoAccess.SOME) {
                        item(key = "some", span = { GridItemSpan(maxLineSpan) }) { SomeAccessRow(m, look, actions.allowAccess) }
                    }
                    items(shown, key = { it.id }, contentType = { "photo" }) { photo ->
                        GalleryTile(
                            photo = photo,
                            picked = photo.id in state.selected,
                            selecting = count > 0,
                            m = m,
                            look = look,
                            onClick = { actions.toggle(photo.id) },
                            thumbnail = thumbnail
                        )
                    }
                }

                // Photos slide under the handle and tabs and fade out just above the first row.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(m(94f))
                        .background(Brush.verticalGradient(0f to look.sheet, 0.85f to look.sheet, 1f to look.sheet.copy(alpha = 0f)))
                )
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().height(m(20f)), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(m(44f), m(4f)).clip(CircleShape).background(look.handle))
                    }
                    TabRow(
                        m = m,
                        look = look,
                        albums = albums,
                        chosen = state.album,
                        allPicked = shownIds.isNotEmpty() && state.selected.containsAll(shownIds),
                        onChoose = actions.chooseAlbum,
                        onPickAll = { actions.toggleAll(shownIds) }
                    )
                }

                val message = when {
                    state.loading -> null
                    state.access == PhotoAccess.NONE -> "Allow photo access to see your photos here."
                    shown.isEmpty() -> "No photos here."
                    else -> null
                }
                if (message != null) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(top = m(96f), bottom = navBottom + m(90f)).padding(horizontal = 32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(message, style = m.text(17f), color = look.dim, textAlign = TextAlign.Center)
                        if (state.access == PhotoAccess.NONE) {
                            Spacer(Modifier.height(m(20f)))
                            PrimaryButton("Allow", actions.allowAccess, height = m(40f))
                        }
                    }
                }
            }

            BottomPill(
                m = m,
                look = look,
                backdrop = backdrop,
                count = count,
                actions = actions,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/** Enhance-X sizes its screens from a 360 dp design, scaled by the screen's width. */
private class Metrics(val scale: Float) {
    operator fun invoke(base: Float): Dp = (base * scale).dp
    /** Enhance-X's sizes, in sp so text still grows with the phone's font size setting. */
    @Composable
    fun text(base: Float, weight: FontWeight = FontWeight.Normal): TextStyle {
        val size: TextUnit = (base * scale).sp
        return MaterialTheme.typography.bodyLarge.copy(fontSize = size, lineHeight = size * 1.25f, fontWeight = weight)
    }
}

/** Colours measured from Enhance-X (dark) and matched for the light theme. */
private class Look(
    val header: Brush,
    val sheet: Color,
    val title: Color,
    val divider: Color,
    val handle: Color,
    val tabFill: Color,
    val tabOn: Color,
    val tabOff: Color,
    val ring: Color,
    val placeholder: Color,
    val pill: Color,
    val pillPicked: Color,
    val pillOn: Color,
    val pillOff: Color,
    val dim: Color,
    /** A white bar needs a soft shadow, or it disappears over a white photo. */
    val pillShadow: Boolean
)

/** Enhance-X's thumbnail selection colour. */
private val PickedColor = Color(0xFF5E4FE6)

@Composable
private fun rememberLook(): Look {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val surface = MaterialTheme.colorScheme.surface
    return remember(dark, surface) {
        if (dark) {
            Look(
                header = Brush.verticalGradient(0f to Color(0xFF02021A), 0.55f to Color(0xFF020422), 1f to Color(0xFF030A2E)),
                sheet = Color(0xFF010102),
                title = Color.White,
                divider = Color(0xFFCCCFE2),
                handle = Color(0xFFC9C9CB),
                tabFill = Color(0xFF1B1B1B),
                tabOn = Color.White,
                tabOff = Color(0xFFAFAFAF),
                ring = Color.White.copy(alpha = 0.32f),
                placeholder = Color(0xFF141414),
                pill = Color(0xFF1E1E1E).copy(alpha = 0.9f),
                pillPicked = Color.White.copy(alpha = 0.1f),
                pillOn = Color(0xFFFCFCFF),
                pillOff = Color(0xFFCCCCCE),
                dim = Color.White.copy(alpha = 0.72f),
                pillShadow = false
            )
        } else {
            Look(
                header = Brush.verticalGradient(0f to Color(0xFFF2F3FA), 1f to Color(0xFFDDE3F8)),
                sheet = surface,
                title = Color(0xFF0A0A0C),
                divider = Color(0xFF8A8D9C),
                handle = Color(0xFFB4B4B8),
                tabFill = Color(0xFFEDEDF0),
                tabOn = Color(0xFF0A0A0C),
                tabOff = Color(0xFF6E6E73),
                ring = Color.Black.copy(alpha = 0.22f),
                placeholder = Color(0xFFEDEDF0),
                pill = Color.White.copy(alpha = 0.9f),
                pillPicked = Color.Black.copy(alpha = 0.06f),
                pillOn = Color(0xFF0A0A0C),
                pillOff = Color(0xFF4A4A4E),
                dim = Color.Black.copy(alpha = 0.6f),
                pillShadow = true
            )
        }
    }
}

/** "Ultra HDR | Select photos", as Enhance-X writes "Enhance-X | Cinematic glow". */
@Composable
private fun Header(m: Metrics, look: Look, statusTop: Dp, count: Int, onGooglePhotos: () -> Unit) {
    val top = maxOf(m(52f), statusTop + 6.dp)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = top, start = m(20f), end = m(6f)).height(m(30f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Ultra HDR", style = m.text(16f), color = look.title, maxLines = 1)
        Spacer(Modifier.width(m(6f)))
        Box(Modifier.size(m(1f), m(20f)).background(look.divider))
        Spacer(Modifier.width(m(6f)))
        Text(
            text = if (count == 0) "Select photos" else "$count selected",
            style = m.text(17.5f, FontWeight.Bold),
            color = look.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        var menu by remember { mutableStateOf(false) }
        Box {
            Box(
                modifier = Modifier.size(m(48f)).clip(CircleShape).clickable(onClickLabel = "More options") { menu = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More options", tint = look.title, modifier = Modifier.size(m(24f)))
            }
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                DropdownMenuItem(
                    text = { Text("Google Photos", style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        menu = false
                        onGooglePhotos()
                    }
                )
            }
        }
    }
}

/** Pill tabs for the albums, and the round button that picks every photo in the tab. */
@Composable
private fun TabRow(
    m: Metrics,
    look: Look,
    albums: List<String>,
    chosen: String?,
    allPicked: Boolean,
    onChoose: (String?) -> Unit,
    onPickAll: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = m(12f), start = m(10f), end = m(10f)).height(m(48f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The chosen tab is kept in view, also when the grid opens again on an album further along.
        val tabs = rememberLazyListState()
        LaunchedEffect(chosen, albums) {
            val index = if (chosen == null) 0 else albums.indexOf(chosen) + 1
            if (index >= 0) tabs.animateScrollToItem(index)
        }
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            LazyRow(
                state = tabs,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = m(4f), end = m(16f)),
                horizontalArrangement = Arrangement.spacedBy(m(6f)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item(key = "all") { AlbumTab(m, look, "All", chosen == null) { onChoose(null) } }
                items(albums, key = { it }) { album ->
                    AlbumTab(m, look, LocalPhotos.albumLabel(album), chosen == album) { onChoose(album) }
                }
            }
            // Tabs that run on fade into the sheet before the round button.
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(m(20f))
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(0f to look.sheet.copy(alpha = 0f), 1f to look.sheet))
            )
        }
        Box(
            modifier = Modifier
                .size(m(48f))
                .clip(CircleShape)
                .clickable(onClickLabel = if (allPicked) "Clear this tab" else "Select all in this tab", onClick = onPickAll),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(m(33f))
                    .clip(CircleShape)
                    .background(if (allPicked) PickedColor else Color.Transparent)
                    .border(1.dp, if (allPicked) PickedColor else look.ring, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.DoneAll,
                    contentDescription = null,
                    tint = if (allPicked) Color.White else look.tabOn,
                    modifier = Modifier.size(m(18f))
                )
            }
        }
    }
}

@Composable
private fun AlbumTab(m: Metrics, look: Look, label: String, chosen: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(if (chosen) look.tabFill else Color.Transparent, tween(200, easing = OneUiEasing), label = "tab")
    Box(
        modifier = Modifier
            .widthIn(min = m(142f))
            .height(m(40f))
            .clip(RoundedCornerShape(m(20f)))
            .background(fill)
            .selectable(selected = chosen, role = Role.Tab, onClick = onClick)
            .padding(horizontal = m(24f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = m.text(16f, if (chosen) FontWeight.Bold else FontWeight.Normal),
            color = if (chosen) look.tabOn else look.tabOff,
            maxLines = 1
        )
    }
}

@Composable
private fun GalleryTile(
    photo: LocalPhoto,
    picked: Boolean,
    selecting: Boolean,
    m: Metrics,
    look: Look,
    onClick: () -> Unit,
    thumbnail: @Composable (LocalPhoto, Modifier) -> Unit
) {
    val name = remember(photo.takenAt) {
        "Photo, " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(photo.takenAt))
    }
    // Circles fade in once picking has started, as in Samsung's own selection mode.
    val circles by animateFloatAsState(if (selecting) 1f else 0f, tween(180, easing = OneUiEasing), label = "circles")
    Box(
        Modifier
            .aspectRatio(1f)
            .background(look.placeholder)
            .toggleable(value = picked, role = Role.Checkbox, onValueChange = { onClick() })
            .semantics { contentDescription = name }
    ) {
        thumbnail(photo, Modifier.fillMaxSize())
        if (picked) Box(Modifier.fillMaxSize().border(m(3f), PickedColor))
        if (circles > 0f) PickCircle(picked, m, Modifier.align(Alignment.TopStart).padding(m(8f)).alpha(circles))
    }
}

/** The selection circle: an empty white ring, or Enhance-X's violet with a tick. */
@Composable
private fun PickCircle(picked: Boolean, m: Metrics, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(m(22f))
            .clip(CircleShape)
            .background(if (picked) PickedColor else Color.Black.copy(alpha = 0.26f))
            .border(1.5.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (picked) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(m(15f)))
    }
}

@Composable
private fun SomeAccessRow(m: Metrics, look: Look, onAllow: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(bottom = m(8f))
            .clip(RoundedCornerShape(m(20f)))
            .background(look.tabFill)
            .padding(start = m(18f), end = m(6f), top = m(6f), bottom = m(6f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Ultra HDR can see only some of your photos.",
            style = m.text(14f),
            color = look.tabOn,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(m(10f)))
        Box(
            modifier = Modifier
                .height(m(36f))
                .clip(CircleShape)
                .background(PickedColor)
                .clickable(onClick = onAllow)
                .padding(horizontal = m(16f)),
            contentAlignment = Alignment.Center
        ) {
            Text("Allow all", style = m.text(14f, FontWeight.Bold), color = Color.White)
        }
    }
}

/** Enhance-X's floating bar: 232 by 56, three items, the middle one on a lighter pill. */
@Composable
private fun BottomPill(m: Metrics, look: Look, backdrop: GlassBackdrop, count: Int, actions: GalleryActions, modifier: Modifier) {
    Box(modifier.navigationBarsPadding().padding(bottom = m(10f))) {
        GlassSurface(
            backdrop = backdrop,
            shape = RoundedCornerShape(m(30f)),
            tint = look.pill,
            blurRadius = 14.dp,
            modifier = Modifier
                .width(m(232f))
                .height(m(56f))
                .then(
                    if (look.pillShadow) Modifier.shadow(
                        elevation = 12.dp,
                        shape = RoundedCornerShape(m(30f)),
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.10f),
                        spotColor = Color.Black.copy(alpha = 0.18f)
                    ) else Modifier
                )
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = m(8f)), verticalAlignment = Alignment.CenterVertically) {
                PillItem(m, look, Icons.Outlined.Close, "Cancel", highlighted = false, enabled = true, onClick = actions.close)
                PillItem(
                    m, look, Icons.Outlined.HdrOn,
                    label = if (count == 0) "Convert" else "Convert $count",
                    highlighted = true,
                    enabled = count > 0,
                    onClick = actions.convert
                )
                PillItem(m, look, Icons.Outlined.Deselect, "Clear", highlighted = false, enabled = count > 0, onClick = actions.clearSelection)
            }
        }
    }
}

@Composable
private fun RowScope.PillItem(
    m: Metrics,
    look: Look,
    icon: ImageVector,
    label: String,
    highlighted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    // The middle item turns violet once there is something to convert, so it reads as the action.
    val active = highlighted && enabled
    val fill by animateColorAsState(
        when {
            active -> PickedColor
            highlighted -> look.pillPicked
            else -> Color.Transparent
        },
        tween(200, easing = OneUiEasing),
        label = "pill"
    )
    val color = when {
        active -> Color.White
        highlighted -> look.pillOn
        else -> look.pillOff
    }
    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .requiredWidth(m(80f))
                .height(m(48f))
                .clip(RoundedCornerShape(m(24f)))
                .background(fill)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .alpha(if (enabled) 1f else 0.5f),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(m(24f)))
                Spacer(Modifier.height(m(2f)))
                Text(
                    text = label,
                    style = m.text(10f, if (highlighted) FontWeight.Bold else FontWeight.Normal),
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ----------------------------------------------------------------------------------------------
// Thumbnails
// ----------------------------------------------------------------------------------------------

/** The phone's own cached thumbnail, with an HDR label when the photo is HDR already. */
@Composable
fun GalleryThumb(photo: LocalPhoto, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { 160.dp.roundToPx() }
    val key = Thumbs.key(photo)
    val bitmap by produceState(Thumbs.bitmap(key), key) {
        if (value == null) value = withContext(Thumbs.io) { Thumbs.load(context, photo, px) }
    }
    val hdr by produceState(Thumbs.hdr(key), key) {
        if (value == null) value = withContext(Thumbs.io) { Thumbs.checkHdr(context, photo) }
    }
    Box(modifier) {
        bitmap?.let { shown ->
            val image = remember(shown) { shown.asImageBitmap() }
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (hdr == true) TileChip("HDR", Modifier.align(Alignment.BottomStart).padding(5.dp))
    }
}

/** Kept for the life of the app, keyed by photo and last change, so a converted photo is read again. */
private object Thumbs {
    @OptIn(ExperimentalCoroutinesApi::class)
    val io = Dispatchers.IO.limitedParallelism(4)

    private val bitmaps = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val hdrFlags = ConcurrentHashMap<String, Boolean>()

    fun key(photo: LocalPhoto) = "${photo.id}:${photo.modified}"
    fun bitmap(key: String): Bitmap? = bitmaps.get(key)
    fun hdr(key: String): Boolean? = hdrFlags[key]

    fun load(context: Context, photo: LocalPhoto, px: Int): Bitmap? =
        runCatching { context.contentResolver.loadThumbnail(photo.uri, Size(px, px), null) }
            .getOrNull()
            ?.also { bitmaps.put(key(photo), it) }

    fun checkHdr(context: Context, photo: LocalPhoto): Boolean =
        LocalPhotos.looksHdr(context, photo.uri).also { hdrFlags[key(photo)] = it }
}
