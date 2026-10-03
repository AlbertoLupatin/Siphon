package app.siphon.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    val defaultPreset: Preset = Preset.Best,
    val maxConcurrent: Int = 2,
    val compatibleMp4: Boolean = true,
    val embedMetadata: Boolean = true,
    val embedThumbnail: Boolean = true,
    val embedChapters: Boolean = true,
    val subtitles: Boolean = false,
    val subtitleLangs: String = "en.*,it.*",
    val sponsorBlock: Boolean = false,
    val concurrentFragments: Int = 4,
    val extraArgs: String = "",
    val autoUpdate: Boolean = true,
    val nightly: Boolean = false,
    val lastUpdateCheck: Long = 0,
    val materialYou: Boolean = false,
) {
    fun toOptions() = DownloadOptions(
        preset = defaultPreset,
        compatibleMp4 = compatibleMp4,
        embedMetadata = embedMetadata,
        embedThumbnail = embedThumbnail,
        embedChapters = embedChapters,
        subtitles = subtitles,
        subtitleLangs = subtitleLangs,
        sponsorBlock = sponsorBlock,
        concurrentFragments = concurrentFragments,
        extraArgs = extraArgs,
    )
}

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val preset = stringPreferencesKey("preset")
        val maxConcurrent = intPreferencesKey("max_concurrent")
        val compat = booleanPreferencesKey("compat_mp4")
        val meta = booleanPreferencesKey("embed_meta")
        val thumb = booleanPreferencesKey("embed_thumb")
        val chapters = booleanPreferencesKey("embed_chapters")
        val subs = booleanPreferencesKey("subs")
        val subLangs = stringPreferencesKey("sub_langs")
        val sponsor = booleanPreferencesKey("sponsorblock")
        val fragments = intPreferencesKey("fragments")
        val extra = stringPreferencesKey("extra_args")
        val autoUpdate = booleanPreferencesKey("auto_update")
        val nightly = booleanPreferencesKey("nightly")
        val lastCheck = longPreferencesKey("last_update_check")
        val materialYou = booleanPreferencesKey("material_you")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = transform(p.toSettings())
            p[K.preset] = s.defaultPreset.name
            p[K.maxConcurrent] = s.maxConcurrent
            p[K.compat] = s.compatibleMp4
            p[K.meta] = s.embedMetadata
            p[K.thumb] = s.embedThumbnail
            p[K.chapters] = s.embedChapters
            p[K.subs] = s.subtitles
            p[K.subLangs] = s.subtitleLangs
            p[K.sponsor] = s.sponsorBlock
            p[K.fragments] = s.concurrentFragments
            p[K.extra] = s.extraArgs
            p[K.autoUpdate] = s.autoUpdate
            p[K.nightly] = s.nightly
            p[K.lastCheck] = s.lastUpdateCheck
            p[K.materialYou] = s.materialYou
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            defaultPreset = this[K.preset]?.let { n -> Preset.entries.firstOrNull { it.name == n } }
                ?.takeIf { it != Preset.Custom } ?: d.defaultPreset,
            maxConcurrent = this[K.maxConcurrent] ?: d.maxConcurrent,
            compatibleMp4 = this[K.compat] ?: d.compatibleMp4,
            embedMetadata = this[K.meta] ?: d.embedMetadata,
            embedThumbnail = this[K.thumb] ?: d.embedThumbnail,
            embedChapters = this[K.chapters] ?: d.embedChapters,
            subtitles = this[K.subs] ?: d.subtitles,
            subtitleLangs = this[K.subLangs] ?: d.subtitleLangs,
            sponsorBlock = this[K.sponsor] ?: d.sponsorBlock,
            concurrentFragments = this[K.fragments] ?: d.concurrentFragments,
            extraArgs = this[K.extra] ?: d.extraArgs,
            autoUpdate = this[K.autoUpdate] ?: d.autoUpdate,
            nightly = this[K.nightly] ?: d.nightly,
            lastUpdateCheck = this[K.lastCheck] ?: d.lastUpdateCheck,
            materialYou = this[K.materialYou] ?: d.materialYou,
        )
    }
}
