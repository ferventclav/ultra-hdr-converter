package com.example.ultrahdr.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.example.ultrahdr.processing.PhotoResult
import com.example.ultrahdr.ui.theme.UhdrTheme

/** Everything the screen can ask for. Keeps the screen itself free of the view model, so it can be drawn in tests. */
class ScreenActions(
    val pickPhotos: () -> Unit = {},
    val clearPhotos: () -> Unit = {},
    val tryAgain: () -> Unit = {},
    val stop: () -> Unit = {},
    val done: () -> Unit = {},
    val dismissNotice: () -> Unit = {},
    val turnOnMediaManagement: () -> Unit = {},
    val notNow: () -> Unit = {},
    val undo: () -> Unit = {},
    val openResult: (PhotoResult) -> Unit = {},
    /** The bar's Gallery button, for every photo that was converted. */
    val openConverted: () -> Unit = {}
)

/** The app: the screen wired to the view model, the photo picker and the window. */
@Composable
fun UltraHdrApp(viewModel: UltraHdrViewModel) {
    val state by viewModel.state.collectAsState()
    val gallery by viewModel.gallery.collectAsState()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100)
    ) { uris -> if (uris.isNotEmpty()) viewModel.addPhotos(uris) }

    val actions = remember(viewModel, picker) {
        ScreenActions(
            pickPhotos = viewModel::openGallery,
            clearPhotos = viewModel::clearPhotos,
            tryAgain = viewModel::convert,
            stop = viewModel::stop,
            done = viewModel::done,
            dismissNotice = viewModel::dismissNotice,
            turnOnMediaManagement = viewModel::openMediaManagement,
            notNow = viewModel::notNow,
            undo = viewModel::undo,
            openResult = { result -> openInGallery(context, result.viewableUri()) },
            openConverted = { openConvertedPhotos(context, viewModel.state.value.results.values) }
        )
    }

    // The grid lists photos on the phone. Google Photos, through Android's picker, is in its menu.
    val galleryActions = remember(viewModel, picker) {
        GalleryActions(
            close = viewModel::closeGallery,
            chooseAlbum = viewModel::chooseAlbum,
            toggle = viewModel::togglePhoto,
            toggleAll = viewModel::toggleAll,
            clearSelection = viewModel::clearSelection,
            undoClear = viewModel::undoClear,
            forgetCleared = viewModel::forgetCleared,
            convert = viewModel::convertSelected,
            allowAccess = viewModel::allowPhotoAccess,
            googlePhotos = {
                viewModel.closeGallery()
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        )
    }

    // HDR pictures only show as HDR while the window asks for it: the converted photos at the end.
    val showsHdr = state.phase is Phase.Finished
    val working = state.phase is Phase.Working
    val view = LocalView.current
    DisposableEffect(showsHdr) {
        context.findActivity()?.window?.colorMode =
            if (showsHdr) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
        onDispose { }
    }
    DisposableEffect(working) {
        view.keepScreenOn = working
        onDispose { view.keepScreenOn = false }
    }

    BackHandler(enabled = state.phase is Phase.Finished) { viewModel.done() }
    BackHandler(enabled = state.phase is Phase.Idle && state.photos.isNotEmpty()) { viewModel.clearPhotos() }
    // Registered last, so it wins while the grid is open.
    BackHandler(enabled = gallery.open) { viewModel.closeGallery() }

    Box(Modifier.fillMaxSize()) {
        UltraHdrScreen(state = state, actions = actions)
        AnimatedVisibility(
            visible = gallery.open,
            enter = fadeIn(tween(220, easing = OneUiEasing)) + slideInVertically(tween(320, easing = OneUiEasing)) { it / 10 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 10 }
        ) {
            GalleryPage(state = gallery, actions = galleryActions)
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * The address another app can open. A photo updated in place may only be known by its file path,
 * which Android does not let one app hand to another, so the address it was picked by is used.
 */
private fun PhotoResult.viewableUri(): Uri = output?.takeIf { it.scheme == "content" } ?: uri

private const val SAMSUNG_GALLERY = "com.sec.android.gallery3d"

/** A converted photo another app can be pointed at. */
private val PhotoResult.openable: Boolean get() = converted && output != null

/**
 * What the bar's open button says. A photo viewer is handed one photo at a time, so with
 * several converted the button opens Gallery itself rather than one photo out of the set.
 */
internal fun galleryButtonLabel(results: Collection<PhotoResult>): String =
    if (results.count { it.openable } > 1) "Open Gallery" else "Open photo"

/** The bar's open button: one converted photo opens in Gallery, several open Gallery. */
internal fun openConvertedPhotos(context: Context, results: Collection<PhotoResult>) {
    val openable = results.filter { it.openable }
    when {
        openable.size == 1 -> openInGallery(context, openable.single().viewableUri())
        // With no gallery app to open, showing one of the photos still beats doing nothing.
        openable.size > 1 -> if (!openGalleryApp(context)) openInGallery(context, openable.first().viewableUri())
    }
}

/** Opens the photo in Samsung Gallery when it is there, in any photo viewer otherwise. */
private fun openInGallery(context: Context, uri: Uri) {
    fun view(packageName: String?) = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/jpeg")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageName != null) setPackage(packageName)
    }
    context.startFirst(view(SAMSUNG_GALLERY), view(null))
}

/**
 * Opens Samsung Gallery, or the phone's own gallery app on other phones, the way its home screen
 * icon does. False when neither opens.
 */
private fun openGalleryApp(context: Context): Boolean {
    val likeTheIcon = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
    return context.startFirst(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(SAMSUNG_GALLERY).addFlags(likeTheIcon),
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY).addFlags(likeTheIcon)
    )
}

/** Starts the first of [intents] that some app on the phone can take. False when none can. */
private fun Context.startFirst(vararg intents: Intent): Boolean = intents.any { intent ->
    try {
        startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}

// ----------------------------------------------------------------------------------------------
// Screen
// ----------------------------------------------------------------------------------------------

/** What the enlarged header says for each moment of the app. */
internal data class Heading(val title: String, val info: String)

internal fun headingFor(state: UiState): Heading {
    val count = state.photos.size
    val photos = if (count == 1) "1 photo" else "$count photos"
    return when (val phase = state.phase) {
        Phase.Idle -> if (count == 0) {
            Heading("Ultra HDR", "")
        } else {
            Heading("Nothing changed", "Android wasn't allowed to update these photos. Try again and tap Allow.")
        }
        Phase.Preparing -> Heading("Getting ready", "Looking at $photos.")
        is Phase.Working -> Heading("Converting", "${minOf(state.results.size + 1, phase.total)} of ${phase.total}.")
        Phase.Undoing -> Heading("Undoing", "Putting the photos back as they were.")
        is Phase.Finished -> {
            val summary = summarize(state.results.values, count, phase.stopped)
            val info = when (summary.tone) {
                Tone.SUCCESS -> "Tap a photo to open it in Gallery."
                Tone.INFO -> "Photos that are HDR already, or have nothing bright to boost, stay as they are."
                Tone.ERROR -> if (count == 1) "Your photo was not changed." else "Your photos were not changed."
            }
            Heading(summary.title, info)
        }
    }
}

@Composable
fun UltraHdrScreen(
    state: UiState,
    actions: ScreenActions,
    modifier: Modifier = Modifier,
    thumbnail: @Composable (Uri, Modifier) -> Unit = { uri, m -> PhotoThumbnail(uri, m) }
) {
    // The page is recorded as it is drawn, so the bars can show a blurred copy of what scrolls under them.
    val backdrop = rememberGlassBackdrop()
    val grid = rememberLazyGridState()
    val page = MaterialTheme.colorScheme.background
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val heading = headingFor(state)

    BoxWithConstraints(modifier.fillMaxSize().background(page)) {
        // The enlarged header takes about a third of the screen, so the first card sits under the thumb.
        val headerHeight = (maxHeight * 0.34f).coerceIn(210.dp, 330.dp)
        val headerPx = with(LocalDensity.current) { headerHeight.toPx() }
        val screenWidth = maxWidth
        // The empty screen has no enlarged header: its title sits in the middle, as on One UI's Software update page.
        val empty = state.phase is Phase.Idle && state.photos.isEmpty()
        val emptyHeight = (maxHeight - statusTop - navBottom - BAR_ROOM).coerceAtLeast(240.dp)
        val collapsed by remember(headerPx) {
            derivedStateOf { grid.firstVisibleItemIndex > 0 || grid.firstVisibleItemScrollOffset > headerPx * 0.6f }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                state = grid,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .widthIn(max = PAGE_MAX_WIDTH)
                    .fillMaxSize()
                    .glassSource(backdrop),
                contentPadding = PaddingValues(
                    start = ScreenInset,
                    end = ScreenInset,
                    top = statusTop,
                    bottom = navBottom + BAR_ROOM
                ),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!empty) {
                    val undo = actions.undo.takeIf { state.phase is Phase.Finished && state.canUndo }
                    wide("header") { ExpandedHeader(heading, headerHeight, screenWidth, undo) }
                    // On the empty screen the notice is the status line instead, clear of the top bar.
                    state.notice?.let { notice -> wide("notice") { NoticeCard(notice, actions.dismissNotice) } }
                }
                if (empty) {
                    emptyState(state, actions, emptyHeight, screenWidth)
                } else {
                    photoPage(state, actions, thumbnail)
                }
            }
        }

        TopBar(backdrop, page, statusTop, heading.title, collapsed)
        BottomBar(state, actions, backdrop, page, Modifier.align(Alignment.BottomCenter))

        if (state.askAboutPrompts) {
            Dialog(
                onDismissRequest = actions.notNow,
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                val blurred = rememberBlurBehind()
                PromptCard(
                    blurred = blurred,
                    onTurnOn = actions.turnOnMediaManagement,
                    onNotNow = actions.notNow,
                    modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp)
                )
            }
        }
    }
}

