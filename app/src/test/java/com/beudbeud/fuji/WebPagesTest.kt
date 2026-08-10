package com.beudbeud.fuji

import com.beudbeud.fuji.data.FujiStyleCard
import com.beudbeud.fuji.data.WebImport
import com.beudbeud.fuji.model.DynamicRange
import com.beudbeud.fuji.model.FilmSimulation
import com.beudbeud.fuji.model.GrainSize
import com.beudbeud.fuji.model.Recipe
import com.beudbeud.fuji.model.Strength
import com.beudbeud.fuji.model.WhiteBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field-validation harness: real recipe pages saved as fixtures, run through
 * the exact production pipeline (htmlToText → pairKeyValueLines →
 * ensureFilmSimulationKey → parseAll).
 */
class WebPagesTest {

    private fun parsePage(name: String): List<Recipe> {
        val html = javaClass.getResourceAsStream("/pages/$name")!!.readBytes().decodeToString()
        val text = WebImport.ensureFilmSimulationKey(
            WebImport.pairKeyValueLines(WebImport.htmlToText(html))
        )
        return FujiStyleCard.parseAll(text, tag = "web")
    }

    @Test
    fun fujistyleapp_headingValueWithSplitWbShift() {
        val recipes = parsePage("fujistyleapp.html")
        assertEquals(1, recipes.size)
        val r = recipes.first()
        assertEquals(FilmSimulation.ETERNA, r.filmSimulation)
        assertEquals(DynamicRange.DR400, r.dynamicRange)
        assertEquals(WhiteBalance.FLUORESCENT_3, r.whiteBalance)
        assertEquals(-8, r.wbShiftRed)
        assertEquals(-4, r.wbShiftBlue)
        assertEquals(Strength.WEAK, r.grainEffect)
        assertEquals(GrainSize.LARGE, r.grainSize)
        assertEquals(Strength.STRONG, r.colorChromeEffect)
        assertEquals(Strength.STRONG, r.colorChromeFxBlue)
        assertEquals(4, r.color)
        assertEquals(-3, r.sharpness)
        assertEquals(-4, r.noiseReduction)
    }

    @Test
    fun rossandhisjpegs_bareSimAndInlineShifts() {
        val recipes = parsePage("ross-recipe.html")
        assertEquals(1, recipes.size)
        val r = recipes.first()
        assertEquals(FilmSimulation.CLASSIC_NEG, r.filmSimulation)
        assertEquals(DynamicRange.DR200, r.dynamicRange)
        assertEquals(6, r.wbShiftRed)
        assertEquals(-7, r.wbShiftBlue)
        assertEquals(-2.0, r.highlight, 0.0)
        assertEquals(1.0, r.shadow, 0.0)
        assertEquals(-2, r.color)
        assertEquals(0, r.sharpness)
        assertEquals(5, r.clarity)
        assertEquals(Strength.WEAK, r.grainEffect)
        assertEquals(GrainSize.LARGE, r.grainSize)
        assertEquals(Strength.WEAK, r.colorChromeEffect)
        assertEquals(Strength.WEAK, r.colorChromeFxBlue)
    }

    @Test
    fun snapsbyfox_dashSeparatorMultiRecipe() {
        val recipes = parsePage("snapsbyfox.html")
        assertTrue("expected >=4 recipes, got ${recipes.size}", recipes.size >= 4)
        val sims = recipes.map { it.filmSimulation }.toSet()
        assertTrue(FilmSimulation.NOSTALGIC_NEG in sims)
        assertTrue(FilmSimulation.VELVIA in sims)
        assertTrue(FilmSimulation.CLASSIC_NEG in sims)
        assertTrue(FilmSimulation.CLASSIC_CHROME in sims)
        val nostalgic = recipes.first { it.filmSimulation == FilmSimulation.NOSTALGIC_NEG }
        assertEquals(1, nostalgic.wbShiftRed)
        assertEquals(-1, nostalgic.wbShiftBlue)
        assertEquals(Strength.STRONG, nostalgic.grainEffect)
        assertEquals(GrainSize.LARGE, nostalgic.grainSize)
        assertEquals(-3, nostalgic.sharpness)
        assertEquals(-3, nostalgic.clarity)
    }

