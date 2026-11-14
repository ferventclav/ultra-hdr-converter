package com.example.ultrahdr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ultrahdr.processing.GainMapPlan
import com.example.ultrahdr.processing.HdrStrength
import com.example.ultrahdr.processing.Outcome
import com.example.ultrahdr.processing.PhotoItem
import com.example.ultrahdr.processing.PhotoResult
import com.example.ultrahdr.processing.PhotoTarget
import com.example.ultrahdr.processing.UltraHdrEngine
import com.example.ultrahdr.ui.Notice
import com.example.ultrahdr.ui.Phase
import com.example.ultrahdr.ui.PreviewMode
import com.example.ultrahdr.ui.PreviewOverlay
import com.example.ultrahdr.ui.PreviewUi
import com.example.ultrahdr.ui.ScreenActions
import com.example.ultrahdr.ui.UiState
import com.example.ultrahdr.ui.UltraHdrScreen
import com.example.ultrahdr.ui.theme.UltraHdrTheme
import com.github.takahirom.roborazzi.captureRoboImage
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
    fun empty() = screen("01_empty", UiState()) {
        compose.onNodeWithText("Pick photos").assertIsDisplayed()
    }

    @Test
    fun emptyDark() = screen("01_empty_dark", UiState(), dark = true)

    // ---------------------------------------------------------------------------------- queue

    @Test
    fun queue() = screen("02_queue", UiState(photos = sevenPhotos)) {
        compose.onNodeWithText("Convert 7 photos").assertIsDisplayed()
        compose.onNodeWithText("7 photos").assertIsDisplayed()
    }

    @Test
    fun queueDark() = screen("02_queue_dark", UiState(photos = sevenPhotos), dark = true)

    @Test
    @Config(sdk = [34], qualifiers = TALL)
    fun queueWholePage() = screen("02_queue_whole_page", UiState(photos = sevenPhotos))

    @Test
    @Config(sdk = [34], qualifiers = TALL)
    fun queueWholePageDark() = screen("02_queue_whole_page_dark", UiState(photos = sevenPhotos), dark = true)

    @Test
    @Config(sdk = [34], qualifiers = TALL)
    fun queueAsCopies() = screen(
        "03_queue_copies_vivid",
        UiState(photos = sevenPhotos, replaceOriginal = false, strength = HdrStrength.VIVID)
    )

    @Test
    fun queueStillChecking() = screen(
        "04_queue_checking",
        UiState(photos = (0 until 3).map { PhotoItem(uri(it), name = "Photo") })
    ) {
        compose.onNodeWithText("Checking photos").assertIsDisplayed()
    }

    @Test
    fun onePhoto() = screen("05_queue_one_photo", UiState(photos = listOf(photo(0)))) {
        compose.onNodeWithText("Convert 1 photo").assertIsDisplayed()
    }

    @Test
    fun declinedPermission() = screen(
        "06_queue_notice",
        UiState(
            photos = sevenPhotos,
            notice = Notice(
                "Nothing was changed. To keep your originals as they are, turn off " +
                    "\"Update original photos\" and HDR copies will be saved instead.",
                isError = true
            )
        )
    )

    @Test
    fun manyPhotos() = screen("07_queue_many", UiState(photos = (0 until 40).map { photo(it) }))

    /** Scrolled, so photos sit under the floating bar and the frosted glass has something to blur. */
    @Test
    fun glassBarOverPhotos() = scrolledScreen("08_glass_bar", UiState(photos = (0 until 40).map { photo(it) }))

    @Test
    fun glassBarOverPhotosDark() =
        scrolledScreen("08_glass_bar_dark", UiState(photos = (0 until 40).map { photo(it) }), dark = true)

    @Test
    fun glassBarWhileWorking() {
        val photos = (0 until 40).map { photo(it) }
        scrolledScreen(
            "08_glass_bar_working",
            UiState(
                photos = photos,
                results = (0 until 12).associate { uri(it) to updated(it) },
                phase = Phase.Working(done = 12, total = 40, currentUri = uri(12), currentName = photo(12).name)
            )
        )
    }

    // -------------------------------------------------------------------------------- working

    @Test
    fun working() = screen("10_working", workingState) {
        compose.onNodeWithText("Converting 4 of 7").assertIsDisplayed()
    }

    @Test
    fun workingDark() = screen("10_working_dark", workingState, dark = true)

    // ------------------------------------------------------------------------------- finished

    @Test
    fun finished() = screen("20_finished", finishedState) {
        compose.onNodeWithText("5 photos are now HDR").assertIsDisplayed()
        compose.onNodeWithText("Open in Gallery").assertIsDisplayed()
    }

    @Test
    fun finishedDark() = screen("20_finished_dark", finishedState, dark = true)

    @Test
    @Config(sdk = [34], qualifiers = TALL)
    fun finishedWholePage() = screen("20_finished_whole_page", finishedState)

    @Test
    fun finishedOneFailed() = screen(
        "21_finished_failed",
        UiState(
            photos = listOf(photo(3)),
            results = mapOf(
                uri(3) to PhotoResult(uri(3), photo(3).name, Outcome.FAILED, note = "This file isn't a picture the phone can read.")
            ),
            phase = Phase.Finished(stopped = false)
        )
    ) {
        compose.onNodeWithText("This photo couldn't be converted").assertIsDisplayed()
    }

    @Test
    fun finishedNothingToDo() = screen(
        "22_finished_nothing",
        UiState(
            photos = listOf(photo(2, hdr = true), photo(6)),
            results = mapOf(
                uri(2) to PhotoResult(uri(2), photo(2).name, Outcome.ALREADY_HDR),
                uri(6) to PhotoResult(uri(6), photo(6).name, Outcome.NO_HIGHLIGHTS)
            ),
            phase = Phase.Finished(stopped = false)
        )
    ) {
        compose.onNodeWithText("Nothing needed changing").assertIsDisplayed()
    }

    @Test
    fun finishedAfterStop() = screen(
        "23_finished_stopped",
        UiState(
            photos = sevenPhotos,
            results = mapOf(
                uri(0) to updated(0),
                uri(1) to updated(1)
            ),
            phase = Phase.Finished(stopped = true)
        )
    ) {
        compose.onNodeWithText("5 not started").assertIsDisplayed()
    }

    // -------------------------------------------------------------------------------- preview

    @Test
    fun preview() = overlay("30_preview_hdr", previewOf(TestScenes.SUNSET), headroom = 4f)

    @Test
    fun previewOnAScreenThatIsNotShowingHdr() {
        overlay("31_preview_screen_not_hdr", previewOf(TestScenes.SUNSET), headroom = 1f)
        compose.onNodeWithText("Your screen isn't showing HDR right now", substring = true).assertIsDisplayed()
    }

    @Test
    fun previewSaysNothingAboutTheScreenWhenThePhoneCannotTell() {
        overlay("38_preview_headroom_unknown", previewOf(TestScenes.SUNSET), headroom = null)
        compose.onNodeWithText("Your screen isn't showing HDR right now", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Highlights up to", substring = true).assertIsDisplayed()
    }

    @Test
    fun previewBoostMap() =
        overlay("32_preview_boost_map", previewOf(TestScenes.NIGHT_STREET), headroom = 4f, mode = PreviewMode.BOOST_MAP)

    @Test
    fun previewPortrait() =
        overlay("33_preview_portrait", previewOf(TestScenes.LAMP, width = 900, height = 1200), headroom = 4f)

    @Test
    fun previewLoading() = overlay("34_preview_loading", PreviewUi(photo(0)), headroom = 4f)

    @Test
    fun previewNothingToBoost() =
        overlay("35_preview_no_highlights", previewOf(TestScenes.FOREST).copy(noHighlights = true, peakBoost = 1f), headroom = 4f)

    @Test
    fun previewAlreadyHdr() {
        val base = TestScenes.bitmap(TestScenes.BEACH, 1200, 900)
        overlay(
            "36_preview_already_hdr",
            PreviewUi(
                photo(2, hdr = true), loading = false, original = base, hdr = base,
                ambient = UltraHdrEngine.ambientWash(base), alreadyHdr = true
            ),
            headroom = 4f
        )
    }

    @Test
    fun previewFailed() =
        overlay("37_preview_failed", PreviewUi(photo(0), loading = false, failed = true), headroom = 4f)

    // ----------------------------------------------------------------------- other screen sizes

    @Test
    @Config(sdk = [34], qualifiers = TABLET)
    fun tabletQueue() = screen("40_tablet_queue", UiState(photos = sevenPhotos))

    @Test
    @Config(sdk = [34], qualifiers = TABLET)
    fun tabletFinished() = screen("40_tablet_finished", finishedState, dark = true)

    @Test
    @Config(sdk = [34], qualifiers = TABLET)
    fun tabletPreview() = overlay("40_tablet_preview", previewOf(TestScenes.SUNSET), headroom = 4f)

    @Test
    @Config(sdk = [34], qualifiers = PHONE_SIDEWAYS)
    fun sidewaysQueue() = screen("41_sideways_queue", UiState(photos = sevenPhotos))

    @Test
    @Config(sdk = [34], qualifiers = PHONE_SIDEWAYS)
    fun sidewaysPreview() = overlay("41_sideways_preview", previewOf(TestScenes.SUNSET), headroom = 1f)

    @Test
    @Config(sdk = [34], qualifiers = TABLET_SIDEWAYS)
    fun tabletSidewaysFinished() = screen("43_tablet_sideways_finished", finishedState)

    @Test
    @Config(sdk = [34], qualifiers = SMALL_PHONE)
    fun smallPhoneWithLargeText() = screen("42_small_large_text", finishedState, fontScale = 1.3f)

    @Test
    @Config(sdk = [34], qualifiers = SMALL_PHONE)
    fun smallPhoneQueueWithLargeText() = screen("42_small_large_text_queue", UiState(photos = sevenPhotos), fontScale = 1.3f)

    @Test
    @Config(sdk = [34], qualifiers = SMALL_PHONE)
    fun smallPhonePreviewWithLargeText() =
        overlay("42_small_large_text_preview", previewOf(TestScenes.SUNSET), headroom = 1f, fontScale = 1.3f)

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

    private fun overlay(
        name: String,
        preview: PreviewUi,
        headroom: Float?,
        mode: PreviewMode = PreviewMode.HDR,
        fontScale: Float = 1f
    ) {
        compose.setContent {
            Scaled(fontScale) {
                PreviewOverlay(
                    preview = preview,
                    strength = HdrStrength.BALANCED,
                    screenHeadroom = headroom,
                    onStrength = {},
                    onClose = {},
                    initialMode = mode
                )
            }
        }
        // Past the moment the preview gives the screen to switch into HDR.
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/$name.png")
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

    private fun previewOf(kind: Int, width: Int = 1200, height: Int = 900): PreviewUi {
        val base = TestScenes.bitmap(kind, width, height)
        val plan = UltraHdrEngine.plan(base, HdrStrength.BALANCED)
        val hdr = base.copy(Bitmap.Config.ARGB_8888, true)
        // The PC has no HDR drawing, so a failure to attach must not stop the screen being drawn.
        runCatching { UltraHdrEngine.attach(hdr, plan) }
        return PreviewUi(
            item = photo(kind), loading = false, original = base, hdr = hdr,
            boostMap = UltraHdrEngine.gainMapPicture(plan),
            ambient = UltraHdrEngine.ambientWash(base),
            peakBoost = plan.peakBoost, boostedShare = plan.boostedShare,
            noHighlights = !plan.hasVisibleEffect
        )
    }

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
            phase = Phase.Finished(stopped = false)
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
