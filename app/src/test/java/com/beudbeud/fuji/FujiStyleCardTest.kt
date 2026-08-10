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
    fun parsesFujiXWeeklyPageDialect() {
        // Page dialect: bare film-sim line (restored by WebImport), "Sharpening",
        // "Color Chrome Effect Blue"
        val page = """
            Classic Chrome
            Dynamic Range: DR400
            Highlight: -1
            Shadow: -2
            Color: +2
            Noise Reduction: -4
            Sharpening: -2
            Clarity: -2
            Grain Effect: Strong, Small
            Color Chrome Effect: Strong
            Color Chrome Effect Blue: Off
            White Balance: 5500K, +0 Red & -7 Blue
        """.trimIndent()
        val fixed = com.beudbeud.fuji.data.WebImport.ensureFilmSimulationKey(page)
        val r = FujiStyleCard.parse(fixed, tag = "fujixweekly")!!
        assertEquals(FilmSimulation.CLASSIC_CHROME, r.filmSimulation)
        assertEquals(-2, r.sharpness)
        assertEquals(Strength.OFF, r.colorChromeFxBlue)
        assertEquals(Strength.STRONG, r.colorChromeEffect)
        assertEquals(WhiteBalance.KELVIN, r.whiteBalance)
        assertEquals(5500, r.kelvin)
        assertEquals(0, r.wbShiftRed)
        assertEquals(-7, r.wbShiftBlue)
    }

    @Test
    fun htmlToTextStripsMarkup() {
        val text = com.beudbeud.fuji.data.WebImport.htmlToText(
            "<ul><li>Classic Chrome</li><li>Dynamic Range: DR400</li>" +
                "<li>White Balance: 5500K, +0 Red &amp; -7 Blue</li></ul>"
        )
        assertEquals(true, "Dynamic Range: DR400" in text)
        assertEquals(true, "+0 Red & -7 Blue" in text)
    }

    @Test
    fun parsesShutterGrooveDialect() {
        // Extracted from a real shuttergroove.com recipe page
        val r = FujiStyleCard.parse(
            """
            Film Simulation: Acros+G Filter
            Dynamic Range: DR400
            Highlight: +3
            Shadow: +2
            Noise Reduction: -4
            Sharpening: -2
            Clarity: 0
            Grain Effect: Off
            Color Chrome Effect: Strong
            Color Chrome FX Blue: Strong
            Monochromatic Color: WC: +0, MG: 0
            White Balance: Auto
            ISO: Auto 3200
            """.trimIndent(),
            tag = "web",
        )!!
        assertEquals(FilmSimulation.ACROS_G, r.filmSimulation)
        assertEquals(DynamicRange.DR400, r.dynamicRange)
        assertEquals(3.0, r.highlight, 0.0)
        assertEquals(2.0, r.shadow, 0.0)
        assertEquals(-4, r.noiseReduction)
        assertEquals(-2, r.sharpness)
        assertEquals(Strength.OFF, r.grainEffect)
        assertEquals(Strength.STRONG, r.colorChromeEffect)
        assertEquals(Strength.STRONG, r.colorChromeFxBlue)
        assertEquals(WhiteBalance.AUTO, r.whiteBalance)
        assertEquals("Auto 3200", r.iso)
    }

    @Test
    fun parsesDoubleSimLineAndWbShiftField() {
        // Second shuttergroove dialect: two Film Simulation lines (base then
        // variant) and a separate "White Balance Shift: R +2, B -2" field
        val r = FujiStyleCard.parse(
            """
            Film Simulation: Monochrome
            Film Simulation: Monochrome+ Ye Filter
            Monochromatic Color: 0
            Dynamic Range: DR200
            Highlight: 0
            Shadow: 0
            Clarity: +5
            Noise Reduction: -4
            Sharpness: +4
            Grain Effect: Weak, Small
            Color Chrome Effect: Strong
            Color Chrome FX Blue: Off
            White Balance: Daylight
            White Balance Shift: R +2, B -2
            ISO: Auto ISO 6400
            Exposure Compensation: 0 to -2/3
            """.trimIndent(),
            tag = "web",
        )!!
        assertEquals(FilmSimulation.MONOCHROME_YE, r.filmSimulation)
        assertEquals(WhiteBalance.DAYLIGHT, r.whiteBalance)
        assertEquals(2, r.wbShiftRed)
        assertEquals(-2, r.wbShiftBlue)
        assertEquals(DynamicRange.DR200, r.dynamicRange)
        assertEquals(5, r.clarity)
        assertEquals(-4, r.noiseReduction)
        assertEquals(4, r.sharpness)
        assertEquals(Strength.WEAK, r.grainEffect)
        assertEquals(GrainSize.SMALL, r.grainSize)
        assertEquals("Auto ISO 6400", r.iso)
        assertEquals("0 to -2/3", r.exposureCompensation)
    }

    @Test
    fun parsesHeadingValueLayout() {
        // Elementor-style pages: key on one line, value on the next
        val stripped = """
            Kodak Gold Film Recipe Custom Film Simulation Settings
            Film Simulation
            Classic Negative
            Highlight
            +1
            Shadow
            0
            Color
            +2
            Sharpness
            -1
            Noise Reduction
            -4
            Grain Effect / Grain Size
            Strong / Small
            Color Chrome Effect / FX Blue
            Off / Off
            WB / Color Temperature
            Daylight, Red 4, Blue -4
            Exposure Compensation
            from -2/3 to +2/3
            ISO
            Auto up to ISO 6400
            Clarity
            0
            Dynamic Range
            DR200
        """.trimIndent()
        val paired = com.beudbeud.fuji.data.WebImport.pairKeyValueLines(stripped)
        val r = FujiStyleCard.parse(paired, tag = "web")!!
        assertEquals(FilmSimulation.CLASSIC_NEG, r.filmSimulation)
        assertEquals(1.0, r.highlight, 0.0)
        assertEquals(2, r.color)
        assertEquals(-1, r.sharpness)
        assertEquals(-4, r.noiseReduction)
        assertEquals(Strength.STRONG, r.grainEffect)
        assertEquals(GrainSize.SMALL, r.grainSize)
        assertEquals(Strength.OFF, r.colorChromeEffect)
        assertEquals(Strength.OFF, r.colorChromeFxBlue)
        assertEquals(WhiteBalance.DAYLIGHT, r.whiteBalance)
        assertEquals(4, r.wbShiftRed)
        assertEquals(-4, r.wbShiftBlue)
        assertEquals(DynamicRange.DR200, r.dynamicRange)
        assertEquals("Auto up to ISO 6400", r.iso)
        assertEquals("from -2/3 to +2/3", r.exposureCompensation)
    }

    @Test
    fun rejectsTextWithoutFilmSimulation() {
        assertNull(FujiStyleCard.parse("random text with no recipe"))
        assertNull(FujiStyleCard.parse("Film Simulation: SOMETHING UNKNOWN | Red: 1"))
    }
}
