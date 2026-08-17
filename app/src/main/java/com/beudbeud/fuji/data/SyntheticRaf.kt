package com.beudbeud.fuji.data

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Overwrites a RAF's sensor data with a grid of flat colour patches.
 *
 * A LUT measured from a photograph only learns the colours that photograph
 * happens to contain — a good frame reaches about a quarter of the cube. A
 * chart reaches whatever the camera's pipeline can actually produce, because
 * every patch is a colour we chose.
 *
 * The donor supplies the container: header, embedded preview, calibration
 * tags, camera model. Only the photosite values change, so the file stays the
 * same size and every offset in it stays valid.
 *
 * ```
 * 0x54  preview JPEG    offset, length  (big endian u32 pairs)
 * 0x5C  CFA header      offset, length
 * 0x64  CFA data        offset, length
 * ```
 *
 * The CFA header is a tag list: big-endian u32 count, then (u16 tag, u16 size,
 * bytes). Tag 0x0100 holds the readout size as (height, width); tag 0x0131
 * holds the 6x6 X-Trans mosaic, one byte per site, 0=red 1=green 2=blue. Both
 * are read from the donor rather than hard-coded, so a different body with a
 * different sensor still works.
 */
object SyntheticRaf {
    /** Photosites are 14 bit, stored in 16. */
    private const val MAX = 16383

    /**
     * The count a photosite reads in the dark. Signal is what sits above it, and
     * everything at or below it develops to the same black.
     *
     * The chart was painted from 0 up, so ten of its 33 levels per axis landed
     * under this and rendered identically — a third of every axis wasted, and
     * nine consecutive holes in the measured grey axis that no amount of extra
     * probes could fill, because there was nothing down there to distinguish.
     * Read from the donor's own BlackLevel tag (1022 on an X-T30 III); a
     * calibration knob if another body differs.
     */
    private const val BLACK = 1022

    /**
     * Where the mosaic sits relative to the start of the readout. Measured on an
     * X-T30 III by rebuilding the image at all 36 phases and correlating each
     * against the file's own preview JPEG (0.723 for this one, 0.689 next best).
     *
     * A wrong phase would only permute which patch gets which colour, not how
     * many distinct colours the chart produces, so coverage does not depend on
     * this being right — only the chart looking like the chart does.
     */
    private const val PHASE = 1

    class Layout(val pixels: Int, val width: Int, val height: Int, val pattern: ByteArray)

    private fun u32(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)

