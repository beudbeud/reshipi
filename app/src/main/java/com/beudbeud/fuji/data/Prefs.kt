package com.beudbeud.fuji.data

import android.content.Context
import com.beudbeud.fuji.model.CAMERA_MODELS
import com.beudbeud.fuji.model.Generation
import com.beudbeud.fuji.model.Recipe

internal fun prefs(context: Context) =
    context.getSharedPreferences("reshipi", Context.MODE_PRIVATE)

/**
 * The body a new recipe starts on.
 *
 * A blank recipe used to open on X-Trans IV, the middle of the range and nobody
 * in particular, so every recipe from scratch began by correcting two fields —
 * and until they were corrected the form offered the wrong generation's ranges
 * and film simulations. There is no right default to pick for someone else,
 * which is exactly what a setting is for.
 */
object MyCamera {
    private const val KEY = "my_camera"

    /**
     * The label chosen, which is either a model or a bare generation, matching
     * the picker in the recipe form. Null when nothing has been chosen.
     */
    fun label(context: Context): String? = prefs(context).getString(KEY, null)

    fun set(context: Context, label: String?) {
        prefs(context).edit().apply { if (label == null) remove(KEY) else putString(KEY, label) }.apply()
        DebugLog.log("my camera set to ${label ?: "none"}")
    }

    /** What a recipe from scratch starts as. */
    fun blankRecipe(context: Context): Recipe {
        val label = label(context) ?: return Recipe()
        CAMERA_MODELS.firstOrNull { it.first == label }?.let { (model, gen) ->
            return Recipe(cameraModel = model, generation = gen)
        }
        // A bare generation carries no model, the same as the form's own generic
        // entries — a recipe that targets a family rather than a body.
        Generation.entries.firstOrNull { it.label == label }?.let {
            return Recipe(generation = it)
        }
        return Recipe()
    }
}
