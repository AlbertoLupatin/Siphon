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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItemColors
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.siphon.SiphonApp
import app.siphon.data.AppSettings
import app.siphon.data.DownloadItem
import app.siphon.engine.Engine
import app.siphon.player.PlayerActivity
import app.siphon.ui.components.LogDialog
import app.siphon.ui.components.copyText
import app.siphon.ui.components.openFile
import app.siphon.ui.components.shareFiles
import app.siphon.ui.download.DownloadScreen
import app.siphon.ui.download.DownloadViewModel
import app.siphon.ui.library.LibraryCallbacks
import app.siphon.ui.library.LibraryScreen
import app.siphon.ui.queue.QueueCallbacks
import app.siphon.ui.queue.QueuePane
import app.siphon.ui.settings.SettingsScreen
import app.siphon.ui.theme.SiphonTheme
import kotlinx.coroutines.launch

/** Download + queue side by side only when both get real room (tablet landscape). */
val TWO_PANE_MIN = 1100.dp

enum class Dest(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Download("Download", Icons.Outlined.Download, Icons.Filled.Download),
    Library("Library", Icons.Outlined.VideoLibrary, Icons.Filled.VideoLibrary),
    Settings("Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {

    private val downloadVm: DownloadViewModel by viewModels()
    private var shareSignal by mutableIntStateOf(0)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        askNotifications()

        val app = SiphonApp.get(this)
        setContent {
            val settings by app.settings.settings.collectAsState(initial = AppSettings())
            SiphonTheme(materialYou = settings.materialYou) { AppRoot(downloadVm, settings, shareSignal) }
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
        shareSignal++
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun AppRoot(vm: DownloadViewModel, settings: AppSettings, shareSignal: Int) {
    val context = LocalContext.current
    val app = remember { SiphonApp.get(context) }
    var dest by rememberSaveable { mutableStateOf(Dest.Download) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val message by vm.messages.collectAsStateWithLifecycle()
    val items by app.downloads.items.collectAsStateWithLifecycle()
    val engine by Engine.state.collectAsStateWithLifecycle()
    val downloadState by vm.state.collectAsStateWithLifecycle()
    var logFor by remember { mutableStateOf<DownloadItem?>(null) }
    var updating by remember { mutableStateOf(false) }
    val activeCount = items.count { it.status.isActive }

    LaunchedEffect(shareSignal) { if (shareSignal > 0) dest = Dest.Download }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.messages.value = null } }

    val repo = app.downloads
    val open: (DownloadItem) -> Unit = { if (it.outputs.isNotEmpty()) PlayerActivity.start(context, it.id) }
    val openExternal: (DownloadItem) -> Unit = { it.outputs.firstOrNull()?.let { out -> openFile(context, out) } }
    val queueCb = remember {
        QueueCallbacks(
            cancel = { repo.cancel(it.id) }, retry = { repo.retry(it.id) }, open = open,
            showLog = { logFor = it }, cancelAll = repo::cancelAll, openLibrary = { dest = Dest.Library },
        )
    }
    val libraryCb = remember {
        LibraryCallbacks(
            open = open,
            openExternal = openExternal,
            share = { shareFiles(context, it.outputs) },
            copyLink = { copyText(context, "Link", it.url) },
            retry = { repo.retry(it.id) },
            showLog = { logFor = it },
            remove = { repo.remove(it.id, deleteFiles = false) },
            deleteFile = { repo.remove(it.id, deleteFiles = true) },
            clearFinished = repo::clearFinished,
        )
    }

    SiphonScaffold(dest, { dest = it }, activeCount, snackbar) { width ->
        when (dest) {
            Dest.Download -> DownloadScreen(
                state = downloadState, engine = engine, actions = vm, twoPane = width >= TWO_PANE_MIN,
                queue = { m -> QueuePane(items, queueCb, m) },
            )
            Dest.Library -> LibraryScreen(items, width >= 720.dp, libraryCb)
            Dest.Settings -> SettingsScreen(
                settings = settings,
                engine = engine,
                updating = updating,
                onChange = { f -> scope.launch { app.settings.update(f) } },
                onUpdateNow = {
                    updating = true
                    scope.launch {
                        val msg = runCatching { Engine.update(context, settings.nightly) }.fold(
                            { if (it) "yt-dlp updated" else "yt-dlp is already up to date" },
                            { "Couldn't update yt-dlp: ${it.message}" },
                        )
                        app.settings.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
                        updating = false
                        snackbar.showSnackbar(msg)
                    }
                },
            )
        }
    }
    logFor?.let { LogDialog(it, { logFor = null }, context) }
}

/** App chrome: rail on tablets, bottom bar on phones. Stateless so screenshot tests can render it. */
@Composable
fun SiphonScaffold(
    dest: Dest,
    onDest: (Dest) -> Unit,
    activeCount: Int,
    snackbar: SnackbarHostState,
    content: @Composable (width: Dp) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val itemColors = NavigationSuiteItemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            indicatorColor = scheme.primaryContainer, selectedIconColor = scheme.onPrimaryContainer,
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            indicatorColor = scheme.primaryContainer, selectedIconColor = scheme.onPrimaryContainer,
        ),
        navigationDrawerItemColors = NavigationDrawerItemDefaults.colors(),
    )
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Dest.entries.forEach { d ->
                item(
                    selected = dest == d,
                    onClick = { onDest(d) },
                    icon = {
                        val icon = if (dest == d) d.selectedIcon else d.icon
                        if (d == Dest.Download && activeCount > 0) {
                            BadgedBox(badge = { Badge(containerColor = scheme.secondary, contentColor = scheme.onSecondary) { Text("$activeCount") } }) {
                                Icon(icon, d.label)
                            }
                        } else Icon(icon, d.label)
                    },
                    label = { Text(d.label) },
                    colors = itemColors,
                )
            }
        },
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationRailContainerColor = scheme.surface,
            navigationBarContainerColor = scheme.surfaceContainer,
        ),
        containerColor = scheme.surface,
    ) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = scheme.surface) { _ ->
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                content(maxWidth)
            }
        }
    }
}
