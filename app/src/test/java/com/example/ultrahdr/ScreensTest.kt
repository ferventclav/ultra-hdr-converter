package com.example.ultrahdr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ultrahdr.processing.GainMapPlan
import com.example.ultrahdr.processing.LocalPhoto
import com.example.ultrahdr.processing.PhotoAccess
import com.example.ultrahdr.processing.HdrStrength
import com.example.ultrahdr.processing.Outcome
import com.example.ultrahdr.processing.PhotoItem
import com.example.ultrahdr.processing.PhotoResult
import com.example.ultrahdr.processing.PhotoTarget
import com.example.ultrahdr.processing.UltraHdrEngine
import com.example.ultrahdr.ui.GalleryActions
import com.example.ultrahdr.ui.GalleryPage
import com.example.ultrahdr.ui.GalleryState
import com.example.ultrahdr.ui.Notice
import com.example.ultrahdr.ui.TileChip
import com.example.ultrahdr.ui.Phase
import com.example.ultrahdr.ui.PromptCard
import com.example.ultrahdr.ui.ScreenActions
import com.example.ultrahdr.ui.UiState
import com.example.ultrahdr.ui.UltraHdrScreen
import com.example.ultrahdr.ui.theme.UltraHdrTheme
import com.github.takahirom.roborazzi.captureRoboImage
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.robolectric.Shadows.shadowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Draws every state of the app on the PC and checks the words that matter are on screen.
 *
 * Run `gradle testDebugUnitTest -Pscreenshots` to also save each screen as a PNG in
 * app/build/screens, which is how the layout is reviewed without a phone. Photos are painted
 * stand-ins from [TestScenes].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = ScreensTest.PHONE)
class ScreensTest {

    @get:Rule
    val compose = createComposeRule()

    // ---------------------------------------------------------------------------------- start

    @Test
    fun start() = screen("01_start", UiState(updatesWithoutAsking = true)) {
        compose.onNodeWithText("Select photos").assertIsDisplayed()
        compose.onNodeWithText("Ultra HDR").assertIsDisplayed()
        compose.onNodeWithText("No photos selected.").assertIsDisplayed()
        compose.onAllNodesWithText("Stop edit prompts").assertCountEquals(0)
    }

    @Test
    fun startDark() = screen("01_start_dark", UiState(updatesWithoutAsking = true), dark = true)

    @Test
    fun startWithPromptsOn() = screen("01_start_prompts_on", UiState()) {
        compose.onNodeWithText("Stop edit prompts").assertIsDisplayed()
    }

    @Test
    fun preparing() = screen(
        "02_preparing",
        UiState(photos = (0 until 7).map { PhotoItem(uri(it), name = "Photo") }, phase = Phase.Preparing)
    ) {
        compose.onNodeWithText("Getting photos ready").assertIsDisplayed()
    }

