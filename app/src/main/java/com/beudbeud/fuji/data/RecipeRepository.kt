package com.beudbeud.fuji.data

import android.content.Context
import android.net.Uri
import com.beudbeud.fuji.model.Recipe
import com.beudbeud.fuji.model.RecipeExport
import com.beudbeud.fuji.model.mergeRecipes
import com.beudbeud.fuji.model.settingsKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class RecipeRepository(private val context: Context) {
    private val file = File(context.filesDir, "recipes.json")
    private val photosDir = File(context.filesDir, "photos").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private val _recipes = MutableStateFlow(load())
    val recipes: StateFlow<List<Recipe>> = _recipes

    init {
        // Deletes photo files no recipe references (cancelled edits, deleted recipes).
        val referenced = _recipes.value.flatMap { it.photos }.toSet()
        photosDir.listFiles()?.forEach { if (it.name !in referenced) it.delete() }
    }

    private fun load(): List<Recipe> = runCatching {
        json.decodeFromString<RecipeExport>(file.readText()).recipes
    }.getOrDefault(emptyList())

    // ponytail: synchronous whole-file rewrite on the caller thread — a few KB, fine
    // for hundreds of recipes; move to Dispatchers.IO if the file ever gets big.
    private fun persist(list: List<Recipe>) {
        _recipes.value = list
        file.writeText(json.encodeToString(RecipeExport(recipes = list)))
    }

    fun upsert(recipe: Recipe) {
        persist(_recipes.value.filter { it.id != recipe.id } +
            recipe.copy(updatedAt = System.currentTimeMillis()))
    }

    fun delete(id: String) {
        _recipes.value.find { it.id == id }?.photos?.forEach { photoFile(it).delete() }
        persist(_recipes.value.filter { it.id != id })
    }

    fun toggleFavorite(id: String) {
        persist(_recipes.value.map { if (it.id == id) it.copy(favorite = !it.favorite) else it })
    }

    fun photoFile(name: String): File = File(photosDir, name)

    /** Copies the picked image into app storage, returns the stored filename. */
    fun addPhoto(uri: Uri): String {
        val name = UUID.randomUUID().toString() + ".jpg"
        context.contentResolver.openInputStream(uri)!!.use { input ->
            photoFile(name).outputStream().use { input.copyTo(it) }
        }
        return name
    }

    /** Stores an in-memory JPEG (e.g. an in-camera render), returns the filename. */
    fun addPhotoBytes(bytes: ByteArray): String {
        val name = UUID.randomUUID().toString() + ".jpg"
        photoFile(name).writeBytes(bytes)
        return name
    }

    fun exportJson(): String = json.encodeToString(RecipeExport(recipes = _recipes.value))

    /** Merges by id, returns the number of recipes added or updated. */
    fun importJson(text: String): Int {
        val incoming = json.decodeFromString<RecipeExport>(text).recipes
            // photo filenames from another device are meaningless — keep only local files
            .map { r -> r.copy(photos = r.photos.filter { photoFile(it).exists() }) }
        return merge(incoming)
    }

    private fun merge(incoming: List<Recipe>): Int {
        val before = _recipes.value.associateBy { it.id }
        // Same settings under a different id: someone else's copy of a recipe we
        // already have. Merging by id alone would shelve it as a second entry.
        val mine = _recipes.value.associateBy { it.settingsKey }
        val fresh = incoming.filter { mine[it.settingsKey]?.id?.equals(it.id) != false }
        val merged = mergeRecipes(_recipes.value, fresh)
        persist(merged)
        return merged.count { before[it.id] != it }
    }

    /** The recipe already holding these settings, if any — ignoring [recipe] itself. */
    fun duplicateOf(recipe: Recipe): Recipe? =
        _recipes.value.firstOrNull { it.settingsKey == recipe.settingsKey && it.id != recipe.id }

    /**
     * Adds recipes that arrived with fresh ids (web pages, cards, camera slots).
     * Those ids are new every time, so identical settings are the only way to
     * recognise something already in the library.
     */
    fun addImported(incoming: List<Recipe>): ImportResult {
        val seen = _recipes.value.map { it.settingsKey }.toMutableSet()
        val added = mutableListOf<Recipe>()
        for (r in incoming) {
            if (seen.add(r.settingsKey)) added += r.copy(updatedAt = System.currentTimeMillis())
        }
        if (added.isNotEmpty()) persist(_recipes.value + added)
        return ImportResult(added = added.size, duplicates = incoming.size - added.size)
    }

    data class ImportResult(val added: Int, val duplicates: Int)

    // -- Full backup: recipes.json + the photos they reference, in one archive --

    /** Writes a complete backup (recipes and their photos) as a ZIP. */
    fun exportZip(out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(BACKUP_JSON))
            zip.write(exportJson().toByteArray())
            zip.closeEntry()
            for (name in _recipes.value.flatMap { it.photos }.distinct()) {
                val file = photoFile(name)
                if (!file.exists()) continue
                zip.putNextEntry(ZipEntry("$PHOTO_DIR/$name"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Restores a ZIP backup: photos are written to app storage first so the
     * recipes that reference them keep their illustrations.
     */
    fun importZip(input: InputStream): Int {
        var recipesJson: String? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                when {
                    entry.isDirectory || name.isEmpty() -> Unit
                    entry.name == BACKUP_JSON -> recipesJson = zip.readBytes().decodeToString()
                    // Flatten to a bare filename: never let an archive path escape photosDir
                    entry.name.startsWith("$PHOTO_DIR/") -> photoFile(name).writeBytes(zip.readBytes())
                }
                zip.closeEntry()
            }
        }
        val text = recipesJson ?: throw IllegalArgumentException("no $BACKUP_JSON in archive")
        val incoming = json.decodeFromString<RecipeExport>(text).recipes
            .map { r -> r.copy(photos = r.photos.filter { photoFile(it).exists() }) }
        return merge(incoming)
    }

    private companion object {
        const val BACKUP_JSON = "recipes.json"
        const val PHOTO_DIR = "photos"
    }
}
