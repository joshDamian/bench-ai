package ai.bench.app.ui.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactsTest {
    private fun file(kind: String, mime: String, open: Boolean) =
        FileInfo("x", "/tmp/x", "x", mime, 1, open, kind, artifact = true)

    @Test fun audioAndVideoAreViewableEvenThoughServedAsDownloads() {
        // The server sends media as a download (open = false); the viewer plays it anyway.
        val video = file("media", "video/mp4", open = false)
        val audio = file("media", "audio/mpeg", open = false)
        assertTrue(video.viewable)
        assertTrue(audio.viewable)
        assertFalse(video.audio)
        assertTrue(audio.audio)
    }

    @Test fun theOtherKindsStillNeedTheServersPermission() {
        assertTrue(file("image", "image/png", open = true).viewable)
        assertTrue(file("pdf", "application/pdf", open = true).viewable)
        assertTrue(file("text", "text/plain", open = true).viewable)
        assertFalse(file("image", "image/png", open = false).viewable)
        assertFalse(file("archive", "application/zip", open = false).viewable)
    }
}
