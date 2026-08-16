package com.beudbeud.fuji.ui

import android.content.Context
import android.net.Uri
import com.beudbeud.fuji.R
import com.beudbeud.fuji.data.CardCrop
import com.beudbeud.fuji.data.FujiExif
import com.beudbeud.fuji.data.FujiStyleCard
import com.beudbeud.fuji.data.OcrBlock
import com.beudbeud.fuji.data.OcrText
import com.beudbeud.fuji.data.RecipeRepository
import com.beudbeud.fuji.model.Recipe
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Turns an image into a recipe: Fujifilm EXIF first (out-of-camera JPEG),
 * FujiStyle-card OCR as fallback. The image itself becomes the illustration.
 * Null when nothing readable was found.
 *
 * Every step here touches the disk or a full-size bitmap — reading the file,
 * copying it into app storage, decoding the card and scanning it row by row —
 * so all of it is kept off the caller's thread.
 */
internal suspend fun importRecipeImage(
    context: Context,
    repo: RecipeRepository,
    uri: Uri,
): Recipe? = runCatching {
    val bytes = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    }
    FujiExif.parse(bytes)?.let { exif ->
        return@runCatching exif.copy(
            photos = listOf(withContext(Dispatchers.IO) { repo.addPhoto(uri) }),
        )
    }

    val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, uri) }
    val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        .process(image)
        .await()
    // Cards put labels and values in two columns; rebuild the pairs
    // from the boxes rather than trusting the flattened reading order.
    val blocks = ocr.textBlocks.mapNotNull { b ->
        b.boundingBox?.let { OcrBlock(b.text, it.left, it.top, it.right, it.bottom) }
    }
    val recipe = FujiStyleCard.parse(OcrText.prepare(blocks, image.width, ocr.text))
        ?: return@runCatching null
    // Keep just the example photo from the card; a gray-placeholder
    // card yields null → no photo, recipe shows its gradient tile.
    val photo = withContext(Dispatchers.IO) {
        CardCrop.extractPhoto(context, uri)?.let { repo.addPhotoBytes(it) }
    }
    recipe.copy(photos = listOfNotNull(photo))
}.getOrNull()

/** ponytail: the four lines of kotlinx-coroutines-play-services we actually use. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

/** "3 imported" / "3 imported, 2 already in your library". */
internal fun importMessage(context: Context, r: RecipeRepository.ImportResult): String = when {
    r.duplicates == 0 -> context.getString(R.string.import_done, r.added)
    r.added == 0 -> context.resources.getQuantityString(
        R.plurals.import_all_duplicates, r.duplicates, r.duplicates,
    )
    else -> context.getString(R.string.import_done_dupes, r.added, r.duplicates)
}

/**
 * "Import failed: Fields [recipes] are required" rather than a bare "invalid file".
 * The parser's own wording is the only thing that says *which* part of the file is
 * wrong; it stays in English on purpose, it is diagnostic text meant to be reported.
 */
internal fun importFailure(context: Context, t: Throwable): String {
    val reason = t.message?.lineSequence()?.firstOrNull()?.trim()?.take(140)
    return if (reason.isNullOrEmpty()) context.getString(R.string.import_failed)
    else context.getString(R.string.import_failed_reason, reason)
}
