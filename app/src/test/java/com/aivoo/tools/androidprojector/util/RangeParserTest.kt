package app.aivoo.tools.androidprojector.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeParserTest {
    @Test
    fun noRange() {
        assertEquals(RangeParser.Result.NotRange, RangeParser.parse(null, 100))
        assertEquals(RangeParser.Result.NotRange, RangeParser.parse("items=0-1", 100))
    }

    @Test
    fun normalRange() {
        assertEquals(RangeParser.Result.Partial(0, 99), RangeParser.parse("bytes=0-", 100))
        assertEquals(RangeParser.Result.Partial(10, 19), RangeParser.parse("bytes=10-19", 100))
        assertEquals(RangeParser.Result.Partial(10, 99), RangeParser.parse("bytes=10-", 100))
        assertEquals(RangeParser.Result.Partial(10, 99), RangeParser.parse("bytes=10-500", 100))
    }

    @Test
    fun suffixRange() {
        assertEquals(RangeParser.Result.Partial(90, 99), RangeParser.parse("bytes=-10", 100))
        assertEquals(RangeParser.Result.Partial(0, 99), RangeParser.parse("bytes=-500", 100))
    }

    @Test
    fun invalidRanges() {
        assertTrue(RangeParser.parse("bytes=100-", 100) is RangeParser.Result.Unsatisfiable)
        assertTrue(RangeParser.parse("bytes=50-10", 100) is RangeParser.Result.Unsatisfiable)
        assertTrue(RangeParser.parse("bytes=-0", 100) is RangeParser.Result.Unsatisfiable)
        assertTrue(RangeParser.parse("bytes=0-1,2-3", 100) is RangeParser.Result.Unsatisfiable)
        assertTrue(RangeParser.parse("bytes=abc-", 100) is RangeParser.Result.Unsatisfiable)
        assertTrue(RangeParser.parse("bytes=0-abc", 100) is RangeParser.Result.Unsatisfiable)
        assertTrue(RangeParser.parse("bytes=0-1-2", 100) is RangeParser.Result.Unsatisfiable)
        assertTrue(RangeParser.parse("bytes=0-", 0) is RangeParser.Result.Unsatisfiable)
    }
}