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

    @Test fun addsTheTimeOnlyWhenTheFormatIsKnown() {
        val expected = ReplayLinks.TIME_PARAM?.let { "$replay?$it=251" } ?: replay
        assertEquals(expected, ReplayLinks.at(replay, 251))
        assertEquals("[S3 at 04:11]($expected)", ReplayLinks.deepLink("[S3 at 04:11]($replay)"))
    }

    @Test fun leavesOtherLinksAlone() {
        val md = "[Microsoft's guide](https://learn.microsoft.com/x) and [S2]($replay)"
        assertEquals(md, ReplayLinks.deepLink(md))
    }

    @Test fun cleansOldPlaceholders() {
        assertEquals("dead tap on an unlabelled element, then an icon", cleanPlaceholders("dead tap on '[unlabelled]', then [icon]"))
    }
}
