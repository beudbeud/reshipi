package com.beudbeud.fuji

import com.beudbeud.fuji.data.FujiExif
import com.beudbeud.fuji.model.DynamicRange
import com.beudbeud.fuji.model.FilmSimulation
import com.beudbeud.fuji.model.Generation
import com.beudbeud.fuji.model.GrainSize
import com.beudbeud.fuji.model.Strength
import com.beudbeud.fuji.model.WhiteBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixture: unedited X-T30 III JPEG (truncated to its EXIF-bearing head). */
class FujiExifTest {

    private val recipe = FujiExif.parse(
        javaClass.getResourceAsStream("/fuji-xt30iii.jpg")!!.readBytes()
    )!!

    @Test
    fun parsesXt30iiiJpeg() {
        // Ground truth from exiftool on the same file
        assertEquals("X-T30 III", recipe.cameraModel)
        assertEquals(Generation.X_TRANS_V, recipe.generation)
        assertEquals(FilmSimulation.CLASSIC_CHROME, recipe.filmSimulation)
        assertEquals(WhiteBalance.KELVIN, recipe.whiteBalance)
        assertEquals(6600, recipe.kelvin)
        assertEquals(-1, recipe.wbShiftRed)
        assertEquals(-3, recipe.wbShiftBlue)
        assertEquals(DynamicRange.DR400, recipe.dynamicRange)
        assertEquals(-2.0, recipe.highlight, 0.0)
        assertEquals(-0.5, recipe.shadow, 0.0)
        assertEquals(3, recipe.color)
        assertEquals(-2, recipe.sharpness)
        assertEquals(-4, recipe.noiseReduction)
        assertEquals(Strength.STRONG, recipe.grainEffect)
        assertEquals(GrainSize.LARGE, recipe.grainSize)
        assertEquals(Strength.STRONG, recipe.colorChromeEffect)
        assertEquals(Strength.OFF, recipe.colorChromeFxBlue)
        assertEquals(3, recipe.clarity)
        assertEquals("1000", recipe.iso)
        assertEquals("-1", recipe.exposureCompensation)
    }

    @Test
    fun rejectsNonFujiData() {
        assertNull(FujiExif.parse(ByteArray(100)))
        assertNull(FujiExif.parse(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())))
    }
}
