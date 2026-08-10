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
}
