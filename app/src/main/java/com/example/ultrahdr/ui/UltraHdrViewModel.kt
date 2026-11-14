package com.example.ultrahdr.ui

import android.app.Application
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ultrahdr.processing.BatchConverter
import com.example.ultrahdr.processing.ConvertOptions
import com.example.ultrahdr.processing.CopyReport
import com.example.ultrahdr.processing.HdrStrength
import com.example.ultrahdr.processing.MediaStoreSaver
import com.example.ultrahdr.processing.PhotoItem
import com.example.ultrahdr.processing.PhotoResult
import com.example.ultrahdr.processing.RecoveryStore
import com.example.ultrahdr.processing.UltraHdrContainer
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
    /** Photos picked: being looked at, or waiting for Android's say-so. Conversion starts by itself. */
    data object Preparing : Phase
    data class Working(val done: Int, val total: Int, val currentUri: Uri?, val currentName: String) : Phase
    /** [stopped] is true when the person pressed Stop before the end. */
    data class Finished(val stopped: Boolean) : Phase
}

data class Notice(val text: String, val isError: Boolean)

data class UiState(
    val photos: List<PhotoItem> = emptyList(),
    val results: Map<Uri, PhotoResult> = emptyMap(),
    val phase: Phase = Phase.Idle,
    val notice: Notice? = null,
    /** Android lets this app update photos without asking first (media management is on). */
    val updatesWithoutAsking: Boolean = false,
    /** The one-time question about turning media management on, shown before the first conversion. */
    val askAboutPrompts: Boolean = false
)

sealed class UiEvent {
    data class RequestWritePermission(val intentSender: IntentSender) : UiEvent()
    /** Opens Android's "Media management" page for this app. */
    data object OpenMediaManagement : UiEvent()
}

/**
 * Fully automatic: picking or sharing photos converts them straight away, the way version 1.9 did,
 * with no settings. Originals are updated where they are, photos an earlier version converted are
 * done again, and photos that are HDR already are never touched.
 */
class UltraHdrViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val converter = BatchConverter(context)

    private val _state = MutableStateFlow(UiState(updatesWithoutAsking = canSkipPrompts()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var checkJob: Job? = null
    private var convertJob: Job? = null

    /** If the app was killed half way through an in-place update last time, put the original back first. */
    private val recoveryJob: Job = viewModelScope.launch(Dispatchers.IO) {
        val report = runCatching { RecoveryStore.recover(context) }.getOrNull() ?: return@launch
        if (!report.any) return@launch
        val parts = buildList {
            if (report.restored > 0) add("${photos(report.restored)} put back as ${if (report.restored == 1) "it was" else "they were"}")
            if (report.waiting > 0) add("${photos(report.waiting)} will be put back next time the app opens")
        }
        _state.update {
            it.copy(notice = Notice("The last conversion was cut off. " + parts.joinToString(", ") + ".", isError = false))
        }
    }

    // ------------------------------------------------------------------------------------------
    // Photos in
    // ------------------------------------------------------------------------------------------

    /** Picked or shared photos start a new batch, which converts by itself. */
    fun addPhotos(uris: List<Uri>) {
        val phase = _state.value.phase
        if (phase is Phase.Working || phase is Phase.Preparing) return
        val fresh = uris.distinct()
        if (fresh.isEmpty()) return
        _state.update {
            it.copy(
                photos = fresh.map { uri -> PhotoItem(uri, name = "Photo") },
                results = emptyMap(),
                notice = null,
                phase = Phase.Preparing
            )
        }

        // Look at each photo off the main thread: its name, where it lives, whether it is HDR already.
        checkJob?.cancel()
        checkJob = viewModelScope.launch(Dispatchers.IO) {
            for (uri in fresh) {
                val checked = check(uri)
                _state.update { s -> s.copy(photos = s.photos.map { if (it.uri == uri) checked else it }) }
            }
        }

        if (!_state.value.updatesWithoutAsking && !prefs.getBoolean(KEY_ASKED_ABOUT_PROMPTS, false)) {
            // Asked once, before the first conversion. The answer starts the batch.
            _state.update { it.copy(askAboutPrompts = true) }
        } else {
            convert()
        }
    }

    private fun check(uri: Uri): PhotoItem {
        val target = runCatching { MediaStoreSaver.resolveTarget(context, uri) }.getOrNull()
        // The real file's name when there is one: the photo picker hands out names like 10866.jpg.
        val name = target?.file?.name
            ?: target?.mediaUri?.let { runCatching { MediaStoreSaver.displayName(context, it) }.getOrNull() }
            ?: runCatching { MediaStoreSaver.displayName(context, uri) }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "Photo"
        // The whole file, not only its start: the labels can sit after a large EXIF block.
        val bytes = runCatching { MediaStoreSaver.readOriginal(context, uri, null)?.bytes }.getOrNull()
        val alreadyHdr = bytes != null && runCatching { UltraHdrEngine.hdrChecks(bytes).isNotEmpty() }.getOrDefault(false)
        val redoable = alreadyHdr && runCatching { UltraHdrContainer.strip(bytes!!) != null }.getOrDefault(false)
        return PhotoItem(uri = uri, name = name, checked = true, target = target, alreadyHdr = alreadyHdr, redoable = redoable)
    }

    /** Leaves a batch that was not started, or the result screen. */
    fun clearPhotos() {
        if (_state.value.phase is Phase.Working) return
        checkJob?.cancel()
        convertJob?.cancel()
        _state.update { it.copy(photos = emptyList(), results = emptyMap(), phase = Phase.Idle, notice = null, askAboutPrompts = false) }
    }

    // ------------------------------------------------------------------------------------------
    // Updating without being asked
    // ------------------------------------------------------------------------------------------

    /**
     * With media management on, Android's "Allow this app to modify these photos?" request is
     * granted straight away, without a dialog. All files access, granted to an older version, does
     * the same.
     */
    private fun canSkipPrompts(): Boolean =
        runCatching { MediaStore.canManageMedia(context) }.getOrDefault(false) ||
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /** Called when the app comes back to the front: the person may have changed it in Settings. */
    fun refreshAccess() {
        val now = canSkipPrompts()
        if (now != _state.value.updatesWithoutAsking) _state.update { it.copy(updatesWithoutAsking = now) }
    }

    fun openMediaManagement() {
        prefs.edit().putBoolean(KEY_ASKED_ABOUT_PROMPTS, true).apply()
        _state.update { it.copy(askAboutPrompts = false) }
        viewModelScope.launch { _events.emit(UiEvent.OpenMediaManagement) }
    }

    /** Back from Settings. A batch waiting on the answer goes ahead either way. */
    fun onMediaManagementResult() {
        refreshAccess()
        if (_state.value.phase is Phase.Preparing && convertJob?.isActive != true) convert()
    }

    fun notNow() {
        prefs.edit().putBoolean(KEY_ASKED_ABOUT_PROMPTS, true).apply()
        _state.update { it.copy(askAboutPrompts = false) }
        if (_state.value.phase is Phase.Preparing) convert()
    }

    // ------------------------------------------------------------------------------------------
    // Converting
    // ------------------------------------------------------------------------------------------

    /**
     * Starts the batch. Updating originals needs Android's say-so first: one request covering every
     * photo, answered without a dialog when media management is on.
     */
    fun convert() {
        if (_state.value.phase is Phase.Working || convertJob?.isActive == true) return
        if (_state.value.photos.isEmpty()) return
        _state.update { it.copy(phase = Phase.Preparing, notice = null) }
        convertJob = viewModelScope.launch {
            recoveryJob.join()
            checkJob?.join()
            val current = _state.value
            if (!runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)) {
                val needConsent = current.photos
                    .filter { !it.alreadyHdr || it.redoable }
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
            // The header says what happened and the toolbar offers Try again; no notice needed.
            _state.update { it.copy(phase = Phase.Idle) }
        }
    }

    private suspend fun run() {
        val photos = _state.value.photos
        if (photos.isEmpty()) return
        _state.update {
            it.copy(results = emptyMap(), notice = null, phase = Phase.Working(0, photos.size, null, ""))
        }
        try {
            converter.convertAll(
                items = photos,
                options = AUTOMATIC,
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

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    /** Plain text for the clipboard: what happened to each photo and why. */
    fun copyReport(): String {
        val s = _state.value
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("?")
        val header = "Ultra HDR Converter $version | ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} | " +
            "Android ${android.os.Build.VERSION.RELEASE} | automatic | " +
            "media management ${if (s.updatesWithoutAsking) "on" else "off"}"
        val ordered = s.photos.mapNotNull { s.results[it.uri] }
        return CopyReport.build(header, ordered)
    }

    private fun photos(count: Int) = if (count == 1) "1 original photo was" else "$count original photos were"

    private companion object {
        /** Version 1.9's look, originals updated in place, earlier conversions done again. */
        val AUTOMATIC = ConvertOptions(replaceOriginal = true, strength = HdrStrength.BALANCED, redo = true)
        const val KEY_ASKED_ABOUT_PROMPTS = "asked_about_prompts"
    }
}
