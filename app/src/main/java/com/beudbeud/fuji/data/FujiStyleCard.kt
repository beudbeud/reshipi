package com.beudbeud.fuji.data

import com.beudbeud.fuji.model.DRangePriority
import com.beudbeud.fuji.model.DynamicRange
import com.beudbeud.fuji.model.FilmSimulation
import com.beudbeud.fuji.model.Generation
import com.beudbeud.fuji.model.GrainSize
import com.beudbeud.fuji.model.Recipe
import com.beudbeud.fuji.model.Strength
import com.beudbeud.fuji.model.WhiteBalance
import kotlin.math.roundToInt

/**
 * Parses recipe text: FujiStyle card OCR ("Key: value | Key: value") or
 * pasted Fuji X Weekly-style lines ("Grain Effect: Strong, Small",
 * "White Balance: 5700K, +1 Red & +1 Blue"). Each field is matched
 * independently so lost pipes or OCR line breaks don't derail the parse.
 */
object FujiStyleCard {

    // Key/value separator: colon, or a dash surrounded by spaces ("Sharpness - -3").
    // A dash glued to a digit is a minus sign ("Highlights -1"), not a separator.
    private const val SEP = "\\s*(?::|\\s[–-]\\s)\\s*"

    /** Optional "[...]"/"(...)" qualifier some sites put between key and separator. */
    private const val QUALIFIER = "(?:\\s*[\\[(][^\\])\\n]{0,40}[\\])])?"

    // "Film Simulation", "Base Film Simulation", the abbreviated "Film Sim", or
    // "StartWith:" (recipes phrased as "start from X, then change these")
    private const val SIM_KEY_PAT = "(?:(?:Base\\s+)?Film\\s+Sim(?:ulation)?|Start\\s*With)"
    internal val SIM_KEY = Regex("$SIM_KEY_PAT$SEP", RegexOption.IGNORE_CASE)

    /**
     * French pages label the same settings in French. Rather than doubling every
     * pattern below, the keys are rewritten to English first. The lookahead keeps
     * this to key position: a white balance *value* of "Ombre" must stay Shade,
     * not turn into the Shadows field. Order matters — the longer keys that
     * contain a shorter one ("Décalage de la balance des blancs") come first.
     */
    private val FR_KEYS = listOf(
        "D[ée]calage\\s+(?:de\\s+)?(?:la\\s+)?balance\\s+des\\s+blancs" to "White Balance Shift",
        "Priorit[ée]\\s+(?:de\\s+)?(?:la\\s+)?(?:gamme|plage)\\s+dynamique" to "DR Priority",
        "(?:Gamme|Plage)\\s+dynamique" to "Dynamic Range",
        "R[ée]duction\\s+du\\s+bruit" to "Noise Reduction",
        // The app writes "Correction"; other sources write "Compensation"
        "(?:Compensation|Correction)\\s+d['’]exposition" to "Exposure Compensation",
        // Fuji leaves "Color Chrome" in English even in French, so only the
        // surrounding words move — and the blue variant comes first, being the
        // longer key. Without these, sharing a recipe in French and importing it
        // back lost both effects.
        "Colou?r\\s+Chrome\\s+FX\\s+Bleu" to "Color Chrome FX Blue",
        "Effet\\s+Colou?r\\s+Chrome" to "Color Chrome Effect",
        "Balance\\s+des\\s+blancs" to "White Balance",
        "Effet\\s+de\\s+grain" to "Grain Effect",
        "Taille\\s+d[eu]\\s*(?:la\\s+)?grain" to "Grain Size",
        "Simulation\\s+de\\s+(?:base|film)" to "Film Simulation",
        "(?:Hautes?\\s+lumi[eè]res?|Tons?\\s+clairs?|Surbrillance)" to "Highlights",
        "(?:Tons?\\s+sombres?|Ombres?)" to "Shadows",
        "Couleur" to "Color",
        "Nettet[ée]" to "Sharpness",
        "Clart[ée]" to "Clarity",
    ).map { (fr, en) -> Regex("(?i)\\b$fr(?=$SEP)") to en }

    private fun toEnglishKeys(text: String): String =
        FR_KEYS.fold(text) { acc, (pattern, english) -> pattern.replace(acc, english) }

