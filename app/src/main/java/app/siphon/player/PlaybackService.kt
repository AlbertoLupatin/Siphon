package app.siphon.player

import android.app.PendingIntent
import android.content.Context
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import app.siphon.SiphonApp
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns the one ExoPlayer instance. The media session gives lock-screen and notification controls,
 * Bluetooth/AirPods buttons (squeeze to play/pause, double squeeze to skip) and the system output
 * switcher; audio focus and "becoming noisy" (headphones removed) are handled by ExoPlayer.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val outputs = SiphonApp.get(this).outputs
        // Same processors as the default chain, plus a tee at the end that feeds AirPlay.
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessorChain(TeeChain(TeeAudioProcessor(outputs.tap)))
                    .build()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(SEEK_MS)
            .setSeekForwardIncrementMs(SEEK_MS)
            .build()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
        outputs.bindPlayer(player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away stops playback unless something is actually playing (e.g. music in the background). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        SiphonApp.get(this).outputs.unbindPlayer()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Mirrors DefaultAudioSink's default chain (silence skipping, then speed) with [tee] appended. */
    private class TeeChain(private val tee: TeeAudioProcessor) : AudioProcessorChain {
        private val silence = SilenceSkippingAudioProcessor()
        private val sonic = SonicAudioProcessor()
        private val processors = arrayOf<AudioProcessor>(silence, sonic, tee)
        override fun getAudioProcessors() = processors
        override fun applyPlaybackParameters(p: PlaybackParameters): PlaybackParameters {
            sonic.setSpeed(p.speed); sonic.setPitch(p.pitch); return p
        }
        override fun applySkipSilenceEnabled(enabled: Boolean): Boolean { silence.setEnabled(enabled); return enabled }
        override fun getMediaDuration(playoutDuration: Long) =
            if (sonic.isActive) sonic.getMediaDuration(playoutDuration) else playoutDuration
        override fun getSkippedOutputFrameCount() = silence.skippedFrames
    }

    companion object {
        const val SEEK_MS = 10_000L
    }
}
