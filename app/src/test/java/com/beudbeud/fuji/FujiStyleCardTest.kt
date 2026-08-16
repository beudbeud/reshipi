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
    fun parsesCarlCruzDialect() {
        // Bare "Grain", "Chrome Effect" without Color, shifts as "(R: +1, B: -2)",
        // combined "Tone Curve: Highlights -1, Shadows 0"
        val r = FujiStyleCard.parse(
            """
            Film Simulation: Classic Chrome
            Grain: Off
            Chrome Effect: Strong
            White Balance: Auto (R: +1, B: -2)
            Dynamic Range: Auto
            Tone Curve: Highlights -1, Shadows 0
            Color: +4
            Sharpness: 0
            High ISO NR: -4
            Clarity: 0
            """.trimIndent(),
            tag = "web",
        )!!
        assertEquals(FilmSimulation.CLASSIC_CHROME, r.filmSimulation)
        assertEquals(Strength.OFF, r.grainEffect)
        assertEquals(Strength.STRONG, r.colorChromeEffect)
        assertEquals(WhiteBalance.AUTO, r.whiteBalance)
        assertEquals(1, r.wbShiftRed)
        assertEquals(-2, r.wbShiftBlue)
        assertEquals(DynamicRange.AUTO, r.dynamicRange)
        assertEquals(-1.0, r.highlight, 0.0)
        assertEquals(0.0, r.shadow, 0.0)
        assertEquals(4, r.color)
        assertEquals(-4, r.noiseReduction)
    }

    @Test
    fun parsesGermanLocalizedCard() {
        // Real FujiStyle card exported with a German phone locale: English keys,
        // German strength/size words ("Schwach" = weak, "Klein" = small)
        val r = FujiStyleCard.parse(
            "#Nostalgic Golden Hour\n" +
                "Film Simulation: CLASSIC Neg. | White Balance: AUTO | Red: 2 | Blue: -5 | " +
                "Dynamic Range: DR400 | Grain Effect - Roughness: Schwach | " +
                "Grain Effect - Size: Klein | Color Chrome Effect: Off | Color FX Blue:None | " +
                "Colour: 4 | Sharpness: 1 | Highlight: -2.0 | Shadow: -2.0 | " +
                "High ISO NR: -4 | Clarity: -3 | ISO:",
            tag = "fujistyle",
        )!!
        assertEquals("Nostalgic Golden Hour", r.name)
        assertEquals(FilmSimulation.CLASSIC_NEG, r.filmSimulation)
        assertEquals(WhiteBalance.AUTO, r.whiteBalance)
        assertEquals(2, r.wbShiftRed)
        assertEquals(-5, r.wbShiftBlue)
        assertEquals(DynamicRange.DR400, r.dynamicRange)
        assertEquals(Strength.WEAK, r.grainEffect)   // Schwach
        assertEquals(GrainSize.SMALL, r.grainSize)    // Klein
        assertEquals(Strength.OFF, r.colorChromeEffect)
        assertEquals(Strength.OFF, r.colorChromeFxBlue)
        assertEquals(4, r.color)
        assertEquals(1, r.sharpness)
        assertEquals(-2.0, r.highlight, 0.0)
        assertEquals(-2.0, r.shadow, 0.0)
        assertEquals(-4, r.noiseReduction)
        assertEquals(-3, r.clarity)
    }

    @Test
    fun parsesAFrenchRecipe() {
        // Pasted verbatim from a French recipe page, accents, "Décalage" and all.
        val r = FujiStyleCard.parse(
            """
            Recette de simulation de film Fujifilm (Cyberpunk Night)
            Simulation de base : Classic Chrome
            Gamme dynamique : DR400
            Surbrillance : -2
            Ombre : +3
            Couleur : +3
            Réduction du bruit : -4
            Netteté : +2
            Effet de grain : Faible, Petit
            Balance des blancs : 3000K (Décalage : Rouge -3, Bleu +4)
            Clarté : -3
            """.trimIndent()
        )!!
        assertEquals(FilmSimulation.CLASSIC_CHROME, r.filmSimulation)
        assertEquals(DynamicRange.DR400, r.dynamicRange)
        assertEquals(-2.0, r.highlight, 0.0)
        assertEquals(3.0, r.shadow, 0.0)
        assertEquals(3, r.color)
        assertEquals(-4, r.noiseReduction)
        assertEquals(2, r.sharpness)
        assertEquals(Strength.WEAK, r.grainEffect)    // Faible
        assertEquals(GrainSize.SMALL, r.grainSize)    // Petit
        assertEquals(WhiteBalance.KELVIN, r.whiteBalance)
        assertEquals(3000, r.kelvin)
        assertEquals(-3, r.wbShiftRed)
        assertEquals(4, r.wbShiftBlue)
        assertEquals(-3, r.clarity)
    }

    @Test
    fun aFrenchShadeValueIsNotTheShadowsField() {
        // "Ombre" is both the Shadows key and the Shade white balance value
        val r = FujiStyleCard.parse("Simulation de film : Provia | Balance des blancs : Ombre")!!
        assertEquals(WhiteBalance.SHADE, r.whiteBalance)
        assertEquals(0.0, r.shadow, 0.0)
    }

    @Test
    fun parsesCondensedToneCurveAndMonochromaticColorLine() {
        // "H -2, S +3" without colons, "MONOCHROMATIC COLOR: N/A" before the
        // real Color line, and "D RANGE PRIORITY" spelled out
        val r = FujiStyleCard.parse(
            """
            FILM SIMULATION: PRO Neg. Std
            MONOCHROMATIC COLOR: N/A
            GRAIN EFFECT: STRONG, LARGE
            COLOR CHROME EFFECT: STRONG
            COLOR CHROME FX BLUE: WEAK
            SMOOTH SKIN EFFECT: OFF
            WHITE BALANCE: 5900K/ R +2, B -3
            DYNAMIC RANGE: DR100
            D RANGE PRIORITY: OFF
            TONE CURVE: H -2, S +3
            COLOR: +4
            SHARPNESS: -2
            HIGH ISO NR: -4
            CLARITY: -2
            """.trimIndent()
        )!!
        assertEquals(FilmSimulation.PRO_NEG_STD, r.filmSimulation)
        assertEquals(Strength.STRONG, r.grainEffect)
        assertEquals(GrainSize.LARGE, r.grainSize)
        assertEquals(Strength.STRONG, r.colorChromeEffect)
        assertEquals(Strength.WEAK, r.colorChromeFxBlue)
        assertEquals(WhiteBalance.KELVIN, r.whiteBalance)
        assertEquals(5900, r.kelvin)
        assertEquals(2, r.wbShiftRed)
        assertEquals(-3, r.wbShiftBlue)
        assertEquals(DynamicRange.DR100, r.dynamicRange)
        assertEquals(-2.0, r.highlight, 0.0)
        assertEquals(3.0, r.shadow, 0.0)
        assertEquals(4, r.color)
        assertEquals(-2, r.sharpness)
        assertEquals(-4, r.noiseReduction)
        assertEquals(-2, r.clarity)
    }

    /**
     * The app's own French share text, verbatim. Sharing a recipe and importing
     * it back has to return the same recipe — three of these labels went
     * unrecognised, so Color Chrome, its blue variant and the exposure
     * compensation silently came back at their defaults.
     */
    @Test
    fun frenchShareTextSurvivesARoundTrip() {
        val r = FujiStyleCard.parse(
            """
            cinestill 800T v2
            Boîtier: X-Trans V
            Simulation de film: Classic Chrome
            Balance des blancs: Kelvin 6600K R-1 B-3
            Plage dynamique: DR400
            Priorité plage dynamique: Désactivé
            Hautes lumières: -2
            Ombres: -0.5
            Couleur: +3
            Netteté: -2
            Réduction du bruit: -4
            Effet de grain: Fort · Grand
            Effet Color Chrome: Fort
            Color Chrome FX Bleu: Désactivé
            Clarté: +3
            ISO: 1000
            Correction d'exposition: -1
            """.trimIndent()
        )!!
        assertEquals(FilmSimulation.CLASSIC_CHROME, r.filmSimulation)
        assertEquals(WhiteBalance.KELVIN, r.whiteBalance)
        assertEquals(6600, r.kelvin)
        assertEquals(-1, r.wbShiftRed)
        assertEquals(-3, r.wbShiftBlue)
        assertEquals(DynamicRange.DR400, r.dynamicRange)
        assertEquals(-2.0, r.highlight, 0.0)
        assertEquals(-0.5, r.shadow, 0.0)
        assertEquals(3, r.color)
        assertEquals(-2, r.sharpness)
        assertEquals(-4, r.noiseReduction)
        assertEquals(Strength.STRONG, r.grainEffect)
        assertEquals(GrainSize.LARGE, r.grainSize)
        assertEquals(Strength.STRONG, r.colorChromeEffect)
        assertEquals(Strength.OFF, r.colorChromeFxBlue)
        assertEquals(3, r.clarity)
        assertEquals("1000", r.iso)
        assertEquals("-1", r.exposureCompensation)
    }

    @Test
    fun rejectsTextWithoutFilmSimulation() {
        assertNull(FujiStyleCard.parse("random text with no recipe"))
        assertNull(FujiStyleCard.parse("Film Simulation: SOMETHING UNKNOWN | Red: 1"))
    }
}
