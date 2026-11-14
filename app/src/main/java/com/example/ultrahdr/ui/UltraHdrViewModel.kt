package com.example.ultrahdr.ui

import android.app.Application
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ultrahdr.processing.BatchConverter
import com.example.ultrahdr.processing.HdrStrength
import com.example.ultrahdr.processing.ImageExifHelper
import com.example.ultrahdr.processing.MediaStoreSaver
import com.example.ultrahdr.processing.PhotoItem
import com.example.ultrahdr.processing.PhotoResult
import com.example.ultrahdr.processing.RecoveryStore
import com.example.ultrahdr.processing.UltraHdrEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Phase {
    data object Idle : Phase
    data class Working(val done: Int, val total: Int, val currentUri: Uri?, val currentName: String) : Phase
    /** [stopped] is true when the person pressed Stop before the end. */
    data class Finished(val stopped: Boolean) : Phase
}

data class Notice(val text: String, val isError: Boolean)

/** What the full-screen preview shows for one photo. */
data class PreviewUi(
    val item: PhotoItem,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val original: Bitmap? = null,
    val hdr: Bitmap? = null,
    val boostMap: Bitmap? = null,
    /** A tiny copy of the photo, drawn blurred across the whole screen behind it. */
    val ambient: Bitmap? = null,
    val peakBoost: Float = 1f,
    val boostedShare: Float = 0f,
    val alreadyHdr: Boolean = false,
    val noHighlights: Boolean = false
)

data class UiState(
    val photos: List<PhotoItem> = emptyList(),
    val results: Map<Uri, PhotoResult> = emptyMap(),
    val phase: Phase = Phase.Idle,
    val strength: HdrStrength = HdrStrength.BALANCED,
    val replaceOriginal: Boolean = true,
    val notice: Notice? = null,
    val preview: PreviewUi? = null
)

sealed class UiEvent {
    data class RequestWritePermission(val intentSender: IntentSender) : UiEvent()
}

class UltraHdrViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val converter = BatchConverter(context)

    private val _state = MutableStateFlow(
        UiState(
            strength = runCatching {
                HdrStrength.valueOf(prefs.getString(KEY_STRENGTH, null) ?: HdrStrength.BALANCED.name)
            }.getOrDefault(HdrStrength.BALANCED),
            replaceOriginal = prefs.getBoolean(KEY_REPLACE, true)
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var checkJob: Job? = null
    private var convertJob: Job? = null
    private var previewJob: Job? = null
    private var previewBase: Bitmap? = null

    /** If the app was killed half way through an in-place update last time, put the original back first. */
    private val recoveryJob: Job = viewModelScope.launch(Dispatchers.IO) {
        val report = runCatching { RecoveryStore.recover(context) }.getOrNull() ?: return@launch
        if (!report.any) return@launch
        val parts = buildList {
            if (report.restored > 0) add("${photos(report.restored)} put back as ${if (report.restored == 1) "it was" else "they were"}")
            if (report.exported > 0) add("${photos(report.exported)} saved to the ${MediaStoreSaver.COPY_FOLDER} album")
        }
        _state.update {
            it.copy(notice = Notice("The last conversion was cut off. " + parts.joinToString(", ") + ".", isError = false))
        }
    }

    // ------------------------------------------------------------------------------------------
    // Queue
    // ------------------------------------------------------------------------------------------

    fun addPhotos(uris: List<Uri>) {
        if (_state.value.phase is Phase.Working) return
        // Adding photos after a finished run starts a new one.
        if (_state.value.phase is Phase.Finished) {
            _state.update { it.copy(photos = emptyList(), results = emptyMap(), phase = Phase.Idle) }
        }
        val known = _state.value.photos.map { it.uri }.toSet()
        val fresh = uris.distinct().filter { it !in known }
        if (fresh.isEmpty()) return
        _state.update { s -> s.copy(photos = s.photos + fresh.map { PhotoItem(it, name = "Photo") }, notice = null) }

        // Look at each new photo off the main thread: its name, where it lives, whether it is HDR already.
        val previous = checkJob
        checkJob = viewModelScope.launch(Dispatchers.IO) {
            previous?.join()
            for (uri in fresh) {
                val checked = check(uri)
                _state.update { s -> s.copy(photos = s.photos.map { if (it.uri == uri) checked else it }) }
            }
        }
    }

    private fun check(uri: Uri): PhotoItem {
        val name = runCatching { MediaStoreSaver.displayName(context, uri) }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "Photo"
        val target = runCatching { MediaStoreSaver.resolveTarget(context, uri) }.getOrNull()
        var alreadyHdr = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(ImageExifHelper.HEADER_SCAN_BYTES)
                var filled = 0
                while (filled < buffer.size) {
                    val read = input.read(buffer, filled, buffer.size - filled)
                    if (read < 0) break
                    filled += read
                }
                ImageExifHelper.looksLikeUltraHdr(buffer, filled)
            } ?: false
        }.getOrDefault(false)

        if (!alreadyHdr && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            alreadyHdr = runCatching {
                val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bmp = android.graphics.BitmapFactory.decodeStream(stream, null, options)
                    val hasGainmap = bmp?.hasGainmap() == true
                    bmp?.recycle()
                    hasGainmap
                } ?: false
            }.getOrDefault(false)
        }

        return PhotoItem(uri = uri, name = name, checked = true, target = target, alreadyHdr = alreadyHdr)
    }

    fun removePhoto(uri: Uri) {
        if (_state.value.phase !is Phase.Idle) return
        _state.update { s -> s.copy(photos = s.photos.filterNot { it.uri == uri }) }
    }

    fun clearPhotos() {
        if (_state.value.phase !is Phase.Idle) return
        _state.update { it.copy(photos = emptyList(), results = emptyMap()) }
    }

    // ------------------------------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------------------------------

    fun setStrength(strength: HdrStrength) {
        if (_state.value.strength == strength) return
        _state.update { it.copy(strength = strength) }
        prefs.edit().putString(KEY_STRENGTH, strength.name).apply()
        if (_state.value.preview != null) renderPreview()
    }

    fun setReplaceOriginal(enabled: Boolean) {
        _state.update { it.copy(replaceOriginal = enabled) }
        prefs.edit().putBoolean(KEY_REPLACE, enabled).apply()
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    // ------------------------------------------------------------------------------------------
    // Converting
    // ------------------------------------------------------------------------------------------

    /**
     * Starts the batch. Updating originals needs the person's say-so first, which Android asks for
     * in one system dialog covering every photo.
     */
    fun convert() {
        if (_state.value.phase is Phase.Working || convertJob?.isActive == true) return
        if (_state.value.photos.isEmpty()) return
        convertJob = viewModelScope.launch {
            recoveryJob.join()
            checkJob?.join()
            val current = _state.value
            if (current.replaceOriginal && !Environment.isExternalStorageManager()) {
                val needConsent = current.photos
                    .filter { !it.alreadyHdr }
                    .mapNotNull { it.target?.mediaUri }
                    .distinct()
                if (needConsent.isNotEmpty()) {
                    val request = runCatching {
                        MediaStore.createWriteRequest(context.contentResolver, needConsent)
                    }.getOrNull()
                    if (request != null) {
                        _events.emit(UiEvent.RequestWritePermission(request.intentSender))
                        return@launch
                    }
                }
            }
            run()
        }
    }

    fun onWritePermissionResult(granted: Boolean) {
        if (granted) {
            convertJob = viewModelScope.launch { run() }
        } else {
            _state.update {
                it.copy(
                    notice = Notice(
                        "Nothing was changed. To keep your originals as they are, turn off " +
                            "\"Update original photos\" and HDR copies will be saved instead.",
                        isError = true
                    )
                )
            }
        }
    }

    private suspend fun run() {
        val start = _state.value
        val photos = start.photos
        if (photos.isEmpty()) return
        _state.update {
            it.copy(results = emptyMap(), notice = null, preview = null, phase = Phase.Working(0, photos.size, null, ""))
        }
        try {
            converter.convertAll(
                items = photos,
                replaceOriginal = start.replaceOriginal,
                strength = start.strength,
                onStart = { index, item ->
                    _state.update { it.copy(phase = Phase.Working(index, photos.size, item.uri, item.name)) }
                },
                onResult = { result ->
                    _state.update { it.copy(results = it.results + (result.uri to result)) }
                }
            )
            _state.update { it.copy(phase = Phase.Finished(stopped = false)) }
        } catch (e: CancellationException) {
            // Stopped between two photos. Whatever was finished stays finished.
            _state.update {
                it.copy(phase = if (it.results.isEmpty()) Phase.Idle else Phase.Finished(stopped = true))
            }
            throw e
        } catch (e: Throwable) {
            // Should not happen: every photo reports its own failure. If it does, don't leave the
            // screen stuck on "Converting".
            _state.update {
                it.copy(
                    phase = if (it.results.isEmpty()) Phase.Idle else Phase.Finished(stopped = true),
                    notice = Notice("Something went wrong and the conversion stopped early. Your photos were not harmed.", isError = true)
                )
            }
        }
    }

    fun stop() {
        convertJob?.cancel()
    }

    /** Leaves the result screen. */
    fun done() {
        if (_state.value.phase is Phase.Working) return
        _state.update { it.copy(photos = emptyList(), results = emptyMap(), phase = Phase.Idle) }
    }

    // ------------------------------------------------------------------------------------------
    // Preview
    // ------------------------------------------------------------------------------------------

    fun openPreview(item: PhotoItem) {
        previewJob?.cancel()
        previewBase = null
        _state.update { it.copy(preview = PreviewUi(item)) }
        previewJob = viewModelScope.launch(Dispatchers.Default) {
            val base = loadForPreview(item.uri)
            if (base == null) {
                _state.update { s -> s.copy(preview = s.preview?.copy(loading = false, failed = true)) }
                return@launch
            }
            previewBase = base
            buildPreview(base)
        }
    }

    private fun renderPreview() {
        val base = previewBase ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch(Dispatchers.Default) { buildPreview(base) }
    }

    private fun buildPreview(base: Bitmap) {
        val strength = _state.value.strength
        val result = runCatching {
            // Made once per photo and kept when only the strength changes.
            val ambient = _state.value.preview?.ambient
                ?: runCatching { UltraHdrEngine.ambientWash(base) }.getOrNull()
            if (base.hasGainmap()) {
                // Already HDR: show it as it is next to a plain version of itself.
                val plain = base.copy(Bitmap.Config.ARGB_8888, true).apply { gainmap = null }
                PreviewUi(
                    item = _state.value.preview?.item ?: return, loading = false,
                    original = plain, hdr = base, ambient = ambient, alreadyHdr = true
                )
            } else {
                val plan = UltraHdrEngine.plan(base, strength)
                val hdr = base.copy(Bitmap.Config.ARGB_8888, true)
                UltraHdrEngine.attach(hdr, plan)
                PreviewUi(
                    item = _state.value.preview?.item ?: return, loading = false,
                    original = base, hdr = hdr, boostMap = UltraHdrEngine.gainMapPicture(plan),
                    ambient = ambient,
                    peakBoost = plan.peakBoost, boostedShare = plan.boostedShare,
                    noHighlights = !plan.hasVisibleEffect
                )
            }
        }.getOrNull()
        _state.update { s ->
            val open = s.preview ?: return@update s
            s.copy(preview = result ?: open.copy(loading = false, failed = true))
        }
    }

    /** The photo, upright, at about screen size. */
    private fun loadForPreview(uri: Uri): Bitmap? = try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longSide = maxOf(info.size.width, info.size.height)
            var sample = 1
            while (longSide / sample > PREVIEW_MAX_SIDE) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
    } catch (_: Throwable) {
        null
    }

    fun closePreview() {
        previewJob?.cancel()
        previewBase = null
        _state.update { it.copy(preview = null) }
    }

    private fun photos(count: Int) = if (count == 1) "1 original photo was" else "$count original photos were"

    private companion object {
        const val KEY_STRENGTH = "strength"
        const val KEY_REPLACE = "replace_original"
        const val PREVIEW_MAX_SIDE = 2400
    }
}
