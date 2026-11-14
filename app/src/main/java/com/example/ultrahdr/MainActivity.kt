package com.example.ultrahdr

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ultrahdr.processing.LocalPhotos
import com.example.ultrahdr.processing.PhotoAccess
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

    /** Asked again from the photo grid. The grid reads the library afresh with whatever was allowed. */
    private val photoAccessLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.reloadGallery() }

    /** Android's own "Allow this app to modify N photos?" dialog. With media management on, no dialog shows. */
    private val writeRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onWritePermissionResult(granted = result.resultCode == RESULT_OK)
    }

    /** Settings, Apps, Special access, Media management, for this app only. */
    private val mediaManagementLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.onMediaManagementResult() }

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
                        UiEvent.OpenMediaManagement -> openMediaManagement()
                        UiEvent.RequestPhotoAccess -> requestPhotoAccess()
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

    override fun onResume() {
        super.onResume()
        // Media management may have been turned on or off in Settings meanwhile, and photos taken.
        viewModel.refreshAccess()
        viewModel.reloadGallery()
    }

    /**
     * After two refusals Android stops showing its dialog, so the app's page in Settings opens
     * instead, where photo access can still be turned on.
     */
    private fun requestPhotoAccess() {
        val refusedForGood = LocalPhotos.access(this) == PhotoAccess.NONE &&
            !shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_IMAGES)
        if (refusedForGood) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        photoAccessLauncher.launch(
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            )
        )
    }

    private fun openMediaManagement() {
        val forThisApp = Uri.fromParts("package", packageName, null)
        val pages = listOf(
            Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, forThisApp),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, forThisApp)
        )
        for (page in pages) {
            try {
                mediaManagementLauncher.launch(page)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        viewModel.onMediaManagementResult()
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
