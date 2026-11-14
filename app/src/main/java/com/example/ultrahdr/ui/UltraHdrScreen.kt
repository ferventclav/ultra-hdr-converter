package com.example.ultrahdr.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.Display
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ultrahdr.processing.HdrStrength
import com.example.ultrahdr.processing.PhotoItem
import com.example.ultrahdr.processing.PhotoResult
import com.example.ultrahdr.ui.theme.OneUiBlue
import com.example.ultrahdr.ui.theme.UhdrTheme
import java.util.function.Consumer

/** Everything the screen can ask for. Keeps the screen itself free of the view model, so it can be drawn in tests. */
class ScreenActions(
    val pickPhotos: () -> Unit = {},
    val removePhoto: (Uri) -> Unit = {},
    val clearPhotos: () -> Unit = {},
    val setStrength: (HdrStrength) -> Unit = {},
    val setReplaceOriginal: (Boolean) -> Unit = {},
    val setRedo: (Boolean) -> Unit = {},
    val copyReport: () -> Unit = {},
    val convert: () -> Unit = {},
    val stop: () -> Unit = {},
    val done: () -> Unit = {},
    val dismissNotice: () -> Unit = {},
    val openPreview: (PhotoItem) -> Unit = {},
    val closePreview: () -> Unit = {},
    val openResult: (PhotoResult) -> Unit = {},
    val openFirstResult: () -> Unit = {}
)

/** The app: the screen wired to the view model, the photo picker and the window. */
@Composable
fun UltraHdrApp(viewModel: UltraHdrViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100)
    ) { uris -> if (uris.isNotEmpty()) viewModel.addPhotos(uris) }

    val actions = remember(viewModel, picker) {
        ScreenActions(
            pickPhotos = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            removePhoto = viewModel::removePhoto,
            clearPhotos = viewModel::clearPhotos,
            setStrength = viewModel::setStrength,
            setReplaceOriginal = viewModel::setReplaceOriginal,
            setRedo = viewModel::setRedo,
            copyReport = {
                context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(
                    android.content.ClipData.newPlainText("Ultra HDR report", viewModel.copyReport())
                )
            },
            convert = viewModel::convert,
            stop = viewModel::stop,
            done = viewModel::done,
            dismissNotice = viewModel::dismissNotice,
            openPreview = viewModel::openPreview,
            closePreview = viewModel::closePreview,
            openResult = { result -> openInGallery(context, result.viewableUri()) },
            openFirstResult = {
                viewModel.state.value.results.values.firstOrNull { it.converted && it.output != null }
                    ?.let { openInGallery(context, it.viewableUri()) }
            }
        )
    }

    // HDR pictures only show as HDR while the window asks for it. Ask only when there is one on screen.
    val showsHdr = state.preview != null || state.phase is Phase.Finished
    val working = state.phase is Phase.Working
    val view = LocalView.current
    DisposableEffect(showsHdr) {
        val window = context.findActivity()?.window
        window?.colorMode = if (showsHdr) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
        onDispose { }
    }
    DisposableEffect(working) {
        view.keepScreenOn = working
        onDispose { view.keepScreenOn = false }
    }

    BackHandler(enabled = state.preview != null) { viewModel.closePreview() }
    BackHandler(enabled = state.preview == null && state.phase is Phase.Finished) { viewModel.done() }

    UltraHdrScreen(state = state, actions = actions, screenHeadroom = rememberScreenHeadroom())
}

/**
 * How much brighter than normal white the screen can go right now. 1 means it is not showing HDR,
 * null means the phone can't say.
 */
@Composable
private fun rememberScreenHeadroom(): Float? {
    val view = LocalView.current
    var headroom by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(view) {
        val display: Display? = runCatching { view.display }.getOrNull()
        if (display == null || !display.isHdrSdrRatioAvailable) {
            headroom = null
            onDispose { }
        } else {
            val listener = Consumer<Display> { headroom = it.hdrSdrRatio }
            headroom = display.hdrSdrRatio
            display.registerHdrSdrRatioChangedListener(view.context.mainExecutor, listener)
            onDispose { display.unregisterHdrSdrRatioChangedListener(listener) }
        }
    }
    return headroom
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

/** Opens the photo in Samsung Gallery when it is there, in any photo viewer otherwise. */
private fun openInGallery(context: Context, uri: Uri) {
    fun view(packageName: String?) = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/jpeg")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageName != null) setPackage(packageName)
    }
    try {
        context.startActivity(view("com.sec.android.gallery3d"))
    } catch (_: Exception) {
        try {
            context.startActivity(view(null))
        } catch (_: Exception) {
        }
    }
}