private val PAGE_MAX_WIDTH = 720.dp
private val BAR_MAX_WIDTH = 520.dp
/** Room left under the last card for the floating toolbar. */
private val BAR_ROOM = 96.dp
/** The collapsed app bar under the status bar. The page fades out under it. */
private val TOP_BAR_HEIGHT = 66.dp

/** A grid row that spans the full width. */
private fun LazyGridScope.wide(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

/**
 * One UI's enlarged header: a big centred title low in the top third, one info line under it, and
 * its small button when there is something to undo. With large text it grows downwards instead of
 * pushing the title up under the top bar.
 */
@Composable
private fun ExpandedHeader(words: Heading, height: Dp, screenWidth: Dp, undo: (() -> Unit)?) {
    // Scales down on narrow screens, the way the Knowledge app's clamp(28px, 8.8vw, 36px) does.
    val titleSize = (screenWidth.value * 0.088f).coerceIn(28f, 36f).sp
    // A button fading out with the old heading must not act again once Undo has started.
    val currentUndo by rememberUpdatedState(undo)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .padding(start = 12.dp, end = 12.dp, top = TOP_BAR_HEIGHT, bottom = 30.dp)
            // TalkBack reads the new heading out when it changes: getting ready, each photo, done.
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedContent(
            targetState = words to (undo != null),
            transitionSpec = { fadeIn(tween(240, easing = OneUiEasing)) togetherWith fadeOut(tween(120)) },
            label = "heading"
        ) { (shown, offersUndo) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = shown.title,
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = titleSize, lineHeight = titleSize * 1.15f),
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() }
                )
                if (shown.info.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = shown.info,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 320.dp)
                    )
                }
                if (offersUndo) {
                    Spacer(Modifier.height(10.dp))
                    HeaderButton("Undo", onClick = { currentUndo?.invoke() })
                }
            }
        }
    }
}