    /**
     * Parses every recipe in the text: pages listing several recipes are split
     * at each "Film Simulation" declaration and parsed independently. Recipes
     * without a name get one from the nearest preceding heading when possible.
     */
    fun parseAll(raw: String, tag: String = "fujistyle"): List<Recipe> {
        val text = toEnglishKeys(raw)
        val starts = SIM_KEY.findAll(text).map { it.range.first }.toList()
        if (starts.size <= 1) return listOfNotNull(parse(text, tag))
        val recipes = mutableListOf<Recipe>()
        for ((i, start) in starts.withIndex()) {
            val end = if (i + 1 < starts.size) starts[i + 1] else text.length
            val recipe = parse(text.substring(start, end), tag) ?: continue
            val heading = headingBefore(text, start)
            recipes += if (recipe.name.isBlank() && heading != null) recipe.copy(name = heading) else recipe
        }
        return recipes
    }

    /** Best-effort recipe name: an explicit "[Name: X]" tag, else the nearest
     *  preceding short line that isn't a setting. */
    private fun headingBefore(text: String, offset: Int): String? {
        val before = text.take(offset)
        Regex("\\[?\\s*Name\\s*:\\s*([^\\]\\n]+)", RegexOption.IGNORE_CASE)
            .findAll(before).lastOrNull()
            ?.groupValues?.get(1)?.trim()?.trim(']')?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 60 }
            ?.let { return it }
        return before.lines().map { it.trim() }
            .lastOrNull { line ->
                line.length in 3..60 && ':' !in line && '|' !in line &&
                    line.count { it.isLetter() } >= 3 &&
                    line.lowercase() !in setOf("setting", "value") &&
                    detectSim(line) == null
            }
            ?.replace(Regex("^\\d+[.)]\\s*"), "")
            ?.replace(Regex("(?i)\\s*(jpeg\\s*)?settings$"), "")
            ?.trim()?.takeIf { it.isNotBlank() }
    }

    fun parse(raw: String, tag: String = "fujistyle"): Recipe? {
        // Idempotent: text already translated by parseAll has no French keys left
        val text = toEnglishKeys(raw)
        // A key may carry a parenthesized/bracketed qualifier before the
        // separator: "Grain [effect, Size] :", "Tone Curve (Highlights/Shadows):"
        fun field(keyPattern: String): String? =
            Regex("$keyPattern$QUALIFIER$SEP([^|\\n]+)", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.trim()

        fun num(keyPattern: String): Double? =
            field(keyPattern)?.let { Regex("-?\\d+(\\.\\d+)?").find(it)?.value?.toDoubleOrNull() }

        // Some pages list the simulation twice (base then refined variant,
        // e.g. "Monochrome" then "Monochrome+ Ye Filter") — keep the last one.
        val sim = Regex("$SIM_KEY_PAT$SEP([^|\\n]+)", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNull { detectSim(it.groupValues[1]) }
            .lastOrNull() ?: return null

        // Shift colours live inside the value ("Décalage : Rouge -3, Bleu +4"),
        // where the key translation above can't reach them.
        fun frenchColours(s: String) = s.replace("ROUGE", "RED").replace("BLEU", "BLUE")
        val wbRaw = frenchColours((field("White\\s+Balance") ?: "").uppercase())
        // "5700K" or the prefix form "K7000"
        val kelvinMatch = (Regex("(\\d{4,5})\\s*K").find(wbRaw)
            ?: Regex("\\bK\\s*(\\d{4,5})").find(wbRaw))?.groupValues?.get(1)?.toIntOrNull()
        // Shifts as their own fields ("Red: -6"), inside the WB value
        // ("+1 Red & +1 Blue"), or in a "White Balance Shift: R +2, B -2" field
        val shiftRaw = frenchColours((field("White\\s+Balance\\s+Shift") ?: "").uppercase())
        fun wbShift(color: String): Int? =
            Regex("([+-]?\\d+)\\s*$color").find(wbRaw)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("$color\\s*:?\\s*([+-]?\\d+)").find(wbRaw)?.groupValues?.get(1)?.toIntOrNull()
                // single letters: "White Balance Shift: R +2, B -2" or "Auto (R: +1, B: -2)"
                ?: Regex("\\b${color.first()}\\s*:?\\s*([+-]?\\d+)").find("$shiftRaw $wbRaw")
                    ?.groupValues?.get(1)?.toIntOrNull()

        // "Tone Curve: Highlights -1, Shadows 0", abbreviated "H:-2, S:-2" or
        // "H -2, S +3" — the colon is optional, the value is already isolated
        val toneCurve = field("Tone\\s+Curve") ?: ""
        fun curve(part: String): Double? =
            Regex("$part\\w*\\s*:?\\s*([+-]?\\d+(\\.\\d+)?)", RegexOption.IGNORE_CASE)
                .find(toneCurve)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: Regex("\\b${part.first()}\\s*:?\\s*([+-]?\\d+(\\.\\d+)?)", RegexOption.IGNORE_CASE)
                    .find(toneCurve)?.groupValues?.get(1)?.toDoubleOrNull()
        val whiteBalance = when {
            kelvinMatch != null || "KELVIN" in wbRaw -> WhiteBalance.KELVIN
            "DAYLIGHT" in wbRaw || "JOUR" in wbRaw || "SOLEIL" in wbRaw -> WhiteBalance.DAYLIGHT
            "SHADE" in wbRaw || "OMBRE" in wbRaw -> WhiteBalance.SHADE
            "FLUORESCENT" in wbRaw -> when {
                "3" in wbRaw -> WhiteBalance.FLUORESCENT_3
                "2" in wbRaw -> WhiteBalance.FLUORESCENT_2
                else -> WhiteBalance.FLUORESCENT_1
            }
            "INCANDESCENT" in wbRaw -> WhiteBalance.INCANDESCENT
            "UNDERWATER" in wbRaw || "SOUS-MARIN" in wbRaw -> WhiteBalance.UNDERWATER
            else -> WhiteBalance.AUTO
        }

        // "Dynamic Range", or the abbreviated "DR:" (never "DR Priority")
        val drRaw = field("Dynamic\\s+Range") ?: field("\\bDR(?!\\s*Priority)") ?: ""

        // "DR Priority", "D-Range Priority", or spelled out "D Range Priority"
        val prioRaw = field("D[-\\s]?R(?:ange)?\\s*Priority")

        // Color Chrome keys, resolved once: some sites spell out "Effect" and use
        // a bare "FX" for the blue variant, others use only one of the two.
        val chromeEffect = field("(?:Colou?r\\s+)?Chrome\\s+Effect(?!\\s*Blue)")
        val chromeFxNoBlue = field("(?:Colou?r\\s+)?Chrome\\s+FX(?!\\s*Blue)")
        val dynamicRange = when {
            "400" in drRaw -> DynamicRange.DR400
            "200" in drRaw -> DynamicRange.DR200
            "100" in drRaw -> DynamicRange.DR100
            else -> DynamicRange.AUTO
        }

        val name = Regex("#\\s*([^\\n|#]+)").find(text)?.groupValues?.get(1)?.trim() ?: ""

        return Recipe(
            name = name,
            generation = Generation.X_TRANS_V,
            filmSimulation = sim,
            whiteBalance = whiteBalance,
            kelvin = kelvinMatch,
            wbShiftRed = (num("Red")?.roundToInt() ?: wbShift("RED") ?: 0).coerceIn(-9, 9),
            wbShiftBlue = (num("Blue")?.roundToInt() ?: wbShift("BLUE") ?: 0).coerceIn(-9, 9),
            dynamicRange = dynamicRange,
            dRangePriority = when (strength(prioRaw)) {
                Strength.WEAK -> DRangePriority.WEAK
                Strength.STRONG -> DRangePriority.STRONG
                else -> if ((prioRaw ?: "").contains("auto", true)) DRangePriority.AUTO
                else DRangePriority.OFF
            },
            highlight = (num("Highlights?") ?: curve("Highlight") ?: 0.0).halfSteps().coerceIn(-2.0, 4.0),
            shadow = (num("Shadows?") ?: curve("Shadow") ?: 0.0).halfSteps().coerceIn(-2.0, 4.0),
            // The lookbehind keeps "Monochromatic Color: N/A" from stealing the
            // Color field on pages that list both.
            color = (num("(?<![A-Za-z] )Colou?r")?.roundToInt() ?: 0).coerceIn(-4, 4),
            sharpness = ((num("Sharpness") ?: num("Sharpening"))?.roundToInt() ?: 0).coerceIn(-4, 4),
            // "High ISO NR", "Noise Reduction", or the abbreviated "ISO N.R."
            noiseReduction = ((num("High\\s+ISO\\s+NR") ?: num("Noise\\s+Reduction")
                ?: num("ISO\\s*N\\.?\\s*R\\.?"))?.roundToInt() ?: 0).coerceIn(-4, 4),
            // Combined form "Grain Effect: Strong, Small", split Roughness/Size
            // fields, or bare "Grain: Off"
            grainEffect = strength(
                field("Grain\\s+Effect\\s*-?\\s*Roughness") ?: field("Grain\\s+Effect")
                    ?: field("Grain")
            ),
            grainSize = if (isLarge(
                    field("Grain\\s+(?:Effect\\s*-?\\s*)?Size") ?: field("Grain\\s+Effect")
                        ?: field("Grain")
                )) GrainSize.LARGE else GrainSize.SMALL,
            // "Color Chrome Effect", bare "Chrome Effect", or "Col. Chr. Effect".
            // "Color Chrome FX" (no Blue) only counts as the effect when the page
            // has no explicit Effect key — some sites use it to mean FX Blue.
            colorChromeEffect = strength(
                chromeEffect ?: field("Col\\.?\\s*Chr\\.?\\s*Effect") ?: chromeFxNoBlue
            ),
            // "Color FX Blue", "Color Chrome FX Blue", "Color Chrome Effect Blue",
            // "Color chrome blue", "Col. Chr. Blue" — or a bare "Color Chrome FX"
            // when the page already spelled out the Effect key separately.
            colorChromeFxBlue = strength(
                field("Colou?r\\s+(?:Chrome\\s+)?(?:FX\\s+|Effect\\s+)?Blue")
                    ?: field("Col\\.?\\s*Chr\\.?\\s*Blue")
                    ?: chromeFxNoBlue.takeIf { chromeEffect != null }
            ),
            clarity = (num("Clarity")?.roundToInt() ?: 0).coerceIn(-5, 5),
            // ISO is the only free-text field: when empty at the end of the block,
            // the regex can swallow the "Made with FUJISTYLE APP" footer line.
            iso = field("ISO")
                ?.takeIf { it.isNotBlank() && !it.contains("fujistyle", true) && !it.contains("made with", true) }
                ?: "Auto",
            exposureCompensation = (field("Exp(?:osure|\\.)?\\s*Comp(?:ensation|\\.)?")
                ?: field("EV\\s*Comp\\.?"))?.takeIf { it.isNotBlank() } ?: "0",
            tags = listOf(tag),
        )
    }

    private fun Double.halfSteps(): Double = (this * 2).roundToInt() / 2.0

    // FujiStyle localizes strength/size words (the app follows the phone's
    // language) while keeping English keys — e.g. German "Schwach"/"Klein".
    private val STRONG_WORDS = listOf("strong", "stark", "fort", "강")
    private val WEAK_WORDS = listOf("weak", "schwach", "faible", "약")
    private val LARGE_WORDS = listOf("large", "groß", "gross", "grand", "grande", "big")

    private fun strength(raw: String?): Strength = when {
        raw == null -> Strength.OFF
        STRONG_WORDS.any { raw.contains(it, true) } -> Strength.STRONG
        WEAK_WORDS.any { raw.contains(it, true) } -> Strength.WEAK
        else -> Strength.OFF // None / Off / Aus / Désactivé
    }

    private fun isLarge(raw: String?): Boolean =
        raw != null && LARGE_WORDS.any { raw.contains(it, true) }

    /** Recognizes a film simulation name inside free text (used for bare-line detection too). */
    internal fun detectSim(raw: String): FilmSimulation? {
        val n = raw.uppercase()
        val filter = Regex("(ACROS|MONOCHROME)\\s*\\+?\\s*(YE|R|G)\\b").find(n)?.groupValues?.get(2)
        return when {
            "ETERNA" in n && "BLEACH" in n -> FilmSimulation.ETERNA_BLEACH_BYPASS
            "ETERNA" in n -> FilmSimulation.ETERNA
            "CLASSIC CHROME" in n -> FilmSimulation.CLASSIC_CHROME
            "CLASSIC NEG" in n -> FilmSimulation.CLASSIC_NEG
            "NOSTALGIC" in n -> FilmSimulation.NOSTALGIC_NEG
            "REALA" in n -> FilmSimulation.REALA_ACE
            "PRO NEG" in n || "PRO NEG." in n ->
                if ("HI" in n) FilmSimulation.PRO_NEG_HI else FilmSimulation.PRO_NEG_STD
            "ACROS" in n -> when (filter) {
                "YE" -> FilmSimulation.ACROS_YE
                "R" -> FilmSimulation.ACROS_R
                "G" -> FilmSimulation.ACROS_G
                else -> FilmSimulation.ACROS
            }
            "MONOCHROME" in n -> when (filter) {
                "YE" -> FilmSimulation.MONOCHROME_YE
                "R" -> FilmSimulation.MONOCHROME_R
                "G" -> FilmSimulation.MONOCHROME_G
                else -> FilmSimulation.MONOCHROME
            }
            "SEPIA" in n -> FilmSimulation.SEPIA
            "VELVIA" in n -> FilmSimulation.VELVIA
            "ASTIA" in n -> FilmSimulation.ASTIA
            "PROVIA" in n -> FilmSimulation.PROVIA
            else -> null
        }
    }
}