// ----------------------------------------------------------------------------------------------
// Screen
// ----------------------------------------------------------------------------------------------

@Composable
fun UltraHdrScreen(
    state: UiState,
    actions: ScreenActions,
    modifier: Modifier = Modifier,
    screenHeadroom: Float? = null,
    thumbnail: @Composable (Uri, Modifier) -> Unit = { uri, m -> PhotoThumbnail(uri, m) }
) {
    val phase = state.phase
    val idle = phase is Phase.Idle
    // The page is recorded as it is drawn, so the floating bar and the strip behind the status
    // bar can show a blurred copy of the photos scrolling under them.
    val backdrop = rememberGlassBackdrop()

    Box(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).ambientGlow(),
        contentAlignment = Alignment.TopCenter
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = { BottomBar(state, actions, backdrop) },
            // On a tablet or a phone held sideways the page stays a comfortable column in the
            // middle, clear of the camera cut-out, and does not stretch from edge to edge.
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .widthIn(max = PAGE_MAX_WIDTH)
        ) { inner ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                modifier = Modifier.fillMaxSize().glassSource(backdrop),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = inner.calculateTopPadding(),
                    bottom = inner.calculateBottomPadding() + 12.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                wide("header") { Header() }

                state.notice?.let { notice ->
                    wide("notice") { NoticeCard(notice, actions.dismissNotice) }
                }

                if (phase is Phase.Finished) {
                    wide("summary") {
                        Column {
                            SummaryCard(summarize(state.results.values, state.photos.size, phase.stopped, state.redo))
                            TextButton(onClick = actions.copyReport, modifier = Modifier.align(Alignment.End)) {
                                Text("Copy report", color = OneUiBlue)
                            }
                        }
                    }
                    items(state.photos, key = { it.uri.toString() }) { item ->
                        val result = state.results[item.uri]
                        PhotoTile(
                            item = item,
                            state = if (result == null) TileState.WAITING else TileState.DONE,
                            result = result,
                            willBeCopied = false,
                            onClick = { if (result?.converted == true) actions.openResult(result) },
                            onRemove = null,
                            thumbnail = thumbnail
                        )
                    }
                } else {
                    if (state.photos.isEmpty()) {
                        wide("empty") { EmptyCard(actions.pickPhotos) }
                    } else {
                        wide("queue") { QueueHeader(state, actions) }
                        items(state.photos, key = { it.uri.toString() }) { item ->
                            val result = state.results[item.uri]
                            val tileState = when {
                                idle -> TileState.IDLE
                                result != null -> TileState.DONE
                                (phase as? Phase.Working)?.currentUri == item.uri -> TileState.WORKING
                                else -> TileState.WAITING
                            }
                            PhotoTile(
                                item = item,
                                state = tileState,
                                result = result,
                                willBeCopied = state.replaceOriginal && item.checked && !item.onDevice,
                                onClick = { if (idle) actions.openPreview(item) },
                                onRemove = if (idle) ({ actions.removePhoto(item.uri) }) else null,
                                thumbnail = thumbnail,
                                modifier = Modifier.animateItem()
                            )
                        }
                        if (idle) {
                            item(key = "add") { AddTile(actions.pickPhotos, Modifier.animateItem()) }
                            wide("hint") {
                                Text(
                                    text = "Tap a photo to see the HDR effect before you convert.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = UhdrTheme.colors.textSecondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    wide("options") { OptionsCard(state, actions, enabled = idle) }
                    wide("notes") { GoodToKnow() }
                }
            }
        }

        // Frosted strip behind the clock and status icons, so they stay readable over photos.
        val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        if (statusBarHeight > 0.dp) {
            GlassSurface(
                backdrop = backdrop,
                shape = RectangleShape,
                tint = MaterialTheme.colorScheme.background.copy(alpha = 0.62f),
                modifier = Modifier.fillMaxWidth().height(statusBarHeight)
            ) {}
        }

        AnimatedVisibility(
            visible = state.preview != null,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(140))
        ) {
            // Keep drawing the last preview while it fades out.
            val shown = remember { mutableListOf<PreviewUi>() }
            state.preview?.let { shown.clear(); shown.add(it) }
            shown.firstOrNull()?.let { preview ->
                PreviewOverlay(
                    preview = preview,
                    strength = state.strength,
                    screenHeadroom = screenHeadroom,
                    onStrength = actions.setStrength,
                    onClose = actions.closePreview
                )
            }
        }
    }
}

