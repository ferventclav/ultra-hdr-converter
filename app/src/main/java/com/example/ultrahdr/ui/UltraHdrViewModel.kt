package com.example.ultrahdr.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ultrahdr.ConversionService
import com.example.ultrahdr.processing.BatchConverter
import com.example.ultrahdr.processing.ConvertOptions
import com.example.ultrahdr.processing.HdrStrength
import com.example.ultrahdr.processing.ImageExifHelper
import com.example.ultrahdr.processing.LocalPhoto
import com.example.ultrahdr.processing.LocalPhotos
import com.example.ultrahdr.processing.MediaStoreSaver
import com.example.ultrahdr.processing.PhotoItem
import com.example.ultrahdr.processing.PhotoNames
import com.example.ultrahdr.processing.PhotoAccess
import com.example.ultrahdr.processing.PhotoResult
import com.example.ultrahdr.processing.RecoveryStore
import com.example.ultrahdr.processing.UltraHdrContainer
import com.example.ultrahdr.processing.UltraHdrEngine
import com.example.ultrahdr.processing.UndoStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Phase {
    data object Idle : Phase
    /** Photos picked: being looked at, or waiting for Android's say-so. Conversion starts by itself. */
    data object Preparing : Phase
    data class Working(val done: Int, val total: Int, val currentUri: Uri?, val currentName: String) : Phase
    /** [stopped] is true when the person pressed Stop before the end. */
    data class Finished(val stopped: Boolean) : Phase
    /** Undo: the last batch's originals are being put back. */
    data object Undoing : Phase
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
    val askAboutPrompts: Boolean = false,
    /** The finished batch changed photos that Undo can put back. */
    val canUndo: Boolean = false
)

/** The app's own photo grid: only photos stored on the phone, so every one can be updated in place. */
data class GalleryState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val access: PhotoAccess = PhotoAccess.ALL,
    val photos: List<LocalPhoto> = emptyList(),
    /** The album tab shown. Null is "All". */
    val album: String? = null,
    /** Picked photo ids, in the order they were tapped. */
    val selected: Set<Long> = emptySet(),
    /** What Clear just took away, kept while its toast offers Undo. Empty the rest of the time. */
    val cleared: Set<Long> = emptySet()
)

sealed class UiEvent {
    data class RequestWritePermission(val intentSender: IntentSender) : UiEvent()
    /** Android's photo access dialog, for the grid. */
    data object RequestPhotoAccess : UiEvent()
    /** Opens Android's "Media management" page for this app. */
    data object OpenMediaManagement : UiEvent()
    /** Android's notification permission, so a long batch can show its progress outside the app. */
    data object RequestNotifications : UiEvent()
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

    /** Shared by every screen: the batch outlives this view model (see [ConversionSession]). */
    private val _state = ConversionSession.state.apply { update { it.copy(updatesWithoutAsking = canSkipPrompts()) } }
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val _gallery = MutableStateFlow(GalleryState())
    val gallery: StateFlow<GalleryState> = _gallery.asStateFlow()

    private var checkJob: Job?
        get() = ConversionSession.checkJob
        set(value) { ConversionSession.checkJob = value }
    private var convertJob: Job?
        get() = ConversionSession.convertJob
        set(value) { ConversionSession.convertJob = value }
    private var galleryJob: Job? = null
    private val session = ConversionSession.scope