/** The collapsed app bar: the page melts into the page colour under it, and the title appears once the header has scrolled away. */
@Composable
private fun TopBar(backdrop: GlassBackdrop, page: Color, statusTop: Dp, title: String, collapsed: Boolean) {
    Box(Modifier.fillMaxWidth().height(statusTop + TOP_BAR_HEIGHT)) {
        FadingGlass(backdrop = backdrop, color = page, fromTop = true, modifier = Modifier.fillMaxSize())
        AnimatedVisibility(
            visible = collapsed,
            enter = fadeIn(tween(200, easing = OneUiEasing)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.padding(top = statusTop).height(TOP_BAR_HEIGHT).fillMaxWidth()
        ) {
            Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun NoticeCard(notice: Notice, onDismiss: () -> Unit) {
    OneUiCard(Modifier.padding(bottom = 6.dp)) {
        Row(Modifier.padding(start = CardPadding, top = 8.dp, bottom = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (notice.isError) Icons.Rounded.ErrorOutline else Icons.Rounded.Info,
                contentDescription = null,
                tint = if (notice.isError) UhdrTheme.colors.danger else UhdrTheme.colors.accent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(20.dp))
            Text(
                text = notice.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = UhdrTheme.colors.textSecondary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ----------------------------------------------------------------------------------------------
// Empty state
// ----------------------------------------------------------------------------------------------

/**
 * Built like One UI 9's Software update page: the app's name large in the accent colour in the
 * middle of the screen, one status line under it, and a soft blue and amber light behind. The
 * status line also carries the last notice, such as what Undo put back, since a card at the top
 * would sit under the top bar's fade. The only extra is a way back to media management, and only
 * while it is off.
 */
private fun LazyGridScope.emptyState(state: UiState, actions: ScreenActions, height: Dp, screenWidth: Dp) {
    wide("empty") {
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val titleSize = (screenWidth.value * 0.11f).coerceIn(34f, 46f).sp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .drawBehind { glow(dark) }
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Ultra HDR",
                style = MaterialTheme.typography.displaySmall.copy(fontSize = titleSize, lineHeight = titleSize * 1.15f),
                color = UhdrTheme.colors.accentInk,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(4.dp))
            val notice = state.notice
            Text(
                text = notice?.text ?: "No photos selected.",
                style = MaterialTheme.typography.titleMedium,
                color = if (notice?.isError == true) UhdrTheme.colors.danger else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
                // Read out when it changes, so TalkBack hears what Undo did.
                modifier = Modifier.widthIn(max = 340.dp).semantics { liveRegion = LiveRegionMode.Polite }
            )
            if (!state.updatesWithoutAsking) {
                Spacer(Modifier.height(20.dp))
                HeaderButton(text = "Stop edit prompts", onClick = actions.turnOnMediaManagement)
            }
        }
    }
}

/** Two soft pools of light behind the title: blue above right, amber below left. */
private fun DrawScope.glow(dark: Boolean) {
    val w = size.width
    val r = w * 0.48f
    fun pool(color: Color, alpha: Float, at: Offset) = drawCircle(
        brush = Brush.radialGradient(
            0f to color.copy(alpha = alpha),
            0.55f to color.copy(alpha = alpha * 0.45f),
            1f to color.copy(alpha = 0f),
            center = at,
            radius = r
        ),
        radius = r,
        center = at
    )
    val blueAt = center + Offset(w * 0.07f, -w * 0.20f)
    val amberAt = center + Offset(-w * 0.09f, w * 0.22f)
    if (dark) {
        pool(Color(0xFF2F55C8), 0.42f, blueAt)
        pool(Color(0xFFC0682E), 0.34f, amberAt)
    } else {
        pool(Color(0xFF7FA2FF), 0.30f, blueAt)
        pool(Color(0xFFFFA25E), 0.28f, amberAt)
    }
}

// ----------------------------------------------------------------------------------------------
// Photos, while converting and after
// ----------------------------------------------------------------------------------------------

private fun LazyGridScope.photoPage(state: UiState, actions: ScreenActions, thumbnail: @Composable (Uri, Modifier) -> Unit) {
    val phase = state.phase
    if (phase is Phase.Finished) {
        wide("summary") {
            SummaryCard(summarize(state.results.values, state.photos.size, phase.stopped))
        }
    }
    itemsIndexed(state.photos, key = { _, it -> it.uri.toString() }) { index, item ->
        val result = state.results[item.uri]
        val tileState = when {
            result != null -> TileState.DONE
            (phase as? Phase.Working)?.currentUri == item.uri -> TileState.WORKING
            else -> TileState.WAITING
        }
        PhotoTile(
            item = item,
            // Read out by TalkBack. File names like 20261108_181428.jpg are no use spoken.
            label = "Photo ${index + 1} of ${state.photos.size}",
            state = tileState,
            result = result,
            onClick = { if (result?.converted == true) actions.openResult(result) },
            thumbnail = thumbnail,
            modifier = Modifier.animateItem()
        )
    }
}

@Composable
private fun SummaryCard(summary: Summary) {
    OneUiCard(Modifier.padding(bottom = 8.dp)) {
        Spacer(Modifier.height(6.dp))
        summary.lines.forEachIndexed { index, line ->
            if (index > 0) RowDivider()
            val (icon, tint) = when (line.kind) {
                LineKind.UPDATED -> Icons.Rounded.CheckCircle to UhdrTheme.colors.accent
                LineKind.COPIED -> Icons.Rounded.ContentCopy to UhdrTheme.colors.accent
                LineKind.SKIPPED -> Icons.Rounded.RemoveCircleOutline to UhdrTheme.colors.textSecondary
                LineKind.FAILED -> Icons.Rounded.ErrorOutline to UhdrTheme.colors.danger
                LineKind.NOT_STARTED -> Icons.Rounded.Block to UhdrTheme.colors.textSecondary
            }
            InfoRow(icon, line.text.replaceFirstChar { it.uppercase() }, null, iconTint = tint)
        }
        val notes = summary.notes + listOfNotNull(summary.reassurance)
        if (notes.isNotEmpty()) {
            if (summary.lines.isNotEmpty()) RowDivider(start = CardPadding)
            Column(Modifier.padding(horizontal = CardPadding, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                notes.forEach { note ->
                    Text(note, style = MaterialTheme.typography.bodyMedium, color = UhdrTheme.colors.textSecondary)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

// ----------------------------------------------------------------------------------------------
// Floating toolbar
// ----------------------------------------------------------------------------------------------

private enum class BarMode { PICK, PREPARING, RETRY, WORKING, UNDOING, OPEN, DONE }

@Composable
private fun BottomBar(state: UiState, actions: ScreenActions, backdrop: GlassBackdrop, page: Color, modifier: Modifier) {
    val mode = when (state.phase) {
        Phase.Idle -> if (state.photos.isEmpty()) BarMode.PICK else BarMode.RETRY
        Phase.Preparing -> BarMode.PREPARING
        is Phase.Working -> BarMode.WORKING
        Phase.Undoing -> BarMode.UNDOING
        is Phase.Finished -> if (state.results.values.any { it.openable }) BarMode.OPEN else BarMode.DONE
    }
    Box(modifier.fillMaxWidth()) {
        // The page melts into its own colour under the toolbar, as it does under the app bar.
        FadingGlass(backdrop = backdrop, color = page, fromTop = false, modifier = Modifier.matchParentSize())
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            GlassSurface(
                backdrop = backdrop,
                shape = CircleShape,
                tint = UhdrTheme.colors.glassToolbar,
                blurRadius = 14.dp,
                modifier = Modifier
                    .widthIn(max = BAR_MAX_WIDTH)
                    .fillMaxWidth()
                    .shadow(
                        elevation = 14.dp,
                        shape = CircleShape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.12f),
                        spotColor = Color.Black.copy(alpha = 0.25f)
                    )
            ) {
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = { fadeIn(tween(220, easing = OneUiEasing)) togetherWith fadeOut(tween(120)) },
                    label = "bar"
                ) { shown ->
                    Box(Modifier.fillMaxWidth().padding(6.dp)) {
                        when (shown) {
                            BarMode.PICK -> PrimaryButton("Select photos", actions.pickPhotos, Modifier.fillMaxWidth(), icon = Icons.Rounded.AddPhotoAlternate)
                            BarMode.PREPARING -> ProgressRow("Getting photos ready", null, "Cancel", actions.clearPhotos, warns = false)
                            BarMode.RETRY -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SecondaryButton("Cancel", actions.clearPhotos)
                                PrimaryButton("Try again", actions.tryAgain, Modifier.weight(1f))
                            }
                            BarMode.WORKING -> {
                                val total = (state.phase as? Phase.Working)?.total ?: state.photos.size
                                val finished = state.results.size
                                ProgressRow(
                                    label = "Converting ${minOf(finished + 1, total)} of $total",
                                    progress = if (total == 0) 0f else finished.toFloat() / total,
                                    action = "Stop",
                                    onAction = actions.stop
                                )
                            }
                            // Putting originals back is never stopped half way, so it has no button.
                            BarMode.UNDOING -> ProgressRow("Putting photos back", null, null, {})
                            // "Done" takes only the room it needs, so the longer label never gets cut off.
                            BarMode.OPEN -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SecondaryButton("Done", actions.done)
                                PrimaryButton(galleryButtonLabel(state.results.values), actions.openConverted, Modifier.weight(1f))
                            }
                            BarMode.DONE -> PrimaryButton("Done", actions.done, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

/**
 * A label over a slim One UI progress rail, with one action beside it (none when [action] is null).
 * [progress] null means still working it out.
 */
@Composable
private fun ProgressRow(label: String, progress: Float?, action: String?, onAction: () -> Unit, warns: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(start = 18.dp, end = if (action == null) 18.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            val rail = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
            if (progress == null) {
                LinearProgressIndicator(modifier = rail, color = UhdrTheme.colors.accent, trackColor = UhdrTheme.colors.rail, gapSize = 0.dp)
            } else {
                val shown by animateFloatAsState(progress, tween(300, easing = OneUiEasing), label = "progress")
                LinearProgressIndicator(
                    progress = { shown },
                    modifier = rail,
                    color = UhdrTheme.colors.accent,
                    trackColor = UhdrTheme.colors.rail,
                    // One plain rail, without the gap and end dot newer Material adds.
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
            }
        }
        if (action != null) {
            Spacer(Modifier.width(14.dp))
            SecondaryButton(action, onAction, textColor = if (warns) UhdrTheme.colors.danger else MaterialTheme.colorScheme.onSurface)
        }
    }
}

// ----------------------------------------------------------------------------------------------
// The one question the app asks
// ----------------------------------------------------------------------------------------------

/**
 * Blurs and dims the screen behind the dialog window, as One UI dialogs do. Returns false where the
 * phone has cross-window blur off (battery saver, some older screens), so the card can be solid.
 */
@Composable
private fun rememberBlurBehind(): Boolean {
    val view = LocalView.current
    val radius = with(LocalDensity.current) { 24.dp.roundToPx() }
    val enabled = remember {
        runCatching { view.context.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled }.getOrDefault(false)
    }
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.setDimAmount(if (enabled) 0.18f else 0.32f)
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = radius }
        }
    }
    return enabled
}

/** A One UI dialog: title, one paragraph, two text buttons split by a hairline. */
@Composable
fun PromptCard(blurred: Boolean, onTurnOn: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(if (blurred) UhdrTheme.colors.glassDialog else MaterialTheme.colorScheme.surface)
            .padding(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 8.dp)
    ) {
        Text(
            text = "Update photos without being asked?",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Android asks before an app changes your photos. Turn on media management for " +
                "Ultra HDR once in Settings, and your photos convert straight away from then on.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onNotNow, modifier = Modifier.weight(1f).fillMaxHeight()) {
                Text("Not now", style = MaterialTheme.typography.labelLarge, color = UhdrTheme.colors.accentInk)
            }
            VerticalDivider(Modifier.height(20.dp), thickness = 1.dp, color = UhdrTheme.colors.hairline)
            TextButton(onClick = onTurnOn, modifier = Modifier.weight(1f).fillMaxHeight()) {
                Text("Settings", style = MaterialTheme.typography.labelLarge, color = UhdrTheme.colors.accentInk)
            }
        }
    }
}
