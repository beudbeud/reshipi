package com.beudbeud.fuji

import com.beudbeud.fuji.data.ptp.packPtpString
import com.beudbeud.fuji.data.ptp.parsePtpString
import com.beudbeud.fuji.data.ptp.patchProfile
import com.beudbeud.fuji.data.ptp.toPresetProps
import com.beudbeud.fuji.model.DynamicRange
import com.beudbeud.fuji.model.FilmSimulation
import com.beudbeud.fuji.model.GrainSize
import com.beudbeud.fuji.model.Recipe
import com.beudbeud.fuji.model.Strength
import com.beudbeud.fuji.model.WhiteBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RecipePresetTest {
    private fun List<Pair<Int, ByteArray>>.value(id: Int): Int? =
        find { it.first == id }?.second?.let {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        }

    @Test
    fun encodings() {
        val props = Recipe(
            name = "Test",
            filmSimulation = FilmSimulation.CLASSIC_CHROME,
            dynamicRange = DynamicRange.DR200,
            highlight = 1.5,
            shadow = -0.5,
            color = 2,
            noiseReduction = -4,
            grainEffect = Strength.STRONG,
            grainSize = GrainSize.LARGE,
            colorChromeEffect = Strength.WEAK,
            clarity = -3,
        ).toPresetProps()

        assertEquals(0x0B, props.value(0xD192))          // Classic Chrome
        assertEquals(200, props.value(0xD190))           // DR raw percentage
        assertEquals(15, props.value(0xD19D))            // highlight ×10
        assertEquals(-5, props.value(0xD19E))            // shadow ×10
        assertEquals(20, props.value(0xD19F))            // color ×10
        assertEquals(0x8000.toShort().toInt(), props.value(0xD1A1)) // NR -4 proprietary
        assertEquals(5, props.value(0xD195))             // grain strong large
        assertEquals(2, props.value(0xD196))             // CCE weak → 1-indexed
        assertEquals(-30, props.value(0xD1A2))           // clarity ×10
    }

    @Test
    fun kelvinFollowsWbAndMonoSkipsColor() {
        val kelvin = Recipe(name = "K", whiteBalance = WhiteBalance.KELVIN, kelvin = 5600).toPresetProps()
        val ids = kelvin.map { it.first }
        assertEquals(ids.indexOf(0xD199) + 1, ids.indexOf(0xD19C)) // Kelvin right after WB
        assertEquals(5600, kelvin.value(0xD19C))

        val mono = Recipe(name = "M", filmSimulation = FilmSimulation.ACROS).toPresetProps()
        assertFalse(mono.any { it.first == 0xD19F })     // Color rejected for B&W sims
        assertTrue(mono.none { it.first == 0xD19C })     // no Kelvin when WB is not Kelvin

        val auto = Recipe(name = "A").toPresetProps()
        assertEquals(100, auto.value(0xD190))            // DR Auto → 100
    }

    @Test
    fun patchProfileWritesNativeFields() {
        val numParams = 28
        val base = ByteArray(2 + numParams * 4)
        base[0] = numParams.toByte() // u16 LE
        val out = Recipe(
            name = "X",
            filmSimulation = FilmSimulation.CLASSIC_CHROME,
            dynamicRange = DynamicRange.DR400,
            whiteBalance = WhiteBalance.DAYLIGHT,
            wbShiftRed = 2,
            wbShiftBlue = -5,
            highlight = 1.5,
            shadow = -0.5,
            color = 2,
            sharpness = -1,
            noiseReduction = 0,
            grainEffect = Strength.WEAK,
            grainSize = GrainSize.SMALL,
            colorChromeEffect = Strength.STRONG,
            clarity = 4,
        ).patchProfile(base)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        fun f(idx: Int) = bb.getInt(2 + idx * 4)

        assertEquals(0x0B, f(8))      // Classic Chrome
        assertEquals(400, f(6))       // DR raw %
        assertEquals(0x0004, f(12))   // WB Daylight
        assertEquals(2, f(13))        // shift R
        assertEquals(-5, f(14))       // shift B
        assertEquals(15, f(16))       // highlight ×10
        assertEquals(-5, f(17))       // shadow ×10
        assertEquals(20, f(18))       // color ×10
        assertEquals(-10, f(19))      // sharpness ×10
        assertEquals(0x2000, f(20))   // NR 0 proprietary
        assertEquals(2, f(9))         // grain weak small flat enum
        assertEquals(3, f(10))        // CCE strong 1-indexed
        assertEquals(40, f(27))       // clarity ×10
    }

    @Test
    fun ptpStringRoundTrip() {
        assertEquals("Kodachrome 64", parsePtpString(packPtpString("Kodachrome 64")))
        assertEquals("", parsePtpString(packPtpString("")))
    }
}
