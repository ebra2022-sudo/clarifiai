package com.clarifiai.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayLinksTest {
    private val replay = "https://clarity.microsoft.com/player/proj/user/session"

    @Test fun readsCitationTimes() {
        assertEquals(251, ReplayLinks.seconds("S3 at 04:11"))
        assertEquals(2561, ReplayLinks.seconds("S11 at 42:41"))
        assertNull(ReplayLinks.seconds("S5"))
    }

    @Test fun startsTheReplayJustBeforeTheCitedMomentInMilliseconds() {
        val expected = "$replay?t=243000" // 04:11 minus the 8 s lead-in
        assertEquals(expected, ReplayLinks.at(replay, 251))
        assertEquals("[S3 at 04:11]($expected)", ReplayLinks.deepLink("[S3 at 04:11]($replay)"))
        assertEquals(replay, ReplayLinks.at(replay, null))
        assertEquals("$replay?t=0", ReplayLinks.at(replay, 3)) // never before the session starts
    }

    @Test fun replacesAnExistingStartTime() {
        assertEquals("$replay?t=12000", ReplayLinks.at("$replay?t=99000", 20))
        assertEquals("$replay?x=1&t=12000", ReplayLinks.at("$replay?t=99000&x=1", 20))
    }

    @Test fun leavesOtherLinksAlone() {
        val md = "[Microsoft's guide](https://learn.microsoft.com/x) and [S2]($replay)"
        assertEquals(md, ReplayLinks.deepLink(md))
    }

    @Test fun cleansOldPlaceholders() {
        assertEquals("dead tap on an unlabelled element, then an icon", cleanPlaceholders("dead tap on '[unlabelled]', then [icon]"))
    }
}
