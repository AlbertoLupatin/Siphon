package app.siphon.engine

import app.siphon.data.Preset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityTest {
    private fun v(w: Int, h: Int, mb: Double? = null) =
        MediaFormat("$w", "mp4", h, 24.0, "avc1", "none", null, null, mb?.let { (it * 1048576).toLong() }, null, width = w)

    @Test fun gradesLikeYouTube() {
        assertEquals(1080, v(2048, 858).quality)  // 2.39:1 film
        assertEquals(720, v(1280, 536).quality)
        assertEquals(1080, v(1920, 1080).quality)
        assertEquals(1080, v(1440, 1080).quality) // 4:3
        assertEquals(1080, v(1080, 1920).quality) // portrait
        assertEquals(2160, v(3840, 1608).quality)
    }

    @Test fun widescreenFilmOffers1080() {
        val info = MediaInfo("x", "Spring", null, null, 464, "YouTube", null, false, emptyList(),
            listOf(v(2048, 858, 60.0), v(1280, 536, 30.0), v(854, 358, 15.0)))
        assertTrue(info.estimate(Preset.P1080, true).available)
        assertFalse(info.estimate(Preset.P1440, true).available)
        assertEquals("1080p", info.estimate(Preset.Best, true).resolution)
        assertEquals("720p", info.estimate(Preset.P720, true).resolution)
    }

    @Test fun sizeFallsBackToBitrateTimesDuration() {
        val json = """{"id":"x","title":"t","duration":100,"formats":[{"format_id":"1","ext":"mp4","vcodec":"avc1","acodec":"none","height":720,"width":1280,"tbr":1000}]}"""
        val f = MediaInfoParser.parse(json).formats.single()
        assertEquals(12_500_000L, f.sizeBytes) // 1000 kbps × 100 s / 8
    }

    @Test fun siteNames() {
        assertEquals("YouTube", MediaInfoParser.parse("""{"id":"x","title":"t","extractor_key":"Youtube"}""").extractor)
        assertEquals("example.com", MediaInfoParser.parse("""{"id":"x","title":"t","extractor_key":"Generic","webpage_url":"https://www.example.com/a"}""").extractor)
    }
}
