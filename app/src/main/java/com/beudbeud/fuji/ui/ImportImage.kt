package com.beudbeud.fuji.ui

import android.content.Context
import android.net.Uri
import com.beudbeud.fuji.data.CardCrop
import com.beudbeud.fuji.data.FujiExif
import com.beudbeud.fuji.data.FujiStyleCard
import com.beudbeud.fuji.data.OcrBlock
import com.beudbeud.fuji.data.OcrText
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
        val image = InputImage.fromFilePath(context, uri)
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(image)
            .addOnSuccessListener { ocr ->
                // Cards put labels and values in two columns; rebuild the pairs
                // from the boxes rather than trusting the flattened reading order.
                val blocks = ocr.textBlocks.mapNotNull { b ->
                    b.boundingBox?.let { OcrBlock(b.text, it.left, it.top, it.right, it.bottom) }
                }
                val recipe = FujiStyleCard.parse(OcrText.prepare(blocks, image.width, ocr.text))
                if (recipe == null) {
                    onResult(null)
                } else {
                    // Keep just the example photo from the card; a gray-placeholder
                    // card yields null → no photo, recipe shows its gradient tile.
                    val cropped = CardCrop.extractPhoto(context, uri)
                    val photos = when {
                        cropped != null -> listOf(repo.addPhotoBytes(cropped))
                        else -> emptyList()
                    }
                    onResult(recipe.copy(photos = photos))
                }
            }
            .addOnFailureListener { onResult(null) }
    }.onFailure { onResult(null) }
}
