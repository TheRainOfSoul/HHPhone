package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HHUpdateTest {
    @Test fun parseLatest() {
        assertEquals(HHLatest(4, "0.3.1", "Исправления"), HHUpdate.parse("""{"versionCode":4,"versionName":"0.3.1","notes":"Исправления"}"""))
        assertEquals(HHLatest(4, "0.3.1", ""), HHUpdate.parse("""{"versionCode":4,"versionName":"0.3.1","extra":1}"""))
    }

    @Test fun parseGarbage() {
        assertNull(HHUpdate.parse("<html>Not Found</html>"))
        assertNull(HHUpdate.parse("""{"versionCode":0,"versionName":"0.3.1"}"""))
        assertNull(HHUpdate.parse("""{"versionCode":4,"versionName":""}"""))
        assertNull(HHUpdate.parse(""))
    }

    @Test fun newerOnlyByVersionCode() {
        assertTrue(HHLatest(4, "0.3.1", "").newerThan(3))
        assertFalse(HHLatest(3, "0.3.0", "").newerThan(3))
        assertFalse(HHLatest(2, "9.9.9", "").newerThan(3))
    }

    @Test fun sixHoursBetweenChecks() {
        val h = 3_600_000L
        assertTrue(HHUpdate.due(0, 6 * h))
        assertFalse(HHUpdate.due(10 * h, 15 * h))
        assertTrue(HHUpdate.due(10 * h, 16 * h))
        assertTrue(HHUpdate.due(20 * h, 10 * h))   // часы перевели назад — проверяем
    }
}