    @Test
    fun lifeunintended_pluralsAndChromeVariants() {
        val recipes = parsePage("lifeunintended.html")
        assertTrue("expected >=2 recipes, got ${recipes.size}", recipes.size >= 2)
        val match = recipes.any {
            it.dynamicRange == DynamicRange.DR200 && it.highlight == 1.0 &&
                it.grainEffect == Strength.STRONG && it.grainSize == GrainSize.LARGE &&
                it.colorChromeEffect == Strength.OFF && it.colorChromeFxBlue == Strength.WEAK
        }
        assertTrue("no recipe matched the lifeunintended reference values", match)
    }

    @Test
    fun kevinmullins_tableLayoutMultiRecipe() {
        val recipes = parsePage("kevinmullins.html")
        assertTrue("expected >=5 recipes, got ${recipes.size}", recipes.size >= 5)
        val sims = recipes.map { it.filmSimulation }.toSet()
        assertTrue(FilmSimulation.ACROS_R in sims)
        assertTrue(FilmSimulation.ACROS_YE in sims)
        assertTrue(FilmSimulation.CLASSIC_CHROME in sims)
        assertTrue(FilmSimulation.CLASSIC_NEG in sims)
        val lighthouse = recipes.first {
            it.filmSimulation == FilmSimulation.ACROS_R && it.highlight == 4.0
        }
        assertEquals(4.0, lighthouse.shadow, 0.0)
        assertEquals(1, lighthouse.sharpness)
        assertEquals(-4, lighthouse.noiseReduction)
        assertEquals(3, lighthouse.clarity)
        assertEquals(Strength.STRONG, lighthouse.grainEffect)
        assertEquals(GrainSize.LARGE, lighthouse.grainSize)
    }

    @Test
    fun grainyjpegs_inlineWbShiftsNoColon() {
        val r = parsePage("grainyjpegs.html").single()
        assertEquals(FilmSimulation.ETERNA, r.filmSimulation)
        assertEquals(DynamicRange.DR400, r.dynamicRange)
        assertEquals(WhiteBalance.FLUORESCENT_2, r.whiteBalance)
        assertEquals(5, r.wbShiftRed)      // "R+5 B-6" — no colon after the letter
        assertEquals(-6, r.wbShiftBlue)
        assertEquals(-0.5, r.highlight, 0.0)
        assertEquals(-1.0, r.shadow, 0.0)
        assertEquals(-2, r.color)
        assertEquals(Strength.WEAK, r.grainEffect)
        assertEquals(GrainSize.SMALL, r.grainSize)
        assertEquals(Strength.WEAK, r.colorChromeEffect)
        assertEquals(Strength.STRONG, r.colorChromeFxBlue)
    }

    @Test
    fun scotttucker_abbreviatedKeysAndToneCurve() {
        val r = parsePage("scotttucker.html").single()
        assertEquals(FilmSimulation.CLASSIC_NEG, r.filmSimulation)  // "Film Sim:"
        assertEquals(DynamicRange.DR400, r.dynamicRange)            // "DR: DR400"
        assertEquals(WhiteBalance.KELVIN, r.whiteBalance)
        assertEquals(5100, r.kelvin)
        assertEquals(2, r.wbShiftRed)
        assertEquals(-2, r.wbShiftBlue)
        assertEquals(-2.0, r.highlight, 0.0)                        // "Tone Curve: H:-2, S:-2"
        assertEquals(-2.0, r.shadow, 0.0)
        assertEquals(4, r.color)
        assertEquals(-2, r.noiseReduction)
        assertEquals(Strength.STRONG, r.grainEffect)                // "Strong Small"
        assertEquals(GrainSize.SMALL, r.grainSize)
    }

    @Test
    fun filmRecipes_gluedKeyValuesAndUnicodeMinus() {
        val r = parsePage("filmrecipes.html").single()
        assertEquals(FilmSimulation.CLASSIC_CHROME, r.filmSimulation) // "Film SimulationClassic Chrome"
        assertEquals(DynamicRange.DR400, r.dynamicRange)
        assertEquals(WhiteBalance.AUTO, r.whiteBalance)
        assertEquals(2, r.wbShiftRed)
        assertEquals(-4, r.wbShiftBlue)
        assertEquals(1.0, r.highlight, 0.0)                           // "Highlights1"
        assertEquals(-1.0, r.shadow, 0.0)                             // "Shadows&#8209;1"
        assertEquals(3, r.color)
        assertEquals(-4, r.noiseReduction)                            // "ISO N.R.&#8209;4"
        assertEquals(Strength.WEAK, r.grainEffect)
        assertEquals(GrainSize.SMALL, r.grainSize)
        assertEquals(Strength.OFF, r.colorChromeEffect)               // "Col. Chr. EffectOff"
        assertEquals(Strength.OFF, r.colorChromeFxBlue)               // "Col. Chr. BlueOff"
    }

