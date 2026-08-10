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
}