    /**
     * Once per app start: if the app was killed half way through an in-place update, the original
     * is put back first. The last run's Undo list is gone with its result screen, so it is deleted.
     */
    private val recoveryJob: Job = ConversionSession.startup {
        session.launch(Dispatchers.IO) {
            runCatching { UndoStore.clear(context) }
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
    }

    // ------------------------------------------------------------------------------------------
    // Photos in
    // ------------------------------------------------------------------------------------------

    /** Picked or shared photos start a new batch, which converts by itself. */
    fun addPhotos(uris: List<Uri>) {
        val phase = _state.value.phase
        if (phase is Phase.Working || phase is Phase.Preparing || phase is Phase.Undoing) return
        val fresh = uris.distinct()
        if (fresh.isEmpty()) return
        forgetUndo()
        _state.update {
            it.copy(
                photos = fresh.map { uri -> PhotoItem(uri, name = "Photo") },
                results = emptyMap(),
                notice = null,
                phase = Phase.Preparing,
                canUndo = false
            )
        }

        // Look at each photo off the main thread: its name, where it lives, whether it is HDR already.
        checkJob?.cancel()
        checkJob = session.launch(Dispatchers.IO) {
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
        // The whole file, not only its start: the labels can sit after a large EXIF block.
        val bytes = runCatching { MediaStoreSaver.readOriginal(context, uri, null)?.bytes }.getOrNull()
        val takenAt = runCatching { MediaStoreSaver.dateTaken(context, uri) }.getOrNull()
        // The real file's name when there is one. Otherwise the name the picker or the sharing app
        // gave, unless it was made for a cache (a bare number, Google Photos' long ids).
        val name = target?.file?.name ?: PhotoNames.readable(
            target?.mediaUri?.let { runCatching { MediaStoreSaver.displayName(context, it) }.getOrNull() }
                ?: runCatching { MediaStoreSaver.displayName(context, uri) }.getOrNull()
                ?: uri.lastPathSegment?.substringAfterLast('/'),
            bytes?.let { ImageExifHelper.getCaptureTimestamp(it) } ?: takenAt
        )
        val alreadyHdr = bytes != null && runCatching { UltraHdrEngine.hdrChecks(bytes).isNotEmpty() }.getOrDefault(false)
        val redoable = alreadyHdr && runCatching { UltraHdrContainer.strip(bytes!!) != null }.getOrDefault(false)
        return PhotoItem(
            uri = uri, name = name, checked = true, target = target,
            alreadyHdr = alreadyHdr, redoable = redoable, takenAt = takenAt
        )
    }

    // ------------------------------------------------------------------------------------------
    // The photo grid
    // ------------------------------------------------------------------------------------------

    fun openGallery() {
        _gallery.update { it.copy(open = true, loading = it.photos.isEmpty()) }
        reloadGallery()
    }

    fun closeGallery() {
        _gallery.update { it.copy(open = false, selected = emptySet(), cleared = emptySet()) }
    }

    /** Reads the library again: on opening, on coming back to the app, and after the access dialog. */
    fun reloadGallery() {
        if (!_gallery.value.open) return
        galleryJob?.cancel()
        galleryJob = viewModelScope.launch(Dispatchers.IO) {
            val access = LocalPhotos.access(context)
            val photos = if (access == PhotoAccess.NONE) emptyList() else runCatching { LocalPhotos.load(context) }.getOrDefault(emptyList())
            val ids = photos.mapTo(HashSet()) { it.id }
            _gallery.update { g ->
                g.copy(
                    loading = false,
                    access = access,
                    photos = photos,
                    album = g.album?.takeIf { album -> photos.any { it.album == album } },
                    selected = g.selected.filterTo(LinkedHashSet()) { it in ids },
                    cleared = g.cleared.filterTo(LinkedHashSet()) { it in ids }
                )
            }
        }
    }

    fun chooseAlbum(album: String?) = _gallery.update { it.copy(album = album) }

    // Picking again ends the chance to undo a Clear, so Undo never overwrites the new picks.
    fun togglePhoto(id: Long) = _gallery.update {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id, cleared = emptySet())
    }

    /** The round button by the tabs: picks every photo in the tab, or clears them when all are picked. */
    fun toggleAll(ids: List<Long>) = _gallery.update {
        it.copy(selected = if (it.selected.containsAll(ids)) it.selected - ids.toSet() else it.selected + ids, cleared = emptySet())
    }

    /** Empties the selection, and keeps it for a few seconds so the toast's Undo can bring it back. */
    fun clearSelection() = _gallery.update {
        if (it.selected.isEmpty()) it else it.copy(selected = emptySet(), cleared = it.selected)
    }

    fun undoClear() = _gallery.update {
        if (it.cleared.isEmpty()) it else it.copy(selected = it.cleared, cleared = emptySet())
    }

    /** The toast has gone, and the cleared selection with it. */
    fun forgetCleared() = _gallery.update { if (it.cleared.isEmpty()) it else it.copy(cleared = emptySet()) }

    /** Converts the picked photos the same way as picked or shared ones. */
    fun convertSelected() {
        val g = _gallery.value
        val byId = g.photos.associateBy { it.id }
        val uris = g.selected.mapNotNull { byId[it]?.uri }
        closeGallery()
        if (uris.isNotEmpty()) addPhotos(uris)
    }

    fun allowPhotoAccess() {
        viewModelScope.launch { _events.emit(UiEvent.RequestPhotoAccess) }
    }

    /** Leaves a batch that was not started, or the result screen. */
    fun clearPhotos() {
        if (_state.value.phase is Phase.Working || _state.value.phase is Phase.Undoing) return
        checkJob?.cancel()
        convertJob?.cancel()
        forgetUndo()
        _state.update {
            it.copy(photos = emptyList(), results = emptyMap(), phase = Phase.Idle, notice = null, askAboutPrompts = false, canUndo = false)
        }
    }

    /** The Undo list belongs to one result screen. Leaving it, or starting a new batch, drops it. */
    private fun forgetUndo() {
        if (!_state.value.canUndo) return
        ConversionSession.undoCleanup = session.launch(Dispatchers.IO) { runCatching { UndoStore.clear(context) } }
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
        convertJob = session.launch {
            recoveryJob.join()
            checkJob?.join()
            val current = _state.value
            val needConsent = current.photos
                .filter { !it.alreadyHdr || it.redoable }
                .mapNotNull { it.target?.mediaUri }
                .distinct()
            if (askToModify(needConsent, WriteRequestFor.CONVERT)) return@launch
            run()
        }
    }

    /**
     * Android's "Allow this app to modify these photos?" request, answered without a dialog when
     * media management is on. True when the request went out and the answer will continue the work.
     */
    private suspend fun askToModify(photos: List<Uri>, purpose: WriteRequestFor): Boolean {
        if (photos.isEmpty() || runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)) return false
        val request = runCatching { MediaStore.createWriteRequest(context.contentResolver, photos) }.getOrNull()
            ?: return false
        ConversionSession.writeRequestFor = purpose
        _events.emit(UiEvent.RequestWritePermission(request.intentSender))
        return true
    }

