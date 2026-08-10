package com.beudbeud.fuji.data

import kotlin.math.abs

/**
 * One recognized block with its pixel box. Deliberately not an ML Kit type so the
 * layout logic below can be unit-tested without a device.
 */
data class OcrBlock(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val height get() = bottom - top
}

/**
 * Turns OCR output into text the recipe parser can read.
 *
 * The reading order ML Kit gives in [Text.text] is wrong for the two-column
 * layouts FujiStyle cards and recipe screenshots use: labels down the left,
 * values down the right. Flattened, that can arrive as every label followed by
 * every value, and pairing them back up by name alone is guesswork. When the
 * boxes show two columns we rebuild the pairs from geometry instead.
 */
object OcrText {

    /** Geometry-stitched text when the layout is two columns, else [fallback]. */
    fun prepare(blocks: List<OcrBlock>, imageWidth: Int, fallback: String): String =
        normalize(stitchColumns(blocks, imageWidth) ?: fallback)

    /**
     * Pair left-column labels with right-column values by vertical position.
     * Returns null when the image is not convincingly two columns, so the caller
     * keeps ML Kit's own reading order.
     */
    internal fun stitchColumns(blocks: List<OcrBlock>, imageWidth: Int): String? {
        if (blocks.size < 6 || imageWidth <= 0) return null

        // The column split is the widest horizontal gap between block centers.
        val centers = blocks.map { it.centerX }.sorted()
        var gap = 0
        var splitX = 0
        for (i in 0 until centers.size - 1) {
            val d = centers[i + 1] - centers[i]
            if (d > gap) {
                gap = d
                splitX = (centers[i] + centers[i + 1]) / 2
            }
        }
        // A single column has no such gap; demanding 15% of the width rejects it.
        if (gap < imageWidth * 0.15) return null

        val labels = blocks.filter { it.centerX < splitX }.sortedBy { it.top }
        val values = blocks.filter { it.centerX >= splitX }.sortedBy { it.top }
        if (labels.isEmpty() || values.isEmpty()) return null

        val taken = HashSet<Int>()
        return buildString {
            for (label in labels) {
                // Nearest unused value on the same line, within one row's height
                val tolerance = label.height.coerceAtLeast(20)
                val best = values.indices
                    .filter { it !in taken }
                    .minByOrNull { abs(values[it].centerY - label.centerY) }
                    ?.takeIf { abs(values[it].centerY - label.centerY) <= tolerance }

                val text = label.text.trim().replace("\n", " ")
                if (best == null) {
                    appendLine(text)
                } else {
                    taken += best
                    appendLine("$text: ${values[best].text.trim().replace("\n", " ")}")
                }
            }
            // Values with no label of their own still carry information
            values.indices.filter { it !in taken }.forEach { appendLine(values[it].text.trim()) }
        }
    }

    // "+|" and "+[" are a plus sign against a badly read 1
    private val SIGN_ONE = Regex("""([+\-])\s?[|lI\[\]!]""")
    private val BRACKET_DIGIT = Regex("""(?<=\d)\]|\](?=\d)""")
    private val IS_ZERO = Regex("""(?i)\bIS0\b""")
    private val ZERO_FF = Regex("""(?i)\b0FF\b""")

    /**
     * Undo the character confusions OCR makes on recipe cards. Applies only to
     * recognized text — pasted text keeps its brackets and pipes untouched.
     */
    internal fun normalize(s: String): String = s
        .replace('−', '-')  // minus sign
        .replace('–', '-')  // en dash
        .replace('—', '-')  // em dash
        .replace(SIGN_ONE) { it.groupValues[1] + "1" }
        // A vertical rule between label and value reads as a bar; it means "value follows"
        .replace('|', ':')
        .replace('│', ':')
        .replace('┃', ':')
        // Only next to digits: real brackets qualify keys like "[Color] Chrome Effect"
        .replace(BRACKET_DIGIT, "1")
        .replace(IS_ZERO, "ISO")
        .replace(ZERO_FF, "OFF")
}