private val PAGE_MAX_WIDTH = 720.dp
private val BAR_MAX_WIDTH = 520.dp

/**
 * Two very soft pools of light at the top of the page, one cool and one warm, like light falling
 * in from outside the frame. They give the page some depth and the glass something to pick up.
 */
@Composable
private fun Modifier.ambientGlow(): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cool = OneUiBlue.copy(alpha = if (dark) 0.20f else 0.13f)
    val warm = Color(0xFFFFA95C).copy(alpha = if (dark) 0.11f else 0.15f)
    return drawBehind {
        val reach = maxOf(size.width, 420.dp.toPx())
        val coolCentre = Offset(size.width * 0.92f, -reach * 0.12f)
        val warmCentre = Offset(-size.width * 0.10f, reach * 0.18f)
        drawRect(Brush.radialGradient(listOf(cool, Color.Transparent), center = coolCentre, radius = reach * 0.85f))
        drawRect(Brush.radialGradient(listOf(warm, Color.Transparent), center = warmCentre, radius = reach * 0.70f))
    }
}

/** A grid row that spans the full width. */
private fun LazyGridScope.wide(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun Header() {
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 28.dp, bottom = 10.dp)) {
        Text(
            text = "Ultra HDR",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Give the photos you already have real HDR highlights.",
            style = MaterialTheme.typography.bodyMedium,
            color = UhdrTheme.colors.textSecondary
        )
    }
}

@Composable
private fun NoticeCard(notice: Notice, onDismiss: () -> Unit) {
    val tint = if (notice.isError) UhdrTheme.colors.danger else MaterialTheme.colorScheme.primary
    SectionCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (notice.isError) Icons.Rounded.ErrorOutline else Icons.Rounded.Info,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
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

@Composable
private fun EmptyCard(onPick: () -> Unit) {
    Surface(
        onClick = onPick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, UhdrTheme.colors.hairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(64.dp).background(UhdrTheme.colors.accentTint, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, tint = OneUiBlue, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("No photos yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Pick some here, or select them in Gallery and share them to Ultra HDR.",
                style = MaterialTheme.typography.bodySmall,
                color = UhdrTheme.colors.textSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun QueueHeader(state: UiState, actions: ScreenActions) {
    val photos = state.photos
    val checking = photos.any { !it.checked }
    val alreadyHdr = photos.count { it.alreadyHdr }
    val toCopy = photos.count { it.checked && !it.alreadyHdr && (!state.replaceOriginal || !it.onDevice) }
    val toUpdate = photos.count { it.checked && !it.alreadyHdr && state.replaceOriginal && it.onDevice }

    val detail = when {
        checking -> "Checking photos"
        else -> listOfNotNull(
            "$toUpdate to update".takeIf { toUpdate > 0 },
            (if (toCopy == 1) "1 as a copy" else "$toCopy as copies").takeIf { toCopy > 0 },
            "$alreadyHdr already HDR".takeIf { alreadyHdr > 0 }
        ).joinToString("  ·  ")
    }

    Column(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 2.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (photos.size == 1) "1 photo" else "${photos.size} photos",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).semantics { heading() }
            )
            if (state.phase is Phase.Idle) {
                // Compact on purpose, so the count and the line under it read as one block.
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = actions.clearPhotos)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Clear", style = MaterialTheme.typography.labelMedium, color = UhdrTheme.colors.danger)
                }
            }
        }
        if (detail.isNotEmpty()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = UhdrTheme.colors.textSecondary)
        }
    }
}

@Composable
private fun AddTile(onPick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClickLabel = "Add photos", onClick = onPick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = OneUiBlue, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(2.dp))
            Text("Add", style = MaterialTheme.typography.labelMedium, color = OneUiBlue)
        }
    }
}

