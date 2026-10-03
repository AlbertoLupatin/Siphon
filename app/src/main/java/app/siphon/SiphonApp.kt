package app.siphon

import android.app.Application
import android.content.Context
import android.util.Log
import app.siphon.airplay.OutputManager
import app.siphon.data.DownloadRepository
import app.siphon.data.SettingsRepository
import app.siphon.engine.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SiphonApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settings: SettingsRepository private set
    lateinit var downloads: DownloadRepository private set
    lateinit var outputs: OutputManager private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        downloads = DownloadRepository(this, settings, appScope)
        outputs = OutputManager(this)
        appScope.launch {
            Engine.init(this@SiphonApp)
            autoUpdate()
        }
    }

    /** YouTube & co. break often; keep yt-dlp fresh at most once a day. */
    private suspend fun autoUpdate() {
        val s = settings.current()
        val day = 24 * 60 * 60 * 1000L
        if (!s.autoUpdate || System.currentTimeMillis() - s.lastUpdateCheck < day) return
        runCatching { Engine.update(this, s.nightly) }
            .onSuccess { settings.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) } }
            .onFailure { Log.w("Siphon", "yt-dlp auto-update failed", it) }
    }

    companion object {
        fun get(context: Context) = context.applicationContext as SiphonApp
    }
}
