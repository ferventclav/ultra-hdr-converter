package com.example.ultrahdr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ultrahdr.ui.UiEvent
import com.example.ultrahdr.ui.UltraHdrApp
import com.example.ultrahdr.ui.UltraHdrViewModel
import com.example.ultrahdr.ui.theme.UltraHdrTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: UltraHdrViewModel by viewModels()

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* The app works either way: without access, photos are saved as copies. */ }

    /** Android's own "Allow this app to modify N photos?" dialog. */
    private val writeRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onWritePermissionResult(granted = result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            requestMediaAccessIfNeeded()
            // Only for a fresh start. After a rotation the photos are already in the view model.
            handleShareIntent(intent)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is UiEvent.RequestWritePermission -> writeRequestLauncher.launch(
                            IntentSenderRequest.Builder(event.intentSender).build()
                        )
                    }
                }
            }
        }

        setContent {
            UltraHdrTheme {
                UltraHdrApp(viewModel)
            }
        }
    }

    /**
     * Photo access lets the app find the real file behind a picked photo, and location access lets
     * it read that file complete with its GPS position. Both are needed to update a photo in place.
     * Asked once per install state: not again when access, full or partial, is already there.
     */
    private fun requestMediaAccessIfNeeded() {
        fun granted(permission: String) =
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        val hasPhotos = granted(Manifest.permission.READ_MEDIA_IMAGES) ||
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        if (hasPhotos && granted(Manifest.permission.ACCESS_MEDIA_LOCATION)) return
        mediaPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            )
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /** Photos shared to the app from Gallery, Google Photos or any other app. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null || intent.type?.startsWith("image/") != true) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { viewModel.addPhotos(listOf(it)) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { viewModel.addPhotos(it) }
            }
        }
    }
}
