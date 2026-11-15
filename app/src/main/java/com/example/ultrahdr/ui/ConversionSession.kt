package com.example.ultrahdr.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** What the next answer to Android's "Allow this app to modify these photos?" request is for. */
internal enum class WriteRequestFor { CONVERT, UNDO }

/**
 * The batch belongs to the app, not to one screen. It keeps going when the screen is closed or
 * another app is opened, while [com.example.ultrahdr.ConversionService] holds a notification so
 * Android keeps the app running. A screen opened later shows the same batch.
 */
internal object ConversionSession {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val state = MutableStateFlow(UiState())

    var checkJob: Job? = null
    var convertJob: Job? = null
    /** Deleting the last Undo list. A new batch waits for it, so it never deletes the new list. */
    var undoCleanup: Job? = null
    var writeRequestFor = WriteRequestFor.CONVERT

    /** The app's screens on show right now. A finished batch only gets a notification when this is 0. */
    @Volatile
    var screensShown = 0

    private var startupJob: Job? = null

    /** Runs [start] once for the whole app: putting back an original whose update was cut off. */
    fun startup(start: () -> Job): Job = startupJob ?: start().also { startupJob = it }
}
