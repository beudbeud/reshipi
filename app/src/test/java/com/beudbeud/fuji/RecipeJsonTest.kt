package com.beudbeud.fuji

import com.beudbeud.fuji.model.FilmSimulation
import com.beudbeud.fuji.model.Generation
import com.beudbeud.fuji.model.Recipe
import com.beudbeud.fuji.model.RecipeExport
import com.beudbeud.fuji.model.mergeRecipes
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeJsonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun roundTrip() {
        val r = Recipe(
            name = "Kodachrome 64",
            generation = Generation.X_TRANS_V,
            filmSimulation = FilmSimulation.REALA_ACE,
            highlight = 1.5,
            wbShiftRed = 2,
            wbShiftBlue = -5,
            favorite = true,
            photos = listOf("a.jpg"),
            updatedAt = 42,
        )
        val text = json.encodeToString(RecipeExport.serializer(), RecipeExport(recipes = listOf(r)))
        val back = json.decodeFromString(RecipeExport.serializer(), text)
        assertEquals(listOf(r), back.recipes)
    }

    @Test
    fun mergeKeepsNewerById() {
        val old = Recipe(id = "x", name = "old", updatedAt = 1)
        val newer = old.copy(name = "new", updatedAt = 2)
        val other = Recipe(id = "y", name = "other", updatedAt = 1)

        val merged = mergeRecipes(listOf(old), listOf(newer, other))
        assertEquals(setOf("x", "y"), merged.map { it.id }.toSet())
        assertEquals("new", merged.first { it.id == "x" }.name)

        // incoming older than current → current wins
        val keepCurrent = mergeRecipes(listOf(newer), listOf(old))
        assertEquals("new", keepCurrent.single().name)
    }

    @Test
    fun unknownKeysIgnored() {
        val r = json.decodeFromString(
            Recipe.serializer(),
            """{"id":"x","name":"n","generation":"X_TRANS_I","filmSimulation":"PROVIA","someFutureField":123}""",
        )
        assertEquals("n", r.name)
    }

    @Test
    fun generationMatrix() {
        assertTrue(FilmSimulation.CLASSIC_CHROME !in Generation.X_TRANS_I.filmSimulations)
        assertTrue(FilmSimulation.CLASSIC_NEG !in Generation.X_TRANS_III.filmSimulations)
        assertTrue(FilmSimulation.REALA_ACE in Generation.X_TRANS_V.filmSimulations)
        assertTrue(Generation.X_TRANS_III.hasGrainEffect)
        assertTrue(!Generation.X_TRANS_III.hasClarity)
        assertEquals(0.5, Generation.X_TRANS_IV.toneStep, 0.0)
        assertEquals(2, Generation.X_TRANS_II.colorRange)
    }
}
