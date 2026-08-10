package com.beudbeud.fuji

import com.beudbeud.fuji.data.FujiStyleCard
import com.beudbeud.fuji.data.OcrBlock
import com.beudbeud.fuji.data.OcrText
import com.beudbeud.fuji.model.FilmSimulation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTextTest {
    /** Labels at x≈100, values at x≈700, one row every 100px — a card layout. */
    private fun twoColumns(rows: List<Pair<String, String>>): List<OcrBlock> =
        rows.flatMapIndexed { i, (label, value) ->
            val y = 100 + i * 100
            listOf(
                OcrBlock(label, left = 40, top = y, right = 160, bottom = y + 40),
                OcrBlock(value, left = 640, top = y, right = 760, bottom = y + 40),
            )
        }

    @Test
    fun twoColumnCardIsStitchedByPosition() {
        val rows = listOf(
            "Film Simulation" to "Classic Chrome",
            "White Balance" to "Daylight",
            "Highlight" to "-1",
            "Shadow" to "+2",
            "Color" to "+1",
            "Sharpness" to "-2",
        )
        val out = OcrText.stitchColumns(twoColumns(rows), imageWidth = 800)
        assertNotNull(out)
        assertEquals(
            rows.map { "${it.first}: ${it.second}" },
            out!!.trim().lines(),
        )
    }

    @Test
    fun singleColumnIsLeftToTheReadingOrder() {
        // Everything in one column: no wide gap, so geometry must decline.
        val blocks = (0 until 8).map {
            OcrBlock("Line $it", left = 40, top = 100 * it, right = 300, bottom = 100 * it + 40)
        }
        assertNull(OcrText.stitchColumns(blocks, imageWidth = 800))
        assertNull(OcrText.stitchColumns(emptyList(), imageWidth = 800))
    }

    @Test
    fun confusedCharactersAreRepaired() {
        assertEquals("Highlight: +1", OcrText.normalize("Highlight| +|"))
        assertEquals("Shadow: -1", OcrText.normalize("Shadow│ -]"))
        assertEquals("ISO 6400", OcrText.normalize("IS0 6400"))
        assertEquals("Grain: OFF", OcrText.normalize("Grain: 0FF"))
        assertEquals("Highlight: -2", OcrText.normalize("Highlight: −2"))
        // Real brackets survive: they qualify keys on some cards
        assertEquals("[Color] Chrome Effect: Strong", OcrText.normalize("[Color] Chrome Effect: Strong"))
    }

    @Test
    fun stitchedCardParsesIntoARecipe() {
        // End to end: geometry + normalization must produce something the parser reads,
        // including a value OCR mangled into "+|".
        val blocks = twoColumns(
            listOf(
                "Film Simulation" to "Classic Chrome",
                "White Balance" to "Daylight",
                "Highlight" to "+|",
                "Shadow" to "-2",
                "Color" to "+1",
                "Grain Effect" to "0FF",
            )
        )
        val recipe = FujiStyleCard.parse(OcrText.prepare(blocks, 800, fallback = ""))
        assertNotNull(recipe)
        assertEquals(FilmSimulation.CLASSIC_CHROME, recipe!!.filmSimulation)
        assertEquals(1.0, recipe.highlight, 0.0)
        assertEquals(-2.0, recipe.shadow, 0.0)
        assertTrue(recipe.color == 1)
    }
}
