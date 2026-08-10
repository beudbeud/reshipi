package com.beudbeud.fuji.ui

import android.content.Context
import android.net.Uri
import com.beudbeud.fuji.data.FujiExif
import com.beudbeud.fuji.data.FujiStyleCard
import com.beudbeud.fuji.data.RecipeRepository
import com.beudbeud.fuji.model.Recipe
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Turns an image into a recipe: Fujifilm EXIF first (out-of-camera JPEG),
 * FujiStyle-card OCR as fallback. The image itself becomes the illustration.
 * onResult(null) when nothing readable was found.
 */
internal fun importRecipeImage(
    context: Context,
    repo: RecipeRepository,
    uri: Uri,
    onResult: (Recipe?) -> Unit,
) {
    val fromExif = runCatching {
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            .let { FujiExif.parse(it) }
    }.getOrNull()
    if (fromExif != null) {
        onResult(fromExif.copy(photos = listOf(repo.addPhoto(uri))))
        return
    }
    runCatching {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(InputImage.fromFilePath(context, uri))
            .addOnSuccessListener { ocr ->
                val recipe = FujiStyleCard.parse(ocr.text)
                onResult(recipe?.copy(photos = listOf(repo.addPhoto(uri))))
            }
            .addOnFailureListener { onResult(null) }
    }.onFailure { onResult(null) }
}
