package com.beudbeud.fuji

import com.beudbeud.fuji.data.CubeLut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeLutTest {
    /** Feed the whole cube through an identity mapping. */
    private fun saturated(size: Int, transform: (Int) -> Int = { it }): CubeLut {
        val lut = CubeLut(size)
        // Scale so the top index lands exactly on 255, not on a truncated step
        fun level(i: Int) = i * 255 / (size - 1)
        for (r in 0 until size) for (g in 0 until size) for (b in 0 until size) {
            val sr = level(r); val sg = level(g); val sb = level(b)
            lut.accumulate(sr, sg, sb, transform(sr), transform(sg), transform(sb))
        }
        return lut
    }

    @Test
    fun identitySamplesRoundTrip() {
        val lut = saturated(9)
        assertEquals(1f, lut.coverage(), 0.001f)
        val grid = lut.build()
        // Corner cells: black stays black, white stays white
        assertEquals(0f, grid[0], 0.01f)
        assertEquals(1f, grid[grid.size - 1], 0.01f)
    }

    @Test
    fun aMeasuredTransformIsReproduced() {
        // Everything halved in brightness
        val grid = saturated(9) { it / 2 }.build()
        val size = 9
        // Pure white in must come out mid grey
        val white = ((size - 1) * size + (size - 1)) * size + (size - 1)
        assertEquals(0.5f, grid[white * 3], 0.02f)
    }

    @Test
    fun unseenColoursFallBackToIdentityRatherThanNonsense() {
        // A single grey sample: nothing else in the cube was ever observed.
        val lut = CubeLut(9)
        lut.accumulate(128, 128, 128, 128, 128, 128)
        assertTrue("coverage should be tiny", lut.coverage() < 0.02f)
        val grid = lut.build()
        // Every entry stays inside the valid range — no runaway extrapolation
        assertTrue(grid.all { it in 0f..1f })
        // Far corners are untouched by the fill, so they pass colour through
        assertEquals(0f, grid[0], 0.001f)
        assertEquals(0f, grid[1], 0.001f)
        assertEquals(0f, grid[2], 0.001f)
    }

    /**
     * Two samples that snap to the same grid node but sit at different places
     * inside it, with opposite destinations. Rounding each to its nearest node
     * would lump them into one cell and average them to mid grey, leaving the
     * next node unmeasured; spreading them by distance has to keep the one
     * nearer the upper node pulling that node up.
     */
    @Test
    fun subCellPositionSurvivesInsteadOfBeingAveragedAway() {
        val size = 9
        val lut = CubeLut(size)
        // Both quantize to node 1 (nodes sit every 255/8 = 31.9 levels)
        lut.accumulate(34, 34, 34, 0, 0, 0)
        lut.accumulate(46, 46, 46, 255, 255, 255)
        val grid = lut.build()
        fun node(k: Int) = grid[(((k * size) + k) * size + k) * 3]

        // Nearest-node binning would put both here and answer 0.5
        assertTrue("node 1 should lean dark, was ${node(1)}", node(1) < 0.35f)
        // ...and would leave node 2 to be guessed by the dilation
        assertTrue("node 2 should lean bright, was ${node(2)}", node(2) > 0.65f)
        assertTrue(grid.all { it in 0f..1f })
    }

    /**
     * Two measured greys far apart, everything between them invented. A fill that
     * freezes each cell on whichever front reaches it first makes the two regions
     * meet in a cliff; a real export came back with a grey ramp that dipped and
     * then jumped by a third of its range between adjacent nodes.
     */
    @Test
    fun inventedCellsAreSmoothedInsteadOfMeetingInACliff() {
        val size = 9
        val lut = CubeLut(size)
        lut.accumulate(64, 64, 64, 51, 51, 51)
        lut.accumulate(192, 192, 192, 204, 204, 204)
        val grid = lut.build()
        val ramp = (0 until size).map { grid[(((it * size) + it) * size + it) * 3] }

        // Between the two measurements — nodes 2 and 6 — the fill is an
        // interpolation and has to rise with the data. Beyond them it is an
        // extrapolation with nothing to go on, so nothing is promised there.
        ramp.subList(2, 7).zipWithNext().forEach { (a, b) ->
            assertTrue("the grey ramp dips between measurements: $ramp", b >= a - 0.01f)
        }
        // No pair of adjacent nodes may swallow a third of the range: that is the
        // cliff where two dilation fronts used to meet.
        val step = ramp.zipWithNext().maxOf { (a, b) -> b - a }
        assertTrue("one step swallows the range ($step): $ramp", step < 0.30f)
        assertTrue(grid.all { it in 0f..1f })
    }

    @Test
    fun cubeTextIsWellFormed() {
        val text = saturated(3).toCubeText("Kodachrome \"64\"")
        val lines = text.trim().lines()
        assertTrue(lines.any { it == "LUT_3D_SIZE 3" })
        // Quotes in the name must not break the TITLE line
        assertTrue(lines.any { it.startsWith("TITLE ") && it.count { c -> c == '"' } == 2 })
        // 27 data rows, each three floats, decimal points regardless of locale
        val data = lines.filter { it.matches(Regex("^[0-9].*")) }
        assertEquals(27, data.size)
        data.forEach { assertEquals(3, it.split(" ").size) }
    }

    /**
     * A chart brings far more colours than greys, and a least-squares fit answers
     * to whoever brought the most samples. One grey against forty neighbours that
     * want it left alone has to still be heard, or every recipe's greys come out
     * carrying the cast of whatever surrounds them on the chart.
     */
    @Test
    fun oneGreySampleOutvotesTheColoursAroundIt() {
        val lut = CubeLut(9)
        // The transform warms greys and leaves everything else untouched.
        lut.accumulate(128, 128, 128, 148, 128, 108)
        for (d in listOf(-30, -20, 20, 30)) {
            for (o in 0..9) {
                lut.accumulate(128 + d, 128, 128 - d + o, 128 + d, 128, 128 - d + o)
            }
        }
        val grid = lut.build()
        // Node 4 of 9 is the middle grey, the one the samples straddle.
        val at = ((4 * 9) + 4) * 9 + 4
        val r = grid[at * 3]
        val b = grid[at * 3 + 2]
        // The grey sample asks for ±20 of 255, so 0.157 is the whole of what it
        // said. Unweighted, its neighbours drag that down to 0.121.
        assertTrue("grey outvoted by its neighbours: R-B=${r - b}", r - b > 0.145f)
        // And it must not go the other way: the colours around it are data too.
        assertTrue("grey fitted at the colours' expense: R-B=${r - b}", r - b < 0.17f)
    }

    /** Notes say what the cube is a transform of; they must not become data rows. */
    @Test
    fun notesAreCommentedOut() {
        val text = saturated(3).toCubeText("x", listOf("INPUT: sRGB", "BASE: Provia at DR400"))
        val lines = text.trim().lines()
        assertTrue(lines.any { it == "# INPUT: sRGB" })
        assertTrue(lines.any { it == "# BASE: Provia at DR400" })
        assertEquals(27, lines.count { it.matches(Regex("^[0-9].*")) })
    }
}