    private fun u16(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    /** Sensor geometry, or null when this RAF cannot be rewritten. */
    fun layout(raf: ByteArray): Layout? {
        if (!RafFile.isRaf(raf) || raf.size < 0x6C) return null
        val headerOffset = u32(raf, 0x5C)
        val headerLength = u32(raf, 0x60)
        val cfaOffset = u32(raf, 0x64)
        val cfaLength = u32(raf, 0x68)
        if (headerOffset <= 0 || headerLength <= 4 || cfaOffset <= 0 || cfaLength <= 0) return null
        if (headerOffset + headerLength > raf.size || cfaOffset + cfaLength > raf.size) return null

        var pattern: ByteArray? = null
        var width = 0
        var height = 0
        var p = headerOffset + 4
        val end = headerOffset + headerLength
        repeat(u32(raf, headerOffset)) {
            if (p + 4 <= end) {
                val tag = u16(raf, p)
                val size = u16(raf, p + 2)
                if (p + 4 + size <= end) {
                    when {
                        tag == 0x0100 && size == 4 -> {
                            height = u16(raf, p + 4)
                            width = u16(raf, p + 6)
                        }
                        tag == 0x0131 && size == 36 -> pattern = raf.copyOfRange(p + 4, p + 40)
                    }
                }
                p += 4 + size
            }
        }
        val mosaic = pattern ?: return null
        if (width <= 0 || height <= 0) return null

        // Uncompressed means the block is exactly the readout plus a little
        // padding in front. Anything else is one of the compressed schemes,
        // which we would have to encode rather than simply write.
        val padding = cfaLength - width * height * 2
        if (padding < 0 || padding > 8192) return null
        return Layout(cfaOffset + padding, width, height, mosaic)
    }

    /**
     * What the camera's white balance shift does to a channel gain, as measured.
     *
     * From RAFs shot in Daylight, reading the WB_GRBLevels the body records for
     * each — the shift is folded into those gains rather than kept beside them:
     *
     * ```
     *  steps    G    R    B
     *  R+0 B+0  302  567  536
     *  R-4 B-4  302  494  473
     *  R+9 B-9  302 1050  307
     *  R-9 B+9  302  307  803
     * ```
     *
     * A table rather than a law, because there is no law: the response is fine
     * near zero and coarse at the ends, 18 counts per step between -4 and 0
     * against 54 between 0 and +9. A ratio per step fitted to the ends misses
     * -4 by 15%, which is how this table replaced one.
     *
     * Nothing here is discounted as unreliable either. If the body holds the
     * gain at some floor near the bottom of the grid — both -9 legs land on 307,
     * a hair above green's own 302 — then that floor is what it does, and the
     * gain there really is that. There is only what was recorded.
     *
     * Calibration knobs: one body, one white balance mode, and the assumption
     * that a step means the same wherever the mode starts from. More steps
     * measured is a finer table and nothing else has to change.
     */
    private val SHIFT_STEPS = intArrayOf(-9, -4, 0, 9)
    private val SHIFT_RED = doubleArrayOf(0.5414, 0.8712, 1.0, 1.8519)
    private val SHIFT_BLUE = doubleArrayOf(0.5728, 0.8825, 1.0, 1.4981)

    /** Straight-line between the measured steps, held at the ends. */
    private fun interpolate(steps: Int, gain: DoubleArray): Double {
        val n = steps.coerceIn(SHIFT_STEPS.first(), SHIFT_STEPS.last())
        for (i in 0 until SHIFT_STEPS.size - 1) {
            val a = SHIFT_STEPS[i]
            val b = SHIFT_STEPS[i + 1]
            if (n in a..b) return gain[i] + (gain[i + 1] - gain[i]) * (n - a) / (b - a).toDouble()
        }
        return 1.0
    }

    /** The channel gains a shift of [red] and [blue] steps comes to. */
    fun shiftGains(red: Int, blue: Int): DoubleArray =
        doubleArrayOf(interpolate(red, SHIFT_RED), interpolate(blue, SHIFT_BLUE))

    /**
     * Paints [steps]^3 colours as [patchPx]-wide patches, in place. Returns false
     * when the donor is compressed or otherwise not one we can rewrite.
     *
     * [patchPx] must be a multiple of 6 so every patch holds whole mosaic tiles
     * and is therefore a single flat colour.
     */
    fun chart(raf: ByteArray, patchPx: Int = 24, steps: Int = 33): Boolean {
        require(patchPx % 6 == 0) { "patch must tile the 6x6 mosaic" }
        val layout = layout(raf) ?: return false
        val cols = layout.width / patchPx
        val rows = layout.height / patchPx
        if (cols < 1 || rows < 1) return false

        // Raw counts are linear light above the black level; the camera's tone
        // curve lifts the shadows hard. Spacing the levels by a gamma spreads the
        // *rendered* patches evenly instead of piling them into the highlights,
        // and the ladder starts at black so that every rung is a signal rather
        // than a differently-worded nothing.
        val level = IntArray(steps) {
            (BLACK + (MAX - BLACK) * (it.toDouble() / (steps - 1)).pow(2.2))
                .roundToInt().coerceIn(0, MAX)
        }
        val site = Array(6) { y -> IntArray(6) { x -> layout.pattern[((y + PHASE) % 6) * 6 + (x + PHASE) % 6].toInt() } }
        val combinations = steps * steps * steps
        val neutrals = steps * GAINS * GAINS * ORIENTATIONS
        val spare = cols * rows - combinations
        val rgb = IntArray(3)
        // Says plainly that a chart was painted, and on what geometry — otherwise
        // nothing downstream distinguishes a chart export from a photograph one.
        // Also names the truncation rather than letting it pass silently: with
        // fewer patches than colours the sweep never reaches the last ones.
        DebugLog.log(
            "chart: ${cols}x$rows patches of ${patchPx}px, $combinations colours" +
                when {
                    spare < 0 -> " — TRUNCATED, only ${cols * rows} fit"
                    else -> ", ${minOf(spare, neutrals)} of $neutrals neutral probes"
                }
        )

        var patch = 0
        for (patchRow in 0 until rows) {
            for (patchCol in 0 until cols) {
                val n = patch++
                if (n < combinations) {
                    rgb[0] = level[n % steps]
                    rgb[1] = level[(n / steps) % steps]
                    rgb[2] = level[n / (steps * steps)]
                } else {
                    neutralProbe((n - combinations) % neutrals, level, rgb)
                }
                for (dy in 0 until patchPx) {
                    val y = patchRow * patchPx + dy
                    val row = site[y % 6]
                    var i = layout.pixels + (y * layout.width + patchCol * patchPx) * 2
                    for (dx in 0 until patchPx) {
                        val v = rgb[row[dx % 6]]
                        raf[i] = (v and 0xFF).toByte()
                        raf[i + 1] = (v shr 8).toByte()
                        i += 2
                    }
                }
            }
        }
        return true
    }

    /**
     * Ratios either side of what a camera's own channel gains need — near half
     * the top for red and two thirds for blue.
     *
     * Wide on purpose, and narrowing it has been tried: 0.40 to 0.80 aims three
     * times as finely at those two figures and cost 19530 measured cells to
     * 16648, with the neutral axis falling from 27 of 33 to 18. The fan holds
     * 8019 patches, a fifth of the chart, and only one of its three orientations
     * can reach neutral on a body whose green leads — the other two would need a
     * ratio above 1. Those probes are not failed greys, they are the only
     * colours in the chart that do not come off the sweep's own grid, and the
     * cube misses them at once.
     */
    private const val GAINS = 9
    private const val RATIO_MIN = 0.35
    private const val RATIO_MAX = 1.0

    /** Green on top, then red, then blue: the fan reaches neutral from every side. */
    private const val ORIENTATIONS = 3

    /**
     * Fills [rgb] with probe number [n] of the neutral fan.
     *
     * The cube sweep almost never produces a grey. A patch only renders neutral
     * when its raw channels sit in the ratio that cancels the camera's own gains,
     * roughly red at half the green and blue at two thirds of it, and a grid of
     * whole levels lands on that ratio by accident at best: a real export
     * measured 7 of the 33 cells on the neutral axis and interpolated the other
     * 26 from coloured neighbours, which is why greys came out green.
     *
     * So the patches the sweep does not need are spent walking a fan of ratios
     * around that neutral direction at every brightness. The gains do not have to
     * be known — the fan is wide enough that some probe in it lands neutral, and
     * that probe is the one the grey axis gets measured from.
     */
    internal fun neutralProbe(n: Int, level: IntArray, rgb: IntArray) {
        val steps = level.size
        val top = level[n % steps]
        // The gains act on signal, so the ratio does too: a fraction of the count
        // rather than of the signal is not the fraction it says it is. At a top of
        // 3565 the 0.50 rung was landing on 1782, which above a black of 1022 is a
        // signal ratio of 0.30 — the fan has been aiming somewhere else than where
        // it was pointed, worst of all where the signal is smallest.
        // Held at [top] as well as at zero: a ladder that started below black
        // would put the ratio above the channel it is a ratio of, and the
        // orientation below — which channel leads — would silently invert.
        fun ratio(i: Int) = (
            BLACK + (top - BLACK) *
                (RATIO_MIN + (RATIO_MAX - RATIO_MIN) * i / (GAINS - 1))
            ).roundToInt().coerceIn(0, top)
        val first = ratio((n / steps) % GAINS)
        val second = ratio((n / (steps * GAINS)) % GAINS)
        // Which channel sits at the top decides which side of neutral the probe
        // approaches from. Fixing green there only reaches neutral when the
        // reference render leans magenta; at DR400 it leans green instead, and
        // the neutral axis fell from 21 measured cells of 33 to 11.
        when (n / (steps * GAINS * GAINS) % ORIENTATIONS) {
            0 -> { rgb[0] = first; rgb[1] = top; rgb[2] = second }
            1 -> { rgb[0] = top; rgb[1] = first; rgb[2] = second }
            else -> { rgb[0] = first; rgb[1] = second; rgb[2] = top }
        }
    }
}