@Composable
private fun OptionsCard(state: UiState, actions: ScreenActions, enabled: Boolean) {
    // Faded while a batch is running: the settings are locked until it ends.
    val fade by animateFloatAsState(if (enabled) 1f else 0.45f, tween(200), label = "options")
    SectionCard(Modifier.fillMaxWidth().padding(top = 8.dp).alpha(fade)) {
        Column(Modifier.padding(20.dp).animateContentSize(tween(220, easing = OneUiEasing))) {
            Text("HDR strength", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(12.dp))
            SegmentedPicker(
                options = listOf(
                    HdrStrength.SUBTLE to "Subtle",
                    HdrStrength.BALANCED to "Balanced",
                    HdrStrength.VIVID to "Vivid"
                ),
                selected = state.strength,
                onSelect = actions.setStrength,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = strengthDescription(state.strength),
                style = MaterialTheme.typography.bodySmall,
                color = UhdrTheme.colors.textSecondary
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp), thickness = 1.dp, color = UhdrTheme.colors.hairline)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Update original photos", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = if (state.replaceOriginal) {
                            "Each photo is updated where it is in Gallery. Photos that are HDR already are never touched."
                        } else {
                            "Your photos are left alone. HDR copies are saved to the UltraHDR album."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = UhdrTheme.colors.textSecondary
                    )
                }
                // One UI switch: grey track when off, blue when on, white thumb.
                Switch(
                    checked = state.replaceOriginal,
                    onCheckedChange = actions.setReplaceOriginal,
                    enabled = enabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = OneUiBlue,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFF9A999E),
                        uncheckedBorderColor = Color.Transparent
                    )
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp), thickness = 1.dp, color = UhdrTheme.colors.hairline)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Redo photos this app converted before", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "Takes out the HDR that version 2.0 added and converts the photo again. " +
                            "Photos an earlier version saved again can't be undone and are skipped.",
                        style = MaterialTheme.typography.bodySmall,
                        color = UhdrTheme.colors.textSecondary
                    )
                }
                Switch(
                    checked = state.redo,
                    onCheckedChange = actions.setRedo,
                    enabled = enabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = OneUiBlue,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFF9A999E),
                        uncheckedBorderColor = Color.Transparent
                    )
                )
            }
        }
    }
}

internal fun strengthDescription(strength: HdrStrength): String = when (strength) {
    HdrStrength.SUBTLE -> "A gentle lift. Highlights get up to 3x brighter."
    HdrStrength.BALANCED -> "Close to what a phone camera does. Highlights get up to 4.5x brighter."
    HdrStrength.VIVID -> "The strongest effect. Highlights get up to 6x brighter."
}

@Composable
private fun GoodToKnow() {
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NoteRow(Icons.Rounded.Remove, "Photos that are already HDR are skipped.")
        NoteRow(Icons.Rounded.Check, "Camera details, date and location are kept.")
        NoteRow(Icons.Rounded.ContentCopy, "Cloud photos, HEIC files and motion photos are saved as copies, so the originals stay intact.")
    }
}

@Composable
private fun NoteRow(icon: ImageVector, text: String, tint: Color = UhdrTheme.colors.textSecondary) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = UhdrTheme.colors.textSecondary)
    }
}

// ----------------------------------------------------------------------------------------------
// Results
// ----------------------------------------------------------------------------------------------

@Composable
private fun SummaryCard(summary: Summary) {
    val tint = when (summary.tone) {
        Tone.SUCCESS -> UhdrTheme.colors.success
        Tone.INFO -> MaterialTheme.colorScheme.primary
        Tone.ERROR -> UhdrTheme.colors.danger
    }
    val icon = when (summary.tone) {
        Tone.SUCCESS -> Icons.Rounded.Check
        Tone.INFO -> Icons.Rounded.Info
        Tone.ERROR -> Icons.Rounded.ErrorOutline
    }
    SectionCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(44.dp).background(tint.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    text = summary.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() }
                )
            }

            if (summary.lines.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    summary.lines.forEach { line ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (lineIcon, lineTint) = when (line.kind) {
                                LineKind.UPDATED -> Icons.Rounded.PhotoLibrary to UhdrTheme.colors.success
                                LineKind.COPIED -> Icons.Rounded.ContentCopy to UhdrTheme.colors.success
                                LineKind.SKIPPED -> Icons.Rounded.Remove to UhdrTheme.colors.textSecondary
                                LineKind.FAILED -> Icons.Rounded.ErrorOutline to UhdrTheme.colors.danger
                                LineKind.NOT_STARTED -> Icons.Rounded.Block to UhdrTheme.colors.textSecondary
                            }
                            Icon(lineIcon, contentDescription = null, tint = lineTint, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(line.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            if (summary.notes.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 16.dp), thickness = 1.dp, color = UhdrTheme.colors.hairline)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    summary.notes.forEach { note ->
                        Text(note, style = MaterialTheme.typography.bodySmall, color = UhdrTheme.colors.textSecondary)
                    }
                }
            }

            summary.reassurance?.let { text ->
                HorizontalDivider(Modifier.padding(vertical = 16.dp), thickness = 1.dp, color = UhdrTheme.colors.hairline)
                Text(text, style = MaterialTheme.typography.bodySmall, color = UhdrTheme.colors.textSecondary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "To see the difference, open one in Gallery and turn the screen brightness up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = UhdrTheme.colors.textSecondary
                )
            }
        }
    }
}