    fun onWritePermissionResult(granted: Boolean) {
        val purpose = ConversionSession.writeRequestFor
        ConversionSession.writeRequestFor = WriteRequestFor.CONVERT
        when {
            purpose == WriteRequestFor.UNDO -> if (granted) {
                convertJob = session.launch { putBack() }
            } else {
                _state.update { it.copy(notice = Notice("Android didn't allow it, so nothing was put back.", isError = true)) }
            }
            granted -> convertJob = session.launch { run() }
            // The header says what happened and the toolbar offers Try again; no notice needed.
            else -> _state.update { it.copy(phase = Phase.Idle) }
        }
    }

    private suspend fun run() {
        val photos = _state.value.photos
        if (photos.isEmpty()) return
        ConversionSession.undoCleanup?.join()
        withContext(Dispatchers.IO) { runCatching { UndoStore.clear(context) } }
        _state.update {
            it.copy(results = emptyMap(), notice = null, canUndo = false, phase = Phase.Working(0, photos.size, null, ""))
        }
        // Keeps the batch going, with a progress notification, if the person leaves the app.
        runCatching { context.startForegroundService(Intent(context, ConversionService::class.java)) }
        askForNotifications(photos.size)
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
            val undoable = withContext(Dispatchers.IO) { runCatching { UndoStore.any(context) }.getOrDefault(false) }
            _state.update { it.copy(phase = Phase.Finished(stopped = false), canUndo = undoable) }
        } catch (e: CancellationException) {
            // Stopped between two photos. Whatever was finished stays finished, and can be undone.
            val undoable = withContext(NonCancellable + Dispatchers.IO) {
                runCatching { UndoStore.any(context) }.getOrDefault(false)
            }
            _state.update {
                it.copy(phase = if (it.results.isEmpty()) Phase.Idle else Phase.Finished(stopped = true), canUndo = undoable)
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

    /** Asked once, the first time a batch is long enough that someone might switch apps meanwhile. */
    private suspend fun askForNotifications(count: Int) {
        if (count < 4 || prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
        _events.emit(UiEvent.RequestNotifications)
    }

    fun stop() {
        convertJob?.cancel()
    }

    /** Leaves the result screen. */
    fun done() {
        if (_state.value.phase is Phase.Working || _state.value.phase is Phase.Undoing) return
        forgetUndo()
        _state.update { it.copy(photos = emptyList(), results = emptyMap(), phase = Phase.Idle, canUndo = false) }
    }

    // ------------------------------------------------------------------------------------------
    // Undo
    // ------------------------------------------------------------------------------------------

    /** Puts the last batch's originals back and deletes its new copies. */
    fun undo() {
        val s = _state.value
        if (s.phase !is Phase.Finished || !s.canUndo || convertJob?.isActive == true) return
        convertJob = session.launch {
            val photos = withContext(Dispatchers.IO) { runCatching { UndoStore.photosToPutBack(context) }.getOrDefault(emptyList()) }
            if (askToModify(photos, WriteRequestFor.UNDO)) return@launch
            putBack()
        }
    }

    private suspend fun putBack() {
        _state.update { it.copy(phase = Phase.Undoing, notice = null) }
        val report = withContext(Dispatchers.IO + NonCancellable) { runCatching { UndoStore.undo(context) }.getOrNull() }
        val text = when {
            report == null -> "Undo didn't finish. Some photos may still be HDR."
            else -> buildList {
                if (report.putBack > 0) add("${photos(report.putBack)} put back as ${if (report.putBack == 1) "it was" else "they were"}")
                if (report.removed > 0) add(if (report.removed == 1) "1 HDR copy was deleted" else "${report.removed} HDR copies were deleted")
                if (report.changed > 0) add(if (report.changed == 1) "1 photo was changed since, so it was left as it is" else "${report.changed} photos were changed since, so they were left as they are")
                if (report.failed > 0) add(if (report.failed == 1) "1 couldn't be undone" else "${report.failed} couldn't be undone")
            }.joinToString(". ").ifEmpty { "Nothing needed putting back" } + "."
        }
        val failed = report == null || report.failed > 0
        _state.update {
            it.copy(photos = emptyList(), results = emptyMap(), phase = Phase.Idle, canUndo = false, notice = Notice(text, isError = failed))
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    private fun photos(count: Int) = if (count == 1) "1 original photo was" else "$count original photos were"

    private companion object {
        /** Version 1.9's look, originals updated in place, earlier conversions done again. */
        val AUTOMATIC = ConvertOptions(replaceOriginal = true, strength = HdrStrength.BALANCED, redo = true)
        const val KEY_ASKED_ABOUT_PROMPTS = "asked_about_prompts"
        const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
    }
}
