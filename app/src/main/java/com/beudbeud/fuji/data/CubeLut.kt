package com.beudbeud.fuji.data

/**
 * Builds a 3D LUT by observing what the camera did to an image.
 *
 * A RAF is developed twice — once neutral, once with the recipe — and every
 * pixel pair is one (input colour → output colour) sample. A single frame gives
 * millions of them, so no colour chart is needed.
 *
 * The catch is coverage: a scene only contains the colours it contains. Grid
 * cells no pixel landed in are filled from their neighbours, and cells the fill
 * cannot reach pass colour through unchanged. [coverage] reports how much of the
 * cube was actually measured, which is the honest quality figure for the result.
 *
 * Clarity and grain are spatial effects and cannot be represented here at all.
 */
class CubeLut(val size: Int = 33) {
    init { require(size in 2..64) { "LUT size $size out of range" } }

    private val cells = size * size * size
    private val sum = DoubleArray(cells * 3)
    private val weight = DoubleArray(cells)
    private val hits = IntArray(cells)

    /** Red varies fastest, as the .cube format requires. */
    private fun index(r: Int, g: Int, b: Int) = (b * size + g) * size + r

    private fun quantize(v: Int) = (v.coerceIn(0, 255) * (size - 1) + 127) / 255

    /**
     * Record that input colour [src] came out as [dst]. Channels are 0..255.
     *
     * The sample almost never lands on a grid node — the chart controls raw
     * values, not what the camera's pipeline renders them as — so its weight is
     * spread over the eight nodes around it, in proportion to how close it is to
     * each. Snapping to the nearest node instead makes every node the average of
     * its whole surrounding box, which flattens the transform exactly where it
     * curves most: the shadows and the saturated colours a recipe works on.
     */
    fun accumulate(srcR: Int, srcG: Int, srcB: Int, dstR: Int, dstG: Int, dstB: Int) {
        // Nearest node, kept only so "measured" keeps meaning one sample landed
        // here rather than a fraction of one leaking in from a neighbour.
        hits[index(quantize(srcR), quantize(srcG), quantize(srcB))]++

        val span = size - 1
        val fr = srcR.coerceIn(0, 255) * span / 255.0
        val fg = srcG.coerceIn(0, 255) * span / 255.0
        val fb = srcB.coerceIn(0, 255) * span / 255.0
        // Clamped one short of the top so the upper node of each pair exists;
        // a sample sitting exactly on the last node then has t = 1 and lands
        // wholly on it, which is what we want.
        val r0 = fr.toInt().coerceIn(0, size - 2)
        val g0 = fg.toInt().coerceIn(0, size - 2)
        val b0 = fb.toInt().coerceIn(0, size - 2)
        val tr = fr - r0
        val tg = fg - g0
        val tb = fb - b0

        val dr = dstR / 255.0
        val dg = dstG / 255.0
        val db = dstB / 255.0
        for (i in 0..1) {
            val wr = if (i == 0) 1.0 - tr else tr
            if (wr <= 0.0) continue
            for (j in 0..1) {
                val wg = wr * (if (j == 0) 1.0 - tg else tg)
                if (wg <= 0.0) continue
                for (k in 0..1) {
                    val w = wg * (if (k == 0) 1.0 - tb else tb)
                    if (w <= 0.0) continue
                    val c = index(r0 + i, g0 + j, b0 + k)
                    weight[c] += w
                    sum[c * 3] += w * dr
                    sum[c * 3 + 1] += w * dg
                    sum[c * 3 + 2] += w * db
                }
            }
        }
    }

    /** Fraction of the cube that was measured rather than inferred, 0..1. */
    fun coverage(): Float = hits.count { it > 0 }.toFloat() / cells

    /**
     * Averaged samples, with unvisited cells grown from their filled neighbours.
     * Cells still unreached pass through unchanged, so the LUT degrades to the
     * identity in colours the frame never contained rather than to nonsense.
     */
    fun build(): FloatArray {
        val out = FloatArray(cells * 3)
        val filled = BooleanArray(cells)
        for (i in 0 until cells) {
            if (weight[i] <= 0.0) continue
            filled[i] = true
            for (c in 0..2) out[i * 3 + c] = (sum[i * 3 + c] / weight[i]).toFloat()
        }
        // Dilate into the empty cells until the cube is full or nothing spreads.
        // `repeat` cannot break, so the loop is explicit: the old return@repeat
        // read as a stop but only skipped a round, rescanning the whole cube for
        // every remaining one after the dilation had already finished.
        var pending = (0 until cells).filterNot { filled[it] }
        var round = 0
        while (pending.isNotEmpty() && round++ < size) {
            val grown = mutableListOf<Pair<Int, FloatArray>>()
            for (i in pending) {
                val r = i % size
                val g = (i / size) % size
                val b = i / (size * size)
                val acc = FloatArray(3)
                var n = 0
                for ((dr, dg, db) in NEIGHBOURS) {
                    val nr = r + dr; val ng = g + dg; val nb = b + db
                    if (nr !in 0 until size || ng !in 0 until size || nb !in 0 until size) continue
                    val j = index(nr, ng, nb)
                    if (!filled[j]) continue
                    for (c in 0..2) acc[c] += out[j * 3 + c]
                    n++
                }
                if (n > 0) grown += i to FloatArray(3) { acc[it] / n }
            }
            if (grown.isEmpty()) break
            for ((i, v) in grown) {
                filled[i] = true
                for (c in 0..2) out[i * 3 + c] = v[c]
            }
            pending = pending.filterNot { filled[it] }
        }
        // Never measured, never reached: leave the colour alone
        for (i in 0 until cells) {
            if (filled[i]) continue
            val r = i % size
            val g = (i / size) % size
            val b = i / (size * size)
            out[i * 3] = r.toFloat() / (size - 1)
            out[i * 3 + 1] = g.toFloat() / (size - 1)
            out[i * 3 + 2] = b.toFloat() / (size - 1)
        }
        return out
    }

    /** Adobe/Resolve .cube text — readable by Lightroom, Capture One, Darktable, Resolve. */
    fun toCubeText(title: String): String = buildString {
        appendLine("# Generated by Reshipi from an in-camera RAW conversion")
        appendLine("# Measured coverage: ${(coverage() * 100).toInt()}% of the colour cube")
        appendLine("TITLE \"${title.replace('"', '\'')}\"")
        appendLine("LUT_3D_SIZE $size")
        appendLine("DOMAIN_MIN 0.0 0.0 0.0")
        appendLine("DOMAIN_MAX 1.0 1.0 1.0")
        val grid = build()
        for (i in 0 until cells) {
            appendLine(
                "%.6f %.6f %.6f".format(
                    java.util.Locale.US, grid[i * 3], grid[i * 3 + 1], grid[i * 3 + 2],
                )
            )
        }
    }

    private companion object {
        val NEIGHBOURS = listOf(
            Triple(-1, 0, 0), Triple(1, 0, 0),
            Triple(0, -1, 0), Triple(0, 1, 0),
            Triple(0, 0, -1), Triple(0, 0, 1),
        )
    }
}
