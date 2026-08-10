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

    fun parse(text: String, tag: String = "fujistyle"): Recipe? {
        fun field(keyPattern: String): String? =
            Regex("$keyPattern\\s*:\\s*([^|\\n]+)", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.trim()

        fun num(keyPattern: String): Double? =
            field(keyPattern)?.let { Regex("-?\\d+(\\.\\d+)?").find(it)?.value?.toDoubleOrNull() }

        val sim = field("Film\\s+Simulation")?.let { detectSim(it) } ?: return null

        val wbRaw = (field("White\\s+Balance") ?: "").uppercase()
        val kelvinMatch = Regex("(\\d{4,5})\\s*K").find(wbRaw)?.groupValues?.get(1)?.toIntOrNull()
        // Shifts either as their own fields ("Red: -6") or inside the WB value
        // ("+1 Red & +1 Blue" / "Red +2, Blue -2")
        fun wbShift(color: String): Int? =
            Regex("([+-]?\\d+)\\s*$color").find(wbRaw)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("$color\\s*:?\\s*([+-]?\\d+)").find(wbRaw)?.groupValues?.get(1)?.toIntOrNull()
        val whiteBalance = when {
            kelvinMatch != null || "KELVIN" in wbRaw -> WhiteBalance.KELVIN
            "DAYLIGHT" in wbRaw -> WhiteBalance.DAYLIGHT
            "SHADE" in wbRaw -> WhiteBalance.SHADE
            "FLUORESCENT" in wbRaw -> when {
                "3" in wbRaw -> WhiteBalance.FLUORESCENT_3
                "2" in wbRaw -> WhiteBalance.FLUORESCENT_2
                else -> WhiteBalance.FLUORESCENT_1
            }
            "INCANDESCENT" in wbRaw -> WhiteBalance.INCANDESCENT
            "UNDERWATER" in wbRaw -> WhiteBalance.UNDERWATER
            else -> WhiteBalance.AUTO
        }

        val drRaw = field("Dynamic\\s+Range") ?: ""
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
            dRangePriority = strength(field("DR\\s+Priority")).let {
                when (it) {
                    Strength.WEAK -> DRangePriority.WEAK
                    Strength.STRONG -> DRangePriority.STRONG
                    else -> if ((field("DR\\s+Priority") ?: "").contains("auto", true)) DRangePriority.AUTO
                    else DRangePriority.OFF
                }
            },
            highlight = (num("Highlight") ?: 0.0).halfSteps().coerceIn(-2.0, 4.0),
            shadow = (num("Shadow") ?: 0.0).halfSteps().coerceIn(-2.0, 4.0),
            color = (num("Colou?r")?.roundToInt() ?: 0).coerceIn(-4, 4),
            sharpness = ((num("Sharpness") ?: num("Sharpening"))?.roundToInt() ?: 0).coerceIn(-4, 4),
            noiseReduction = (num("High\\s+ISO\\s+NR")?.roundToInt() ?: 0).coerceIn(-4, 4),
            // Combined form "Grain Effect: Strong, Small" or split Roughness/Size fields
            grainEffect = strength(
                field("Grain\\s+Effect\\s*-?\\s*Roughness") ?: field("Grain\\s+Effect")
            ),
            grainSize = if (
                (field("Grain\\s+Effect\\s*-?\\s*Size") ?: field("Grain\\s+Effect") ?: "")
                    .contains("large", true)
            ) GrainSize.LARGE else GrainSize.SMALL,
            colorChromeEffect = strength(field("Color\\s+Chrome\\s+Effect")),
            // "Color FX Blue" (FujiStyle) or "Color Chrome Effect Blue" (Fuji X Weekly)
            colorChromeFxBlue = strength(field("Color\\s+(?:Chrome\\s+)?(?:FX|Effect)\\s+Blue")),
            clarity = (num("Clarity")?.roundToInt() ?: 0).coerceIn(-5, 5),
            // ISO is the only free-text field: when empty at the end of the block,
            // the regex can swallow the "Made with FUJISTYLE APP" footer line.
            iso = field("ISO")
                ?.takeIf { it.isNotBlank() && !it.contains("fujistyle", true) && !it.contains("made with", true) }
                ?: "Auto",
            exposureCompensation = field("Exposure\\s+Compensation")?.takeIf { it.isNotBlank() } ?: "0",
            tags = listOf(tag),
        )
    }

    private fun Double.halfSteps(): Double = (this * 2).roundToInt() / 2.0

    private fun strength(raw: String?): Strength = when {
        raw == null -> Strength.OFF
        raw.contains("strong", true) -> Strength.STRONG
        raw.contains("weak", true) -> Strength.WEAK
        else -> Strength.OFF // None / Off
    }

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
