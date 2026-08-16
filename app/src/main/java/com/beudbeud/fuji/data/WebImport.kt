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

    fun fetch(url: String, repo: RecipeRepository): List<Recipe> = runCatching {
        DebugLog.log("web import: $url")
        val html = get(url) ?: return emptyList()
        val text = ensureFilmSimulationKey(pairKeyValueLines(htmlToText(html)))
        val recipes = FujiStyleCard.parseAll(text, tag = "fujixweekly")
        if (recipes.isEmpty()) return emptyList()

        val title = Regex("property=\"og:title\" content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: Regex("<title>([^<|–—-]+)").find(html)?.groupValues?.get(1)?.trim()
        val pageName = title?.substringAfterLast("Recipe:")?.trim()?.takeIf { it.isNotBlank() }
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

        recipes.mapIndexed { i, recipe ->
            recipe.copy(
                // single recipe: page title wins; multi: keep per-recipe headings
                name = if (recipes.size == 1) (pageName ?: recipe.name.ifBlank { "Import" })
                else recipe.name.ifBlank { "${pageName ?: "Import"} ${i + 1}" },
                generation = gen ?: recipe.generation,
                photos = if (i == 0) listOfNotNull(photo) else emptyList(),
            )
        }.also { DebugLog.log("web import ok: ${it.size} recipe(s), photo=${photo != null}") }
    }.onFailure { DebugLog.log("web import failed: ${it.message}") }.getOrDefault(emptyList())

    private const val MAX_BODY = 8 shl 20

    private fun get(url: String): String? = getBytes(url)?.toString(Charsets.UTF_8)

    /**
     * GET with a browser UA; null unless HTTP 200 and body ≤ 8MB.
     *
     * The URL is not ours: the page is shared by the user and the og:image it
     * names is chosen by whoever wrote the page. So the scheme is checked rather
     * than assumed, and the body is capped while reading — reading it whole and
     * measuring afterwards is no cap at all.
     */
    private fun getBytes(url: String): ByteArray? {
        val parsed = URL(url)
        if (parsed.protocol !in setOf("http", "https")) return null
        val conn = parsed.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Reshipi")
        return try {
            if (conn.responseCode != 200) null else conn.inputStream.use { readCapped(it) }
        } finally {
            conn.disconnect()
        }
    }

    /** Everything the stream holds, or null once it exceeds [MAX_BODY]. */
    internal fun readCapped(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > MAX_BODY) return null
            out.write(buf, 0, n)
        }
    }

    internal fun htmlToText(html: String): String {
        var t = html.replace(
            Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " "
        )
        t = t.replace(Regex("(?i)<br ?/?>"), "\n")
            .replace(Regex("(?i)</(li|p|h[1-6]|div|tr)>"), "\n")
            .replace(Regex("<[^>]+>"), "")
        // Decode numeric entities generically — sites use many (&#8209; is a
        // non-breaking hyphen standing in for a minus sign).
        t = Regex("&#(x?)([0-9a-fA-F]+);").replace(t) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        t = t.replace("&amp;", "&").replace("&nbsp;", " ")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'")
        // Typographic dashes used as minus signs (non-breaking hyphen, figure
        // dash, en dash, true minus) — normalize so "Shadows‑1" parses as -1.
        return t.replace('‑', '-').replace('‒', '-')
            .replace('–', '-').replace('−', '-')
    }

    // Setting names that some sites put on their own line (heading) with the
    // value on the following line — reassembled into "Key: value".
    private val KNOWN_KEYS = listOf(
        "grain effect / grain size", "color chrome effect / fx blue", "wb / color temperature",
        "base film simulation", "film simulation", "film sim", "dynamic range",
        "white balance shift", "white balance", "wb shift red", "wb shift blue",
        "highlights", "highlight", "shadows", "shadow", "colour",
        "color chrome effect", "color chrome fx blue",
        "col. chr. effect", "col. chr. blue",
        "color", "sharpness", "sharpening", "noise reduction", "high iso nr",
        "iso n.r.", "ev comp.", "tone curve",
        "grain effect", "grain size", "clarity", "iso", "exposure compensation",
        "dr priority", "d-range priority", "monochromatic color", "monochromatic colour",
    ).sortedByDescending { it.length } // longest-first so "film sim" can't shadow "film simulation"

    /**
     * Generic pass for heading/value layouts (Elementor cards, tables): when a
     * line is exactly a known setting name and the next non-blank line looks
     * like a value, join them as "Key: value". Combined keys ("Grain Effect /
     * Grain Size: Strong / Small") are split into the parser's vocabulary.
     */
    /**
     * Table layouts that render key and value in adjacent cells can collapse to
     * a single run with no separator ("Film SimulationClassic Chrome",
     * "Highlights1"). Split those back into "Key: value".
     */
    internal fun splitGluedKeyValues(text: String): String =
        text.lines().joinToString("\n") { raw ->
            val line = raw.trim()
            if (line.length > 60 || ':' in line || '|' in line) return@joinToString raw
            val key = KNOWN_KEYS.firstOrNull { k ->
                line.length > k.length && line.regionMatches(0, k, 0, k.length, ignoreCase = true) &&
                    // The glued value starts right where the key ends: a digit, a
                    // sign, or a capital. A space or lowercase letter means we cut
                    // inside a longer key ("Film Sim|ulation", "Color| Chrome Effect").
                    line[k.length].let { it.isDigit() || it == '-' || it == '+' || it.isUpperCase() }
            } ?: return@joinToString raw
            val value = line.substring(key.length).trim()
            if (value.isEmpty() || value.length > 40) raw
            else "${line.substring(0, key.length)}: $value"
        }

    internal fun pairKeyValueLines(text: String): String {
        val lines = splitGluedKeyValues(text).lines().map { it.trim() }
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
        if (FujiStyleCard.SIM_KEY.containsMatchIn(text)) return text
        val lines = text.lines().map { it.trim() }
        // Anchor on actual settings-key lines (line start + separator), not prose
        // mentions of "dynamic range"; try every anchor until one yields a sim.
        val anchorRegex = Regex(
            "^(?:Dynamic\\s+Range|Grain(?:\\s+Effect)?|Highlights?|White\\s+Balance)\\s*(?::|\\s[–-]\\s)",
            RegexOption.IGNORE_CASE,
        )
        for (anchor in lines.indices) {
            if (anchor == 0 || !anchorRegex.containsMatchIn(lines[anchor])) continue
            for (i in (maxOf(0, anchor - 6) until anchor).reversed()) {
                if (!lines[i].contains(":") && FujiStyleCard.detectSim(lines[i]) != null) {
                    return "Film Simulation: ${lines[i]}\n$text"
                }
            }
        }
        return text
    }
}
