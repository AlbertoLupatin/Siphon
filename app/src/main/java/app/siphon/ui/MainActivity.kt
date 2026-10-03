package app.siphon.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.siphon.SiphonApp
import app.siphon.ui.download.DownloadScreen
import app.siphon.ui.download.DownloadViewModel
import app.siphon.ui.library.LibraryScreen
import app.siphon.ui.settings.SettingsScreen
import app.siphon.ui.theme.SiphonTheme

private enum class Dest(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Download("Download", Icons.Outlined.Download, Icons.Filled.Download),
    Library("Library", Icons.Outlined.VideoLibrary, Icons.Filled.VideoLibrary),
    Settings("Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {

    private val downloadVm: DownloadViewModel by viewModels()
    private var showDownloadTab by mutableStateOf(0)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        askNotifications()

        setContent {
            SiphonTheme { AppRoot(downloadVm, showDownloadTab) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        downloadVm.acceptText(text)
        showDownloadTab++
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun AppRoot(vm: DownloadViewModel, showDownloadSignal: Int) {
    var dest by rememberSaveable { mutableStateOf(Dest.Download) }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.messages.collectAsStateWithLifecycle()
    val app = SiphonApp.get(androidx.compose.ui.platform.LocalContext.current)
    val items by app.downloads.items.collectAsStateWithLifecycle()
    val activeCount = items.count { it.status.isActive }

    LaunchedEffect(showDownloadSignal) { if (showDownloadSignal > 0) dest = Dest.Download }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.messages.value = null }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Dest.entries.forEach { d ->
                item(
                    selected = dest == d,
                    onClick = { dest = d },
                    icon = {
                        val icon = if (dest == d) d.selectedIcon else d.icon
                        if (d == Dest.Download && activeCount > 0) {
                            BadgedBox(badge = { Badge { Text("$activeCount") } }) { Icon(icon, d.label) }
                        } else Icon(icon, d.label)
                    },
                    label = { Text(d.label) },
                )
            }
        },
    ) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { _ ->
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val wide = maxWidth >= 700.dp
                when (dest) {
                    Dest.Download -> DownloadScreen(vm, wide, onOpenLibrary = { dest = Dest.Library })
                    Dest.Library -> LibraryScreen(wide)
                    Dest.Settings -> SettingsScreen(snackbar)
                }
            }
        }
    }
}