// ----------------------------------------------------------------------------------------------
// Bottom bar
// ----------------------------------------------------------------------------------------------

private enum class BarMode { PICK, CONVERT, WORKING, OPEN, DONE }

@Composable
private fun BottomBar(state: UiState, actions: ScreenActions, backdrop: GlassBackdrop) {
    val background = MaterialTheme.colorScheme.background
    val mode = when (val phase = state.phase) {
        is Phase.Working -> BarMode.WORKING
        is Phase.Finished -> if (state.results.values.any { it.converted && it.output != null }) BarMode.OPEN else BarMode.DONE
        Phase.Idle -> if (state.photos.isEmpty()) BarMode.PICK else BarMode.CONVERT
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // A light fade under the bar only. The bar is glass, so the page should still be
            // seen sliding beneath it.
            .background(
                Brush.verticalGradient(
                    0f to background.copy(alpha = 0f),
                    1f to background.copy(alpha = 0.78f)
                )
            )
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        GlassSurface(
            backdrop = backdrop,
            shape = MaterialTheme.shapes.extraLarge,
            tint = MaterialTheme.colorScheme.surface.copy(alpha = 0.66f),
            border = BorderStroke(1.dp, UhdrTheme.colors.glassEdge),
            modifier = Modifier
                .widthIn(max = BAR_MAX_WIDTH)
                .fillMaxWidth()
                .shadow(
                    elevation = 18.dp,
                    shape = MaterialTheme.shapes.extraLarge,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.10f),
                    spotColor = Color.Black.copy(alpha = 0.16f)
                )
        ) {
            AnimatedContent(
                targetState = mode,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                label = "bar"
            ) { shown ->
                Box(Modifier.fillMaxWidth().padding(8.dp)) {
                    when (shown) {
                        BarMode.PICK -> PrimaryButton("Pick photos", Icons.Rounded.AddPhotoAlternate, actions.pickPhotos)
                        BarMode.CONVERT -> {
                            val count = state.photos.size
                            PrimaryButton(
                                text = if (count == 1) "Convert 1 photo" else "Convert $count photos",
                                icon = Icons.Rounded.AutoAwesome,
                                onClick = actions.convert
                            )
                        }
                        BarMode.WORKING -> WorkingBar(state, actions.stop)
                        // "Done" takes only the room it needs, so the longer label never gets cut off.
                        BarMode.OPEN -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton("Done", actions.done)
                            PrimaryButton("Open in Gallery", null, actions.openFirstResult, Modifier.weight(1f))
                        }
                        BarMode.DONE -> PrimaryButton("Done", null, actions.done)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkingBar(state: UiState, onStop: () -> Unit) {
    val phase = state.phase as? Phase.Working
    val total = phase?.total ?: state.photos.size
    val finished = state.results.size
    val progress by animateFloatAsState(
        targetValue = if (total == 0) 0f else finished.toFloat() / total,
        animationSpec = tween(300, easing = OneUiEasing),
        label = "progress"
    )
    Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Converting ${minOf(finished + 1, total)} of $total",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = OneUiBlue,
                trackColor = UhdrTheme.colors.segmentTrack,
                // One plain bar, without the gap and end dot newer Material adds.
                gapSize = 0.dp,
                drawStopIndicator = {}
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onStop) {
            Text("Stop", style = MaterialTheme.typography.labelMedium, color = UhdrTheme.colors.danger)
        }
    }
}

@Composable
private fun PrimaryButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(containerColor = OneUiBlue, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier.fillMaxWidth().height(52.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = UhdrTheme.colors.segmentTrack,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        contentPadding = PaddingValues(horizontal = 24.dp),
        modifier = modifier.height(52.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
