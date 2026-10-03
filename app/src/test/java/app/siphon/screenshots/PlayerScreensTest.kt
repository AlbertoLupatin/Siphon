package app.siphon.screenshots

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import app.siphon.data.Chapter
import app.siphon.player.OutputOption
import app.siphon.player.Panel
import app.siphon.player.PlayerActions
import app.siphon.player.PlayerScreen
import app.siphon.player.PlayerUiState
import app.siphon.player.TrackOption
import app.siphon.ui.theme.SiphonTheme
import coil3.BitmapImage
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class PlayerScreensTest {
    @get:Rule val compose = createComposeRule()

    private object NoActions : PlayerActions {
        override fun playPause() {}
        override fun seekTo(ms: Long) {}
        override fun seekBy(deltaMs: Long) {}
        override fun previous() {}
        override fun next() {}
        override fun setSpeed(speed: Float) {}
        override fun selectText(id: String?) {}
        override fun selectAudio(id: String) {}
        override fun toggleZoom() {}
        override fun enterPip() {}
        override fun close() {}
        override fun openSystemOutputSwitcher() {}
        override fun selectOutput(id: String) {}
        override fun setOutputVolume(v: Float) {}
        override fun searchOutputs(active: Boolean) {}
        override fun openCaptionSettings() {}
    }

    private val video = PlayerUiState(
        title = "Big Buck Bunny 60fps 4K – Official Blender Foundation Short Film",
        subtitle = "Blender",
        artworkUrl = "thumb://meadow",
        isPlaying = true,
        positionMs = 245_000, durationMs = 635_000, bufferedMs = 360_000,
        chapters = listOf(
            Chapter(0.0, 90.0, "Opening"), Chapter(90.0, 190.0, "The bunny wakes up"),
            Chapter(190.0, 340.0, "Butterflies"), Chapter(340.0, 475.0, "The ambush"),
            Chapter(475.0, 580.0, "Revenge"), Chapter(580.0, 635.0, "Credits"),
        ),
        textTracks = listOf(
            TrackOption("2:0", "English", null, true),
            TrackOption("2:1", "Italiano", null, false),
            TrackOption("2:2", "English (auto-generated)", null, false),
        ),
        audioTracks = listOf(TrackOption("1:0", "English", "Stereo", true)),
        outputs = listOf(
            OutputOption("a@Living Room", "Living Room", "HomePod", true),
            OutputOption("b@Kitchen", "Kitchen", "HomePod mini", false),
        ),
        outputNote = "Audio plays on Living Room about 2 seconds after the picture.",
    )

    @OptIn(ExperimentalCoilApi::class)
    private fun shoot(name: String, state: PlayerUiState, panel: Panel? = null) {
        compose.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { r -> Fixtures.image(r.data.toString()) ?: Fixtures.image("thumb://studio")!! },
            ) {
                SiphonTheme(dark = true) {
                    PlayerScreen(
                        state, NoActions, inPip = false, initialPanel = panel,
                        video = { m ->
                            Box(m, contentAlignment = Alignment.Center) {
                                val bmp = (Fixtures.image("thumb://meadow") as BitmapImage).bitmap.asImageBitmap()
                                Image(bmp, null, Modifier.aspectRatio(16f / 9f), contentScale = ContentScale.Fit)
                            }
                        },
                        subtitles = { m ->
                            Box(m, contentAlignment = Alignment.BottomCenter) {
                                Text(
                                    "The rabbit had no idea what the morning would bring.",
                                    style = TextStyle(color = Color.White, fontSize = 22.sp, shadow = Shadow(Color.Black, blurRadius = 6f)),
                                    modifier = Modifier.padding(8.dp()),
                                )
                            }
                        },
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())

    @Config(qualifiers = "w1412dp-h904dp-land-hdpi") @Test fun playerVideo() = shoot("player_video", video)
    @Config(qualifiers = "w1412dp-h904dp-land-hdpi") @Test fun playerChapters() = shoot("player_chapters", video, Panel.Chapters)
    @Config(qualifiers = "w1412dp-h904dp-land-hdpi") @Test fun playerOutput() = shoot("player_output", video, Panel.Output)
    @Config(qualifiers = "w1412dp-h904dp-land-hdpi") @Test fun playerAudio() = shoot(
        "player_audio",
        video.copy(isAudio = true, title = "Late night jazz session, live from the studio", subtitle = "Blue Room", artworkUrl = "thumb://studio", chapters = emptyList(), textTracks = emptyList()),
    )
    @Config(qualifiers = "w400dp-h860dp-port-xhdpi") @Test fun playerPhone() = shoot("player_phone", video)
}