    @Test
    fun declinedPermission() = screen(
        "03_declined",
        UiState(photos = sevenPhotos)
    ) {
        compose.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test
    fun theOneQuestion() = card("04_prompt") {
        PromptCard(blurred = false, onTurnOn = {}, onNotNow = {})
    }

    @Test
    fun theOneQuestionDark() = card("04_prompt_dark", dark = true) {
        PromptCard(blurred = false, onTurnOn = {}, onNotNow = {})
    }

    // ---------------------------------------------------------------------------------- working

    @Test
    fun glassBarOverPhotos() = scrolledScreen("08_glass_bar", manyWorking)

    @Test
    fun glassBarOverPhotosDark() = scrolledScreen("08_glass_bar_dark", manyWorking, dark = true)

    @Test
    fun working() = screen("10_working", workingState) {
        compose.onNodeWithText("Converting 4 of 7").assertIsDisplayed()
        compose.onNodeWithText("Stop").assertIsDisplayed()
    }

    @Test
    fun workingDark() = screen("10_working_dark", workingState, dark = true)

    // ---------------------------------------------------------------------------------- finished

    @Test
    fun finished() = screen("20_finished", finishedState) {
        compose.onNodeWithText("Open Gallery").assertIsDisplayed()
        compose.onNodeWithText("5 photos are now HDR").assertIsDisplayed()
        compose.onNodeWithText("Undo").assertIsDisplayed()
    }

    @Test
    fun finishedDark() = screen("20_finished_dark", finishedState, dark = true)

    @Test
    fun finishedSingle() = screen(
        "20_finished_single",
        UiState(photos = listOf(photo(0)), results = mapOf(uri(0) to updated(0)), phase = Phase.Finished(stopped = false), canUndo = true)
    ) {
        compose.onNodeWithText("Open photo").assertIsDisplayed()
        compose.onNodeWithText("Open Gallery").assertDoesNotExist()
    }

    @Test
    fun finishedSingleDark() = screen(
        "20_finished_single_dark",
        UiState(photos = listOf(photo(0)), results = mapOf(uri(0) to updated(0)), phase = Phase.Finished(stopped = false), canUndo = true),
        dark = true
    )

    @Test
    @Config(sdk = [34], qualifiers = TALL)
    fun finishedWholePage() = screen("20_finished_whole_page", finishedState)

    @Test
    fun finishedOneFailed() = screen(
        "21_finished_one_failed",
        UiState(
            photos = listOf(photo(0)),
            results = mapOf(uri(0) to PhotoResult(uri(0), photo(0).name, Outcome.FAILED, note = "Couldn't open this photo.")),
            phase = Phase.Finished(stopped = false)
        )
    ) {
        compose.onNodeWithText("This photo couldn't be converted").assertIsDisplayed()
    }

    @Test
    fun undoing() = screen("23_undoing", finishedState.copy(phase = Phase.Undoing, canUndo = false)) {
        compose.onNodeWithText("Putting photos back").assertIsDisplayed()
    }

    @Test
    fun finishedNothingToDo() = screen(
        "22_finished_nothing_to_do",
        UiState(
            photos = listOf(photo(0, hdr = true), photo(1)),
            results = mapOf(
                uri(0) to PhotoResult(uri(0), photo(0).name, Outcome.ALREADY_HDR),
                uri(1) to PhotoResult(uri(1), photo(1).name, Outcome.NO_HIGHLIGHTS, note = "No bright highlights to boost, so it was left as it is.")
            ),
            phase = Phase.Finished(stopped = false)
        )
    ) {
        compose.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun finishedAfterStop() = screen(
        "23_finished_after_stop",
        UiState(
            photos = sevenPhotos,
            results = mapOf(uri(0) to updated(0), uri(1) to updated(1)),
            phase = Phase.Finished(stopped = true)
        )
    ) {
        compose.onNodeWithText("5 not started").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------------- other screens

    @Test
    @Config(sdk = [34], qualifiers = TABLET)
    fun tabletStart() = screen("40_tablet_start", UiState())

    @Test
    @Config(sdk = [34], qualifiers = TABLET)
    fun tabletFinished() = screen("40_tablet_finished", finishedState, dark = true)

    @Test
    @Config(sdk = [34], qualifiers = PHONE_SIDEWAYS)
    fun sidewaysWorking() = screen("41_sideways_working", workingState)

    @Test
    @Config(sdk = [34], qualifiers = TABLET_SIDEWAYS)
    fun tabletSidewaysFinished() = screen("43_tablet_sideways_finished", finishedState)

    @Test
    @Config(sdk = [34], qualifiers = SMALL_PHONE)
    fun smallPhoneWithLargeText() = screen("42_small_large_text", finishedState, fontScale = 1.3f)

    @Test
    @Config(sdk = [34], qualifiers = SMALL_PHONE)
    fun smallPhoneStartWithLargeText() = screen("42_small_large_text_start", UiState(), fontScale = 1.3f)

    // ------------------------------------------------------------------- what the maths decides

    /** One sheet: each painted scene next to the parts the app would brighten, at each strength. */
    @Test
    fun boostMapsForEveryScene() {
        val cellW = 320
        val cellH = 240
        val label = 44
        val columns = 1 + HdrStrength.entries.size
        val sheet = Bitmap.createBitmap(columns * cellW, TestScenes.COUNT * (cellH + label), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.BLACK)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f }

        for (kind in 0 until TestScenes.COUNT) {
            val top = kind * (cellH + label)
            val scene = TestScenes.bitmap(kind, 1200, 900)
            canvas.drawBitmap(scene, null, Rect(0, top, cellW, top + cellH), null)
            canvas.drawText("scene $kind", 8f, top + cellH + 30f, text)
            HdrStrength.entries.forEachIndexed { index, strength ->
                val plan = UltraHdrEngine.plan(scene, strength)
                val left = (index + 1) * cellW
                canvas.drawBitmap(UltraHdrEngine.gainMapPicture(plan), null, Rect(left, top, left + cellW, top + cellH), null)
                canvas.drawText(describe(strength, plan), left + 8f, top + cellH + 30f, text)
                // The numbers the screens show must stay inside what the strength promises.
                check(plan.peakBoost <= plan.ratioMax + 0.01f) { "scene $kind $strength peaks too high" }
                check(plan.ratioMax >= 1f)
            }
        }
        if (recording) {
            val out = File("build/screens/50_boost_maps.png").apply { parentFile?.mkdirs() }
            FileOutputStream(out).use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun describe(strength: HdrStrength, plan: GainMapPlan) = String.format(
        Locale.US, "%s  peak %.1fx  %.0f%%",
        strength.name.lowercase(Locale.US), plan.peakBoost, plan.boostedShare * 100f
    )

    // ---------------------------------------------------------------------------------- photo grid

    @Test
    fun gallery() = galleryScreen("30_gallery", galleryState()) {
        compose.onNodeWithText("Select photos").assertIsDisplayed()
        compose.onNodeWithText("All").assertIsDisplayed()
        compose.onNodeWithText("Camera").assertIsDisplayed()
        compose.onNodeWithText("Convert").assertIsDisplayed().assertIsNotEnabled()
        // Nothing to clear yet, so Clear is not in the bar.
        compose.onNodeWithText("Clear").assertDoesNotExist()
    }

    @Test
    fun galleryDark() = galleryScreen("30_gallery_dark", galleryState(), dark = true)

    @Test
    fun galleryPicked() = galleryScreen("31_gallery_picked", galleryState(selected = setOf(3L, 4L, 5L, 6L, 1L))) {
        compose.onNodeWithText("5 selected").assertIsDisplayed()
        compose.onNodeWithText("Convert 5").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Clear").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun galleryPickedDark() = galleryScreen("31_gallery_picked_dark", galleryState(selected = setOf(3L, 4L, 5L, 6L)), dark = true)

    @Test
    fun galleryOneAlbum() = galleryScreen("32_gallery_whatsapp", galleryState(album = "WhatsApp Images"), dark = true) {
        compose.onNodeWithText("WhatsApp").assertIsDisplayed()
    }

    @Test
    fun gallerySomeAccess() = galleryScreen("33_gallery_some_access", galleryState(access = PhotoAccess.SOME)) {
        compose.onNodeWithText("Allow all").assertIsDisplayed()
    }

    @Test
    fun galleryNoAccess() = galleryScreen("34_gallery_no_access", GalleryState(open = true, access = PhotoAccess.NONE)) {
        compose.onNodeWithText("Allow").assertIsDisplayed()
    }

    @Test
    fun galleryLargeText() = galleryScreen("37_gallery_large_text", galleryState(selected = setOf(1L, 2L, 3L)), dark = true, fontScale = 1.3f) {
        compose.onNodeWithText("Convert 3").assertIsDisplayed()
    }

    /** The largest font size with a three-digit count: the label shrinks to fit, never cut short. */
    @Test
    fun galleryHugeText() = galleryScreen("38_gallery_huge_text", galleryState(selected = (1L..128L).toSet()), dark = true, fontScale = 2f) {
        compose.onNodeWithText("Convert 128").assertIsDisplayed()
        compose.onNodeWithText("128 selected").assertIsDisplayed()
    }

    /** Just after Clear: the bar is back to Cancel and Convert, and the toast offers Undo. */
    @Test
    fun galleryCleared() = galleryScreen("39_gallery_cleared", galleryState(cleared = setOf(3L, 4L, 5L, 6L, 1L))) {
        compose.onNodeWithText("Select photos").assertIsDisplayed()
        compose.onNodeWithText("Selection cleared").assertIsDisplayed()
        compose.onNodeWithText("Undo").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Clear").assertDoesNotExist()
        compose.onNodeWithText("Convert").assertIsNotEnabled()
    }

    @Test
    fun galleryClearedDark() = galleryScreen("39_gallery_cleared_dark", galleryState(cleared = setOf(3L, 4L)), dark = true)

    @Test
    fun galleryClearedHugeText() = galleryScreen("39_gallery_cleared_huge_text", galleryState(cleared = setOf(3L)), dark = true, fontScale = 2f) {
        compose.onNodeWithText("Selection cleared").assertIsDisplayed()
        compose.onNodeWithText("Undo").assertIsDisplayed()
    }

    @Test
    fun undoInTheToastCallsUndo() {
        var undone = 0
        compose.setContent {
            UltraHdrTheme {
                GalleryPage(
                    state = galleryState(cleared = setOf(3L, 4L)),
                    actions = GalleryActions(undoClear = { undone++ }),
                    thumbnail = paintedLocal
                )
            }
        }
        compose.onNodeWithText("Undo").performClick()
        assertEquals(1, undone)
    }

    /** Undo is offered for five seconds, and the count waits while a thumb is on it. */
    @Test
    fun undoWaitsForAThumbThenTimesOut() {
        var timeUp = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UltraHdrTheme {
                GalleryPage(state = galleryState(cleared = setOf(3L)), actions = GalleryActions(forgetCleared = { timeUp++ }), thumbnail = paintedLocal)
            }
        }
        compose.mainClock.advanceTimeBy(4_000)
        assertEquals(0, timeUp)
        compose.onNodeWithText("Undo").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(8_000)
        assertEquals(0, timeUp)
        compose.onNodeWithText("Undo").performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(4_000)
        assertEquals(0, timeUp)
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(1, timeUp)
    }

    /** With TalkBack on, the toast stays until the user acts, as Android's own snackbars do. */
    @Test
    fun undoWaitsForTalkBack() {
        var timeUp = 0
        val manager = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setTouchExplorationEnabled(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UltraHdrTheme {
                GalleryPage(state = galleryState(cleared = setOf(3L)), actions = GalleryActions(forgetCleared = { timeUp++ }), thumbnail = paintedLocal)
            }
        }
        compose.mainClock.advanceTimeBy(60_000)
        assertEquals(0, timeUp)
        shadowOf(manager).setTouchExplorationEnabled(false)
    }

    /** A tap on the toast's message must not pick the photo hidden under it, which would also end Undo. */
    @Test
    fun tapsOnTheToastStayOnTheToast() {
        val toggled = mutableListOf<Long>()
        compose.setContent {
            UltraHdrTheme {
                GalleryPage(state = galleryState(cleared = setOf(3L)), actions = GalleryActions(toggle = { toggled += it }), thumbnail = paintedLocal)
            }
        }
        compose.onNodeWithText("Selection cleared").performClick()
        assertEquals(emptyList<Long>(), toggled)
    }

    /** The same for the bar: its rim and the gaps between its buttons belong to the bar. */
    @Test
    fun tapsOnTheBarsRimStayOnTheBar() {
        val toggled = mutableListOf<Long>()
        compose.setContent {
            UltraHdrTheme {
                GalleryPage(state = galleryState(selected = setOf(1L)), actions = GalleryActions(toggle = { toggled += it }), thumbnail = paintedLocal)
            }
        }
        compose.onNodeWithText("Cancel").performTouchInput { click(Offset(-2.dp.toPx(), centerY)) }
        assertEquals(emptyList<Long>(), toggled)
    }

    /** The Galaxy S25 at its default display size, to set beside an Enhance-X screenshot. */
    @Test
    @Config(qualifiers = "w384dp-h832dp-xxhdpi")
    fun galleryS25() = galleryScreen("36_gallery_s25", galleryState(), dark = true)

    @Test
    @Config(qualifiers = TABLET)
    fun galleryTablet() = galleryScreen("35_gallery_tablet", galleryState(selected = setOf(2L)), dark = true)

    // ---------------------------------------------------------------------------------- tools

    private val recording: Boolean get() = System.getProperty("roborazzi.test.record") == "true"

    private fun screen(
        name: String,
        state: UiState,
        dark: Boolean = false,
        fontScale: Float = 1f,
        checks: () -> Unit = {}
    ) {
        compose.setContent {
            Scaled(fontScale) {
                UltraHdrTheme(darkTheme = dark) {
                    UltraHdrScreen(state = state, actions = ScreenActions(), thumbnail = painted)
                }
            }
        }
        compose.waitForIdle()
        checks()
        compose.onRoot().captureRoboImage("build/screens/$name.png")
    }

    private fun scrolledScreen(name: String, state: UiState, dark: Boolean = false) {
        compose.setContent {
            UltraHdrTheme(darkTheme = dark) {
                UltraHdrScreen(state = state, actions = ScreenActions(), thumbnail = painted)
            }
        }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.35f, durationMillis = 400) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/$name.png")
    }

    private fun card(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            UltraHdrTheme(darkTheme = dark) {
                Box(Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) { content() }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/$name.png")
    }

    private fun galleryScreen(
        name: String,
        state: GalleryState,
        dark: Boolean = false,
        fontScale: Float = 1f,
        checks: () -> Unit = {}
    ) {
        compose.setContent {
            Scaled(fontScale) {
                UltraHdrTheme(darkTheme = dark) {
                    GalleryPage(state = state, actions = GalleryActions(), thumbnail = paintedLocal)
                }
            }
        }
        compose.waitForIdle()
        checks()
        compose.onRoot().captureRoboImage("build/screens/$name.png")
    }

    /** Painted stand-ins; every fourth one carries the HDR label the real thumbnail shows. */
    private val paintedLocal: @Composable (LocalPhoto, Modifier) -> Unit = { photo, modifier ->
        val image = remember(photo.id) { TestScenes.bitmap(photo.id.toInt(), 240, 240).asImageBitmap() }
        Box(modifier) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (photo.id % 4 == 2L) TileChip("HDR", Modifier.align(Alignment.BottomStart).padding(5.dp))
        }
    }

    private fun galleryState(
        selected: Set<Long> = emptySet(),
        album: String? = null,
        access: PhotoAccess = PhotoAccess.ALL,
        cleared: Set<Long> = emptySet()
    ): GalleryState {
        val hour = 3_600_000L
        val albums = listOf("Camera", "WhatsApp Images", "Camera", "Screenshots", "Download", "Camera", "WhatsApp Images")
        val photos = (0 until 40).map { i ->
            // About six photos a day, going back from this afternoon.
            val taken = GALLERY_NOW - hour - i * 4L * hour
            LocalPhoto(i.toLong(), uri(i), albums[i % albums.size], taken, taken)
        }
        return GalleryState(open = true, access = access, photos = photos, album = album, selected = selected, cleared = cleared)
    }

    @Composable
    private fun Scaled(fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { content() }
    }

    private val painted: @Composable (Uri, Modifier) -> Unit = { uri, modifier ->
        val kind = uri.lastPathSegment?.toIntOrNull() ?: 0
        val image = remember(kind) { TestScenes.bitmap(kind, 360, 360).asImageBitmap() }
        Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    }

    private val manyWorking: UiState
        get() = UiState(
            photos = (0 until 40).map { photo(it) },
            results = (0 until 12).associate { uri(it) to updated(it) },
            phase = Phase.Working(done = 12, total = 40, currentUri = uri(12), currentName = photo(12).name)
        )

    private val workingState: UiState
        get() = UiState(
            photos = sevenPhotos,
            results = mapOf(
                uri(0) to updated(0),
                uri(1) to updated(1),
                uri(2) to PhotoResult(uri(2), photo(2).name, Outcome.ALREADY_HDR)
            ),
            phase = Phase.Working(done = 3, total = 7, currentUri = uri(3), currentName = photo(3).name)
        )

    private val finishedState: UiState
        get() = UiState(
            photos = sevenPhotos,
            results = mapOf(
                uri(0) to updated(0),
                uri(1) to updated(1),
                uri(2) to PhotoResult(uri(2), photo(2).name, Outcome.ALREADY_HDR),
                uri(3) to updated(3),
                uri(4) to PhotoResult(
                    uri(4), photo(4).name, Outcome.COPIED, output = uri(4), keptOriginalPixels = true,
                    note = "Not stored on this phone, so a copy was saved.", peakBoost = 4.2f
                ),
                uri(5) to updated(5),
                uri(6) to PhotoResult(
                    uri(6), photo(6).name, Outcome.NO_HIGHLIGHTS,
                    note = "No bright highlights to boost, so it was left as it is."
                )
            ),
            phase = Phase.Finished(stopped = false),
            canUndo = true
        )

    private fun updated(i: Int) =
        PhotoResult(uri(i), photo(i).name, Outcome.UPDATED, output = uri(i), keptOriginalPixels = true, peakBoost = 4.5f)

    companion object {
        /** Galaxy S25: 1080 x 2340 at 480 dpi. */
        const val PHONE = "w360dp-h780dp-xxhdpi"
        const val PHONE_SIDEWAYS = "w780dp-h360dp-xxhdpi"
        const val SMALL_PHONE = "w320dp-h640dp-xhdpi"
        /** Same width, tall enough to see a whole page that would scroll. */
        const val TALL = "w360dp-h1400dp-xxhdpi"
        /** Galaxy Tab S9, upright. */
        const val TABLET = "w800dp-h1280dp-xhdpi"
        const val TABLET_SIDEWAYS = "w1280dp-h800dp-xhdpi"

        /** 15 Nov 2026, 13:29 local time. */
        val GALLERY_NOW: Long = java.time.LocalDateTime.of(2026, 11, 15, 13, 29)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        private val onPhone = PhotoTarget(File("/storage/emulated/0/DCIM/Camera/photo.jpg"), null)

        fun uri(i: Int): Uri = Uri.parse("content://media/external/images/media/$i")

        fun photo(i: Int, hdr: Boolean = false, cloud: Boolean = false) = PhotoItem(
            uri = uri(i),
            name = String.format(Locale.US, "20261108_18%02d%02d.jpg", 10 + i % 40, (i * 7) % 60),
            checked = true,
            target = if (cloud) null else onPhone,
            alreadyHdr = hdr
        )

        val sevenPhotos: List<PhotoItem> = listOf(
            photo(0), photo(1), photo(2, hdr = true), photo(3), photo(4, cloud = true), photo(5), photo(6)
        )
    }
}
