package com.example.eqify

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRulesTest {
    private fun v(s: String) = AppVersion.parse(s)!!

    @Test fun parsesCommonTags() {
        assertEquals(AppVersion(0, 2, 0, listOf("beta")), v("v0.2.0-beta"))
        assertEquals(AppVersion(1, 0, 0), v("1.0"))
        assertEquals(AppVersion(1, 2, 3, listOf("rc", "1")), v("V1.2.3-rc.1+build.7"))
    }

    @Test fun rejectsBadTags() {
        listOf(null, "", "latest", "1", "v1.x.0", "1.2.3-", "1.2.3-beta..1").forEach {
            assertNull(it, AppVersion.parse(it))
        }
    }

    @Test fun ordersBySemverPrecedence() {
        val ordered = listOf("0.1.0-beta", "0.1.1-beta", "0.2.0-alpha", "0.2.0-beta", "0.2.0-beta.2",
            "0.2.0-beta.10", "0.2.0-rc.1", "0.2.0", "0.10.0", "1.0.0").map(::v)
        assertEquals(ordered, ordered.shuffled(java.util.Random(4)).sorted())
        assertEquals(0, v("1.0").compareTo(v("1.0.0")))
    }

    @Test fun newestSkipsDraftsAndBadTagsAndIncludesPreReleases() {
        val json = JsonParser().parse("""[
            {"tag_name":"v0.3.0-beta","draft":true,"html_url":"https://github.com/o/r/releases/tag/v0.3.0-beta"},
            {"tag_name":"nightly","draft":false,"html_url":"https://github.com/o/r/releases/tag/nightly"},
            {"tag_name":"v0.2.0-beta","draft":false,"prerelease":true,"html_url":"https://github.com/o/r/releases/tag/v0.2.0-beta"},
            {"tag_name":"v0.1.1-beta","draft":false,"html_url":"https://github.com/o/r/releases/tag/v0.1.1-beta"},
            {"tag_name":"v9.9.9","draft":false,"html_url":"https://evil.example/download"},
            "garbage"
        ]""").asJsonArray
        val newest = UpdateRules.newest(json)!!
        assertEquals("v0.2.0-beta", newest.tag)
        assertEquals("https://github.com/o/r/releases/tag/v0.2.0-beta", newest.url)
    }

    @Test fun emptyListHasNoRelease() {
        assertNull(UpdateRules.newest(JsonParser().parse("[]").asJsonArray))
    }

    @Test fun onlyStrictlyNewerReleasesCount() {
        val release = ReleaseInfo("v0.2.0-beta", v("0.2.0-beta"), "https://github.com/x")
        assertTrue(UpdateRules.isNewer(release, v("0.1.1-beta")))
        assertFalse(UpdateRules.isNewer(release, v("0.2.0-beta")))
        assertFalse(UpdateRules.isNewer(release, v("0.2.0")))   // dev build ahead of the release
        assertFalse(UpdateRules.isNewer(null, v("0.1.0")))
        assertFalse(UpdateRules.isNewer(release, null))
    }

    @Test fun checksAtMostOnceADay() {
        val day = UpdateRules.CHECK_INTERVAL_MS
        assertTrue(UpdateRules.isDue(1_000L, 0L))                 // never checked
        assertFalse(UpdateRules.isDue(10 * day - 1, 9 * day))     // within a day
        assertTrue(UpdateRules.isDue(10 * day, 9 * day))          // a day later
        assertTrue(UpdateRules.isDue(5 * day, 9 * day))           // clock moved backwards
    }
}
