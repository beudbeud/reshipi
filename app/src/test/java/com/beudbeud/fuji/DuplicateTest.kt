package com.beudbeud.fuji

import com.beudbeud.fuji.model.FilmSimulation
import com.beudbeud.fuji.model.GrainSize
import com.beudbeud.fuji.model.Recipe
import com.beudbeud.fuji.model.Strength
import com.beudbeud.fuji.model.WhiteBalance
import com.beudbeud.fuji.model.settingsKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DuplicateTest {
    private val base = Recipe(
        name = "Kodachrome 64",
        filmSimulation = FilmSimulation.CLASSIC_CHROME,
        highlight = 1.0,
        shadow = -1.0,
        color = 2,
    )

    @Test
    fun identityIgnoresEverythingButTheSettings() {
        // Same look, refetched from another site under another name, with its own
        // photo, tags and id: still the same recipe.
        val other = base.copy(
            id = "different",
            name = "Kodachrome 64 (v2)",
            notes = "found on another blog",
            tags = listOf("web"),
            photos = listOf("a.jpg"),
            favorite = true,
            iso = "Auto up to 6400",
            exposureCompensation = "+1/3",
            updatedAt = 999,
        )
        assertEquals(base.settingsKey, other.settingsKey)
    }

    @Test
    fun anySettingChangeMakesItADifferentRecipe() {
        val variants = listOf(
            base.copy(filmSimulation = FilmSimulation.CLASSIC_NEG),
            base.copy(highlight = 1.5),
            base.copy(shadow = 0.0),
            base.copy(color = 3),
            base.copy(sharpness = 1),
            base.copy(noiseReduction = -1),
            base.copy(clarity = 2),
            base.copy(whiteBalance = WhiteBalance.DAYLIGHT),
            base.copy(wbShiftRed = 1),
            base.copy(wbShiftBlue = -1),
            base.copy(grainEffect = Strength.WEAK),
            base.copy(colorChromeEffect = Strength.STRONG),
            base.copy(colorChromeFxBlue = Strength.WEAK),
        )
        variants.forEach { assertNotEquals(it.toString(), base.settingsKey, it.settingsKey) }
    }

    @Test
    fun conditionalFieldsOnlyCountWhenTheyApply() {
        // Grain is off, so its size is not a real difference — the camera keeps a
        // stale size while grain is disabled.
        assertEquals(
            base.copy(grainSize = GrainSize.SMALL).settingsKey,
            base.copy(grainSize = GrainSize.LARGE).settingsKey,
        )
        // With grain on, size matters again
        val grainy = base.copy(grainEffect = Strength.WEAK)
        assertNotEquals(
            grainy.copy(grainSize = GrainSize.SMALL).settingsKey,
            grainy.copy(grainSize = GrainSize.LARGE).settingsKey,
        )
        // Kelvin is meaningless unless the white balance is Kelvin
        assertEquals(base.copy(kelvin = 5600).settingsKey, base.copy(kelvin = 7000).settingsKey)
        val k = base.copy(whiteBalance = WhiteBalance.KELVIN)
        assertNotEquals(k.copy(kelvin = 5600).settingsKey, k.copy(kelvin = 7000).settingsKey)
    }
}
