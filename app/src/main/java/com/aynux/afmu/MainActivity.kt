package com.aynux.afmu

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import com.aynux.afmu.core.LocaleHelper
import com.aynux.afmu.core.Prefs
import com.aynux.afmu.service.TransferService
import com.aynux.afmu.ui.AfmuTheme
import com.aynux.afmu.ui.MainScreen
import com.aynux.afmu.ui.ProvideAppLocale

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.reloadPrefs()
        }

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            viewModel.sendToPeer(uris)
        }

    private val requestAllFiles =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.reloadPrefs()
        }

    /** The camera is only ever used for the pairing scanner, so it is asked for on demand. */
    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.openScanner() else viewModel.reportCameraDenied()
        }

    /** Applies the saved language before any resource is resolved. */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRuntimePermissions()
        // Only resume serving if that is how the user left it; see Prefs.serverEnabled.
        if (Prefs(this).serverEnabled) TransferService.start(this)

        setContent {
            val state by viewModel.state.collectAsState()
            // Language changes take effect here, by re-resolving resources in place. The
            // activity is not rebuilt; see ProvideAppLocale for why.
            ProvideAppLocale(state.language) {
                AfmuTheme {
                    MainScreen(
                        state = state,
                        viewModel = viewModel,
                        onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                        onRequestFullStorage = { requestAllFilesAccess() },
                        onScanCode = { openScanner() },
                    )
                }
            }
        }

        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.reloadPrefs()
    }

    /** Files sent here from another app's share sheet go straight to the selected PC. */
    private fun handleShare(intent: Intent?) {
        // An afmu://pair link — the same payload the QR code carries. Routed through exactly
        // the same parser and the same pairing flow as a scan: a second way *in* must not
        // become a second set of rules about what a valid code is.
        if (intent?.action == Intent.ACTION_VIEW) {
            // Deliberately not onCodeScanned(): a link is not consent. See onPairLink.
            intent.dataString?.let { viewModel.onPairLink(it) }
            return
        }

        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtraCompat<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) viewModel.sendToPeer(uris)
    }

    private fun requestRuntimePermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (wanted.isNotEmpty()) requestPermissions.launch(wanted.toTypedArray())
    }

    private fun openScanner() {
        val granted = checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.openScanner() else requestCamera.launch(android.Manifest.permission.CAMERA)
    }

    /** All-files access lives in system settings; there is no runtime dialog for it. */
    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            requestRuntimePermissions()
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:$packageName".toUri()
        )
        runCatching { requestAllFiles.launch(intent) }.onFailure {
            requestAllFiles.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(key: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, T::class.java)
        } else {
            getParcelableExtra(key) as? T
        }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> Intent.getParcelableArrayListExtraCompat(
        key: String,
    ): ArrayList<T>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(key, T::class.java)
        } else {
            getParcelableArrayListExtra(key)
        }
}
