package com.beudbeud.fuji.model

import androidx.annotation.StringRes
import com.beudbeud.fuji.R
import kotlinx.serialization.Serializable
import java.util.UUID

// ponytail: availability modeled per sensor generation, not per camera body —
// firmware differences within a generation (e.g. X-T3 vs X-Pro3) are ignored.
enum class Generation(val label: String) {
    X_TRANS_I("X-Trans I"),
    X_TRANS_II("X-Trans II"),
    X_TRANS_III("X-Trans III"),
    X_TRANS_IV("X-Trans IV"),
    X_TRANS_V("X-Trans V");

    val hasGrainEffect get() = this >= X_TRANS_III
    val hasGrainSize get() = this >= X_TRANS_IV
    val hasColorChrome get() = this >= X_TRANS_IV
    val hasClarity get() = this >= X_TRANS_IV
    val hasDRangePriority get() = this >= X_TRANS_IV
    val toneMax get() = if (this >= X_TRANS_III) 4.0 else 2.0
    val toneStep get() = if (this >= X_TRANS_IV) 0.5 else 1.0
    val colorRange get() = if (this >= X_TRANS_III) 4 else 2
    val filmSimulations get() = FilmSimulation.entries.filter { it.minGen <= this }
}

/**
 * Camera model → feature set. Hybrids (X-Trans IV sensor + X-Processor 5,
 * e.g. X-T30 III, X-M5) map to X_TRANS_V because their film simulations and
 * settings match that generation. X-Trans line only; Bayer/GFX not covered.
 */
val CAMERA_MODELS: List<Pair<String, Generation>> = listOf(
    "X-Pro1" to Generation.X_TRANS_I,
    "X-E1" to Generation.X_TRANS_I,
    "X-M1" to Generation.X_TRANS_I,
    "X100S" to Generation.X_TRANS_II,
    "X100T" to Generation.X_TRANS_II,
    "X-E2" to Generation.X_TRANS_II,
    "X-E2S" to Generation.X_TRANS_II,
    "X-T1" to Generation.X_TRANS_II,
    "X-T10" to Generation.X_TRANS_II,
    "X70" to Generation.X_TRANS_II,
    "X-Pro2" to Generation.X_TRANS_III,
    "X100F" to Generation.X_TRANS_III,
    "X-T2" to Generation.X_TRANS_III,
    "X-T20" to Generation.X_TRANS_III,
    "X-E3" to Generation.X_TRANS_III,
    "X-H1" to Generation.X_TRANS_III,
    "X-T3" to Generation.X_TRANS_IV,
    "X-T30" to Generation.X_TRANS_IV,
    "X-Pro3" to Generation.X_TRANS_IV,
    "X100V" to Generation.X_TRANS_IV,
    "X-T4" to Generation.X_TRANS_IV,
    "X-S10" to Generation.X_TRANS_IV,
    "X-E4" to Generation.X_TRANS_IV,
    "X-T30 II" to Generation.X_TRANS_IV,
    "X-H2S" to Generation.X_TRANS_V,
    "X-H2" to Generation.X_TRANS_V,
    "X-T5" to Generation.X_TRANS_V,
    "X-S20" to Generation.X_TRANS_V,
    "X100VI" to Generation.X_TRANS_V,
    "X-T50" to Generation.X_TRANS_V,
    "X-M5" to Generation.X_TRANS_V,
    "X-E5" to Generation.X_TRANS_V,
    "X-T30 III" to Generation.X_TRANS_V,
)

// Fuji product names, not translated.
enum class FilmSimulation(val label: String, val minGen: Generation) {
    PROVIA("Provia/Std", Generation.X_TRANS_I),
    VELVIA("Velvia/Vivid", Generation.X_TRANS_I),
    ASTIA("Astia/Soft", Generation.X_TRANS_I),
    CLASSIC_CHROME("Classic Chrome", Generation.X_TRANS_II),
    CLASSIC_NEG("Classic Neg.", Generation.X_TRANS_IV),
    NOSTALGIC_NEG("Nostalgic Neg.", Generation.X_TRANS_V),
    REALA_ACE("Reala Ace", Generation.X_TRANS_V),
    PRO_NEG_HI("Pro Neg. Hi", Generation.X_TRANS_I),
    PRO_NEG_STD("Pro Neg. Std", Generation.X_TRANS_I),
    ETERNA("Eterna", Generation.X_TRANS_IV),
    ETERNA_BLEACH_BYPASS("Eterna Bleach Bypass", Generation.X_TRANS_IV),
    ACROS("Acros", Generation.X_TRANS_III),
    ACROS_YE("Acros +Ye", Generation.X_TRANS_III),
    ACROS_R("Acros +R", Generation.X_TRANS_III),
    ACROS_G("Acros +G", Generation.X_TRANS_III),
    MONOCHROME("Monochrome", Generation.X_TRANS_I),
    MONOCHROME_YE("Monochrome +Ye", Generation.X_TRANS_I),
    MONOCHROME_R("Monochrome +R", Generation.X_TRANS_I),
    MONOCHROME_G("Monochrome +G", Generation.X_TRANS_I),
    SEPIA("Sepia", Generation.X_TRANS_I),
}

