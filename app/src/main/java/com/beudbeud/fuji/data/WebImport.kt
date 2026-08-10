package com.beudbeud.fuji.data

import com.beudbeud.fuji.model.Generation
import com.beudbeud.fuji.model.Recipe
import java.net.HttpURLConnection
import java.net.URL

/**
 * Imports a recipe from a web page (Fuji X Weekly and compatible layouts):
 * settings parsed from the page text via FujiStyleCard, name from og:title,
 * og:image downloaded as the example photo. Blocking — call on Dispatchers.IO.
 */
object WebImport {

    fun fetch(url: String, repo: RecipeRepository): Recipe? = runCatching {
        DebugLog.log("web import: $url")
        val html = get(url) ?: return null
        val text = ensureFilmSimulationKey(pairKeyValueLines(htmlToText(html)))
        val recipe = FujiStyleCard.parse(text, tag = "fujixweekly") ?: return null

        val title = Regex("property=\"og:title\" content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        val name = title?.substringAfterLast("Recipe:")?.trim()?.takeIf { it.isNotBlank() }
            ?: recipe.name.ifBlank { "Import" }
        val gen = Regex("X-Trans (V|IV|III|II|I)\\b").find(title ?: "")?.groupValues?.get(1)?.let {
            when (it) {
                "I" -> Generation.X_TRANS_I
                "II" -> Generation.X_TRANS_II
                "III" -> Generation.X_TRANS_III
                "IV" -> Generation.X_TRANS_IV
                else -> Generation.X_TRANS_V
            }
        }
        val photo = Regex("property=\"og:image\" content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?.let { imageUrl -> runCatching { getBytes(imageUrl)?.let { repo.addPhotoBytes(it) } }.getOrNull() }

        recipe.copy(
            name = name,
            generation = gen ?: recipe.generation,
            photos = listOfNotNull(photo),
        ).also { DebugLog.log("web import ok: \"${it.name}\" photo=${photo != null}") }
    }.onFailure { DebugLog.log("web import failed: ${it.message}") }.getOrNull()

    private fun get(url: String): String? = getBytes(url)?.toString(Charsets.UTF_8)

    /** GET with a browser UA; null unless HTTP 200 and body ≤ 8MB. */
    private fun getBytes(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Reshipi")
        return try {
            if (conn.responseCode != 200) null
            else conn.inputStream.use { it.readBytes() }.takeIf { it.size <= 8 shl 20 }
        } finally {
            conn.disconnect()
        }
    }

    internal fun htmlToText(html: String): String {
        var t = html.replace(
            Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " "
        )
        t = t.replace(Regex("(?i)<br ?/?>"), "\n")
            .replace(Regex("(?i)</(li|p|h[1-6]|div|tr)>"), "\n")
            .replace(Regex("<[^>]+>"), "")
        return t.replace("&amp;", "&").replace("&nbsp;", " ")
            .replace("&#8211;", "-").replace("&#8217;", "'")
            .replace("&#8220;", "\"").replace("&#8221;", "\"")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
    }

    // Setting names that some sites put on their own line (heading) with the
    // value on the following line — reassembled into "Key: value".
    private val KNOWN_KEYS = listOf(
        "grain effect / grain size", "color chrome effect / fx blue", "wb / color temperature",
        "film simulation", "dynamic range", "white balance shift", "white balance",
        "highlight", "shadow", "colour", "color chrome effect", "color chrome fx blue",
        "color", "sharpness", "sharpening", "noise reduction", "high iso nr",
        "grain effect", "grain size", "clarity", "iso", "exposure compensation",
        "dr priority", "d-range priority", "monochromatic color",
    )

    /**
     * Generic pass for heading/value layouts (Elementor cards, tables): when a
     * line is exactly a known setting name and the next non-blank line looks
     * like a value, join them as "Key: value". Combined keys ("Grain Effect /
     * Grain Size: Strong / Small") are split into the parser's vocabulary.
     */
    internal fun pairKeyValueLines(text: String): String {
        val lines = text.lines().map { it.trim() }
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val key = if (':' !in line) KNOWN_KEYS.firstOrNull { line.equals(it, true) } else null
            var j = i + 1
            while (j < lines.size && lines[j].isBlank()) j++
            val value = lines.getOrNull(j)
            if (key != null && value != null && value.length < 60 && ':' !in value &&
                KNOWN_KEYS.none { value.equals(it, true) }
            ) {
                val parts = value.split('/').map(String::trim)
                when (key) {
                    "grain effect / grain size" -> {
                        out.appendLine("Grain Effect: ${parts.getOrElse(0) { "" }}")
                        out.appendLine("Grain Effect - Size: ${parts.getOrElse(1) { "" }}")
                    }
                    "color chrome effect / fx blue" -> {
                        out.appendLine("Color Chrome Effect: ${parts.getOrElse(0) { "" }}")
                        out.appendLine("Color FX Blue: ${parts.getOrElse(1) { "" }}")
                    }
                    "wb / color temperature" -> out.appendLine("White Balance: $value")
                    else -> out.appendLine("$line: $value")
                }
                i = j + 1
            } else {
                out.appendLine(line)
                i++
            }
        }
        return out.toString()
    }

    /**
     * Fuji X Weekly lists the film simulation as a bare line ("Classic Chrome")
     * above the settings. If no "Film Simulation:" key exists, find it near the
     * settings block and prepend it as a proper key so the parser picks it up.
     */
    internal fun ensureFilmSimulationKey(text: String): String {
        if (Regex("Film\\s+Simulation\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(text)) return text
        val lines = text.lines().map { it.trim() }
        val anchor = lines.indexOfFirst {
            it.contains("Dynamic Range", true) || it.contains("Grain Effect", true)
        }
        if (anchor <= 0) return text
        for (i in (maxOf(0, anchor - 6) until anchor).reversed()) {
            if (!lines[i].contains(":") && FujiStyleCard.detectSim(lines[i]) != null) {
                return "Film Simulation: ${lines[i]}\n$text"
            }
        }
        return text
    }
}
