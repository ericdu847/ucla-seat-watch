package com.ericdu.seatwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SocLiveTest {
    @Test fun catalogPadding() {
        mapOf("7B" to "0007B", "5HA" to "0005HA", "13" to "0013", "M51A" to "0051A M", "C122" to "0122  C", "0007B" to "0007B")
            .forEach { (i, o) -> assertEquals(o, Soc.normCatlg(i)) }
    }

    @Test fun liveCourses() {
        if (System.getenv("LIVE") == null) return
        val (subs, terms) = Soc.subjectsAndTerms()
        println("subjects=${subs.size} terms=$terms")
        assertTrue(subs.any { it.second == "LIFESCI" })
        for ((s, c, w) in listOf(Triple("LIFESCI", "7B", false), Triple("BMD RES", "5HA", true), Triple("STATS", "13", false))) {
            val st = Soc.course("27W", s, c)
            println("== $s $c: ${st.title}\n" + Soc.summarize(st, w))
            assertTrue(st.primaries.isNotEmpty())
        }
    }
}
