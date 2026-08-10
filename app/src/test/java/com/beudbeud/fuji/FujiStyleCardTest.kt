package com.beudbeud.fuji

import com.beudbeud.fuji.data.FujiStyleCard
import com.beudbeud.fuji.model.DynamicRange
import com.beudbeud.fuji.model.FilmSimulation
import com.beudbeud.fuji.model.GrainSize
import com.beudbeud.fuji.model.Strength
import com.beudbeud.fuji.model.WhiteBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FujiStyleCardTest {

    @Test
    fun parsesCineStillCard() {
        val r = FujiStyleCard.parse(
            """
            #CineStill 800T
            Film Simulation: CLASSIC CHROME
            Film Simulation: CLASSIC CHROME | White Balance: FLUORESCENT LIGHT-3 | Red:
            -6  |  Blue: -4 | Dynamic Range: DR400 | Grain Effect - Roughness: Strong | Grain
            Effect - Size: Large | Color Chrome Effect: Strong | Color FX Blue:Weak | Colour: 4 |
            Sharpness: -3 | Highlight: 0.0 | Shadow: 2.0 | High ISO NR: -4 | Clarity: -5 | ISO:
            Made with FUJISTYLE APP
            """.trimIndent()
        )!!
        assertEquals("CineStill 800T", r.name)
        assertEquals(FilmSimulation.CLASSIC_CHROME, r.filmSimulation)
        assertEquals(WhiteBalance.FLUORESCENT_3, r.whiteBalance)
        assertEquals(-6, r.wbShiftRed)
        assertEquals(-4, r.wbShiftBlue)
        assertEquals(DynamicRange.DR400, r.dynamicRange)
        assertEquals(Strength.STRONG, r.grainEffect)
        assertEquals(GrainSize.LARGE, r.grainSize)
        assertEquals(Strength.STRONG, r.colorChromeEffect)
        assertEquals(Strength.WEAK, r.colorChromeFxBlue)
        assertEquals(4, r.color)
        assertEquals(-3, r.sharpness)
        assertEquals(0.0, r.highlight, 0.0)
        assertEquals(2.0, r.shadow, 0.0)
        assertEquals(-4, r.noiseReduction)
        assertEquals(-5, r.clarity)
        assertEquals("Auto", r.iso)
        assertEquals(listOf("fujistyle"), r.tags)
    }

    @Test
    fun parsesMonochromeAndAcrosVariants() {
        val mono = FujiStyleCard.parse(
            "#Leica Monochrome\nFilm Simulation: MONOCHROME+Ye FILTER | White Balance: DAYLIGHT | " +
                "Red: 2 | Blue: -2 | Dynamic Range: DR200 | Grain Effect - Roughness: Weak | " +
                "Grain Effect - Size: Small | Sharpness: 4 | Clarity: 5 | ISO:"
        )!!
        assertEquals(FilmSimulation.MONOCHROME_YE, mono.filmSimulation)
        assertEquals(WhiteBalance.DAYLIGHT, mono.whiteBalance)
        assertEquals(GrainSize.SMALL, mono.grainSize)
        assertEquals(Strength.WEAK, mono.grainEffect)

        val acros = FujiStyleCard.parse(
            "Film Simulation: ACROS G | White Balance: AUTO | Dynamic Range: Auto | ISO: 80-12800"
        )!!
        assertEquals(FilmSimulation.ACROS_G, acros.filmSimulation)
        assertEquals(DynamicRange.AUTO, acros.dynamicRange)
        assertEquals("80-12800", acros.iso)
    }

    @Test
    fun parsesFujiXWeeklyStyleText() {
        val r = FujiStyleCard.parse(
            """
            Film Simulation: Classic Negative
            Grain Effect: Strong, Small
            Color Chrome Effect: Weak
            Color Chrome FX Blue: Strong
            White Balance: 5700K, +1 Red & +1 Blue
            Dynamic Range: DR400
            Highlight: +2.5
            Shadow: -2
            Color: +4
            Sharpness: -2
            High ISO NR: -4
            Clarity: -3
            ISO: Auto, up to ISO 6400
            Exposure Compensation: 0 to -2/3 (typically)
            """.trimIndent(),
            tag = "import",
        )!!
        assertEquals(FilmSimulation.CLASSIC_NEG, r.filmSimulation)
        assertEquals(Strength.STRONG, r.grainEffect)
        assertEquals(GrainSize.SMALL, r.grainSize)
        assertEquals(Strength.WEAK, r.colorChromeEffect)
        assertEquals(Strength.STRONG, r.colorChromeFxBlue)
        assertEquals(WhiteBalance.KELVIN, r.whiteBalance)
        assertEquals(5700, r.kelvin)
        assertEquals(1, r.wbShiftRed)
        assertEquals(1, r.wbShiftBlue)
        assertEquals(DynamicRange.DR400, r.dynamicRange)
        assertEquals(2.5, r.highlight, 0.0)
        assertEquals(-2.0, r.shadow, 0.0)
        assertEquals(4, r.color)
        assertEquals(-2, r.sharpness)
        assertEquals(-4, r.noiseReduction)
        assertEquals(-3, r.clarity)
        assertEquals("Auto, up to ISO 6400", r.iso)
        assertEquals("0 to -2/3 (typically)", r.exposureCompensation)
        assertEquals(listOf("import"), r.tags)
    }

    @Test
    fun rejectsTextWithoutFilmSimulation() {
        assertNull(FujiStyleCard.parse("random text with no recipe"))
        assertNull(FujiStyleCard.parse("Film Simulation: SOMETHING UNKNOWN | Red: 1"))
    }
}
