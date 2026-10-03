package app.siphon.screenshots

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.siphon.data.AppSettings
import app.siphon.data.DownloadOptions
import app.siphon.data.Preset
import app.siphon.engine.Engine
import app.siphon.engine.MediaFormat
import app.siphon.ui.Dest
import app.siphon.ui.SiphonScaffold
import app.siphon.ui.TWO_PANE_MIN
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.siphon.ui.download.DownloadActions
import app.siphon.ui.download.DownloadScreen
import app.siphon.ui.download.DownloadUiState
import app.siphon.ui.download.FetchState
import app.siphon.ui.library.LibraryCallbacks
import app.siphon.ui.library.LibraryScreen
import app.siphon.ui.queue.QueueCallbacks
import app.siphon.ui.queue.QueuePane
import app.siphon.ui.settings.SettingsScreen
import app.siphon.ui.theme.SiphonTheme
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Design-review renders of every screen. Run `./gradlew :app:recordRoborazziDebug`; images land in app/screenshots/.
 * Sizes follow the Honor tablet this app is tuned for (1412×904 dp landscape) plus portrait and a phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private object NoActions : DownloadActions {
        override fun setUrl(url: String) {}
        override fun acceptText(text: String) {}
        override fun fetch() {}
        override fun download() {}
        override fun setPlaylist(on: Boolean) {}
        override fun selectPreset(p: Preset) {}
        override fun selectFormat(f: MediaFormat) {}
        override fun toggleFormats() {}
        override fun updateOptions(f: (DownloadOptions) -> DownloadOptions) {}
    }

    private val queueCb = QueueCallbacks({}, {}, {}, {}, {}, {})
    private val libraryCb = LibraryCallbacks({}, {}, {}, {}, {}, {}, {}, {})
    private val engine = Engine.State.Ready("2026.08.19")

    @OptIn(ExperimentalCoilApi::class)
    private fun shoot(name: String, dest: Dest, content: @Composable (width: Dp) -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { request -> Fixtures.image(request.data.toString()) ?: Fixtures.image("thumb://studio")!! },
            ) {
                SiphonTheme {
                    SiphonScaffold(dest, {}, activeCount = 3, snackbar = SnackbarHostState(), content = content)
                }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    private fun download(name: String, state: DownloadUiState, items: List<app.siphon.data.DownloadItem> = Fixtures.queue) =
        shoot(name, Dest.Download) { width ->
            DownloadScreen(state, engine, NoActions, twoPane = width >= TWO_PANE_MIN) { m -> QueuePane(items, queueCb, m) }
        }

    private val loaded = DownloadUiState(
        url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
        fetch = FetchState.Loaded(Fixtures.bunny),
        options = DownloadOptions(preset = Preset.P1080),
    )

    // ---- tablet landscape, light ----
    @Config(qualifiers = "w1412dp-h904dp-land-hdpi") @Test fun tabletWelcome() = download("tablet_welcome", DownloadUiState(), emptyList())
    @Config(qualifiers = "w1412dp-h904dp-land-hdpi") @Test fun tabletLoaded() = download("tablet_loaded", loaded)
    @Config(qualifiers = "w1412dp-h904dp-land-hdpi") @Test fun tabletLibrary() =
        shoot("tablet_library", Dest.Library) { width -> LibraryScreen(Fixtures.queue, width >= 720.dp, libraryCb) }
    @Config(qualifiers = "w1412dp-h904dp-land-hdpi") @Test fun tabletSettings() =
        shoot("tablet_settings", Dest.Settings) { _ -> SettingsScreen(AppSettings(), engine, false, {}, {}) }
    @Config(qualifiers = "w1412dp-h904dp-land-hdpi") @Test fun tabletError() = download(
        "tablet_error",
        DownloadUiState(url = "https://www.youtube.com/watch?v=private", fetch = FetchState.Error("Private video. Sign in if you've been granted access.")),
    )

    // ---- tablet landscape, dark ----
    @Config(qualifiers = "w1412dp-h904dp-land-night-hdpi") @Test fun tabletLoadedDark() = download("tablet_loaded_dark", loaded)
    @Config(qualifiers = "w1412dp-h904dp-land-night-hdpi") @Test fun tabletWelcomeDark() = download("tablet_welcome_dark", DownloadUiState())
    @Config(qualifiers = "w1412dp-h904dp-land-night-hdpi") @Test fun tabletLibraryDark() =
        shoot("tablet_library_dark", Dest.Library) { width -> LibraryScreen(Fixtures.queue, width >= 720.dp, libraryCb) }

    // ---- tablet portrait ----
    @Config(qualifiers = "w904dp-h1412dp-port-hdpi") @Test fun portraitLoaded() = download("portrait_loaded", loaded)
    @Config(qualifiers = "w904dp-h1412dp-port-hdpi") @Test fun portraitWelcome() = download("portrait_welcome", DownloadUiState())

    // ---- phone ----
    @Config(qualifiers = "w400dp-h860dp-port-xhdpi") @Test fun phoneLoaded() = download("phone_loaded", loaded)
    @Config(qualifiers = "w400dp-h860dp-port-night-xhdpi") @Test fun phoneLibraryDark() =
        shoot("phone_library_dark", Dest.Library) { width -> LibraryScreen(Fixtures.queue, width >= 720.dp, libraryCb) }
}