enum class WhiteBalance(@StringRes val labelRes: Int) {
    AUTO(R.string.wb_auto),
    DAYLIGHT(R.string.wb_daylight),
    SHADE(R.string.wb_shade),
    FLUORESCENT_1(R.string.wb_fluorescent_1),
    FLUORESCENT_2(R.string.wb_fluorescent_2),
    FLUORESCENT_3(R.string.wb_fluorescent_3),
    INCANDESCENT(R.string.wb_incandescent),
    UNDERWATER(R.string.wb_underwater),
    KELVIN(R.string.wb_kelvin),

    // Measured white balances stored in the camera. Their PTP codes are not
    // confirmed on any body, so these are recorded in the recipe but never
    // written to the camera — see WB_CODE / toPresetProps.
    CUSTOM_1(R.string.wb_custom_1),
    CUSTOM_2(R.string.wb_custom_2),
    CUSTOM_3(R.string.wb_custom_3),
}

enum class DynamicRange(val label: String) { AUTO("Auto"), DR100("DR100"), DR200("DR200"), DR400("DR400") }

enum class DRangePriority(@StringRes val labelRes: Int) {
    OFF(R.string.off), AUTO(R.string.auto), WEAK(R.string.weak), STRONG(R.string.strong)
}

enum class Strength(@StringRes val labelRes: Int) {
    OFF(R.string.off), WEAK(R.string.weak), STRONG(R.string.strong)
}

enum class GrainSize(@StringRes val labelRes: Int) {
    SMALL(R.string.size_small), LARGE(R.string.size_large)
}

@Serializable
data class Recipe(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val cameraModel: String = "",   // "" = generic, only the generation is known
    val generation: Generation = Generation.X_TRANS_IV,
    val filmSimulation: FilmSimulation = FilmSimulation.PROVIA,
    val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
    val kelvin: Int? = null,
    val wbShiftRed: Int = 0,
    val wbShiftBlue: Int = 0,
    val dynamicRange: DynamicRange = DynamicRange.AUTO,
    val dRangePriority: DRangePriority = DRangePriority.OFF,
    val highlight: Double = 0.0,
    val shadow: Double = 0.0,
    val color: Int = 0,
    val sharpness: Int = 0,
    val noiseReduction: Int = 0,
    val grainEffect: Strength = Strength.OFF,
    val grainSize: GrainSize = GrainSize.SMALL,
    val colorChromeEffect: Strength = Strength.OFF,
    val colorChromeFxBlue: Strength = Strength.OFF,
    val clarity: Int = 0,
    val iso: String = "Auto",
    val exposureCompensation: String = "0",
    val notes: String = "",
    val tags: List<String> = emptyList(),
    val favorite: Boolean = false,
    val photos: List<String> = emptyList(),
    val updatedAt: Long = 0,
)

@Serializable
data class RecipeExport(val version: Int = 1, val recipes: List<Recipe> = emptyList())

/** Merge by id: unknown ids are added, known ids replaced only if the incoming one is newer. */
fun mergeRecipes(current: List<Recipe>, incoming: List<Recipe>): List<Recipe> {
    val result = current.associateBy { it.id }.toMutableMap()
    for (r in incoming) {
        val existing = result[r.id]
        if (existing == null || r.updatedAt > existing.updatedAt) result[r.id] = r
    }
    return result.values.toList()
}

/** "+1.5", "0", "-2" — half steps only shown when needed. */
fun formatSigned(v: Double): String {
    val s = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
    return if (v > 0) "+$s" else s
}

fun formatSigned(v: Int): String = if (v > 0) "+$v" else v.toString()

/** Display name for the recipe's target camera: model if known, else generation. */
val Recipe.cameraLabel: String get() = cameraModel.ifBlank { generation.label }