    @Test
    fun filmsimrecipes_headingValueLayout() {
        val r = parsePage("filmsimrecipes.html").single()
        assertEquals(FilmSimulation.PRO_NEG_HI, r.filmSimulation)
        assertEquals(DynamicRange.DR400, r.dynamicRange)
        assertEquals(WhiteBalance.AUTO, r.whiteBalance)
        assertEquals(4, r.wbShiftRed)
        assertEquals(-6, r.wbShiftBlue)
        assertEquals(-1.0, r.highlight, 0.0)
        assertEquals(-2.0, r.shadow, 0.0)
        assertEquals(4, r.color)
        assertEquals(-2, r.sharpness)
        assertEquals(-4, r.noiseReduction)
        assertEquals(-2, r.clarity)
        assertEquals(Strength.STRONG, r.grainEffect)                  // "Strong /Small"
        assertEquals(GrainSize.SMALL, r.grainSize)
    }

    @Test
    fun shuttergrooveColorPlus_chromeFxWithoutBlueKeyword() {
        val r = parsePage("shuttergroove-cp.html").single()
        assertEquals(FilmSimulation.CLASSIC_CHROME, r.filmSimulation)
        assertEquals(DynamicRange.DR100, r.dynamicRange)
        assertEquals(WhiteBalance.DAYLIGHT, r.whiteBalance)
        assertEquals(0, r.wbShiftRed)
        assertEquals(-3, r.wbShiftBlue)
        assertEquals(2, r.color)
        assertEquals(-4, r.noiseReduction)
        assertEquals(Strength.WEAK, r.grainEffect)                    // "Weak / Small"
        assertEquals(GrainSize.SMALL, r.grainSize)
        assertEquals(Strength.OFF, r.colorChromeEffect)
        assertEquals(Strength.OFF, r.colorChromeFxBlue)               // "Color Chrome FX:" (no "Blue")
        assertEquals("Auto up to 6400", r.iso)
    }

    @Test
    fun ahradwani_startWithKeyBracketQualifiersAndKelvinPrefix() {
        val recipes = parsePage("ahradwani.html")
        assertTrue("expected >=40 recipes, got ${recipes.size}", recipes.size >= 40)

        val sand = recipes.first { it.name == "Sand Storm" }   // "[Name: Sand Storm ]"
        assertEquals(FilmSimulation.CLASSIC_NEG, sand.filmSimulation)  // "StartWith: Classic Neg."
        assertEquals(WhiteBalance.KELVIN, sand.whiteBalance)
        assertEquals(7000, sand.kelvin)                        // "K7000" prefix form
        assertEquals(4, sand.wbShiftRed)
        assertEquals(-4, sand.wbShiftBlue)
        assertEquals(DynamicRange.DR100, sand.dynamicRange)    // bare "100"
        assertEquals(-2.0, sand.highlight, 0.0)                // "Tone Curve (Highlights/Shadows): H: -2, S: +1"
        assertEquals(1.0, sand.shadow, 0.0)
        assertEquals(3, sand.color)
        assertEquals(0, sand.sharpness)
        assertEquals(-1, sand.noiseReduction)
        assertEquals(Strength.STRONG, sand.grainEffect)        // "Grain [effect, Size] : Strong, small"
        assertEquals(GrainSize.SMALL, sand.grainSize)
        assertEquals(Strength.STRONG, sand.colorChromeEffect)
        assertEquals(Strength.STRONG, sand.colorChromeFxBlue)

        val eternal = recipes.first { it.name == "Light Eternal" }
        assertEquals(DynamicRange.DR400, eternal.dynamicRange) // "400%"
        assertEquals(Strength.WEAK, eternal.colorChromeFxBlue)
        assertEquals(-1, eternal.sharpness)
    }
}
