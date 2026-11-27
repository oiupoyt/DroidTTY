package space.oiupoyt.droidtty.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {

    private val ok = """{"versionCode":15,"versionName":"0.6.0","apk":"https://github.com/oiupoyt/DroidTTY/releases/download/latest/DroidTTY.apk","notes":"new things"}"""

    @Test
    fun parsesTheReleasesFile() {
        val r = Release.parse(ok)!!
        assertEquals(15, r.versionCode)
        assertEquals("0.6.0", r.versionName)
        assertEquals("new things", r.notes)
    }

    @Test
    fun refusesApksFromAnywhereElse() {
        assertNull(Release.parse(ok.replace("github.com/oiupoyt/DroidTTY", "evil.example/x")))
        assertNull(Release.parse(ok.replace("DroidTTY.apk", "DroidTTY.zip")))
        assertNull(Release.parse("not json"))
        assertNull(Release.parse("""{"versionCode":"x"}"""))
    }

    @Test
    fun onlyNewerVersionsAndEachOnce() {
        val r = Release.parse(ok)!!
        assertTrue(Updates.isNewer(r, 14))
        assertFalse(Updates.isNewer(r, 15))
        assertTrue(Updates.shouldNotify(r, installed = 14, alreadyNotified = 0))
        assertFalse(Updates.shouldNotify(r, installed = 14, alreadyNotified = 15))
        assertFalse(Updates.shouldNotify(r, installed = 15, alreadyNotified = 0))
    }

    @Test
    fun checksDailyAndRetriesSooner() {
        val day = Updates.INTERVAL_MS
        assertFalse(Updates.due(now = day - 1, lastCheck = 0, lastOk = true))
        assertTrue(Updates.due(now = day, lastCheck = 0, lastOk = true))
        assertTrue(Updates.due(now = Updates.RETRY_MS, lastCheck = 0, lastOk = false))
        assertTrue("never checked", Updates.due(now = System.currentTimeMillis(), lastCheck = 0, lastOk = true))
    }
}

class StoreUpdateTest {

    @Test
    fun playsReleaseHasNoApkAndStillLoadsFromStorage() {
        // Google Play only says which version code is out.
        val play = Release(21, "", "", "")
        assertEquals(play, Release.parse(play.toJson(), requireApk = false))
        // From the network an APK link is required, and must be the project's own.
        assertNull(Release.parse(play.toJson()))
    }

    @Test
    fun titleNamesTheVersionOnlyWhenKnown() {
        assertEquals("DroidTTY 0.6.0 is out", Updates.title(Release(21, "0.6.0", "", "")))
        assertEquals("a DroidTTY update is out", Updates.title(Release(21, "", "", "")))
    }

    @Test
    fun onlyTheProjectsOwnApksAreTrusted() {
        assertTrue(Release.trusted("https://github.com/oiupoyt/DroidTTY/releases/download/v0.6.0/DroidTTY.apk"))
        assertTrue(Release.trusted("https://git.oiupoyt.space/oiupoyt/DroidTTY/releases/download/latest/DroidTTY.apk"))
        assertFalse(Release.trusted("https://github.com/oiupoyt/DroidTTY/releases/download/v0.6.0/DroidTTY-play.aab"))
        assertFalse(Release.trusted("https://example.com/DroidTTY.apk"))
        assertFalse(Release.trusted(""))
    }

    @Test
    fun fdroidFollowsTaggedReleasesAndSideloadFollowsMain() {
        assertTrue(Updates.TAGGED_VERSION_URL.contains("/releases/latest/download/"))
        assertTrue(Updates.VERSION_URL.contains("/releases/download/latest/"))
    }
}
