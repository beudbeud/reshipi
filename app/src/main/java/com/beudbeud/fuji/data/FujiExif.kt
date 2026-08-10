package com.beudbeud.fuji.data

import com.beudbeud.fuji.model.CAMERA_MODELS
import com.beudbeud.fuji.model.DynamicRange
import com.beudbeud.fuji.model.FilmSimulation
import com.beudbeud.fuji.model.Generation
import com.beudbeud.fuji.model.GrainSize
import com.beudbeud.fuji.model.Recipe
import com.beudbeud.fuji.model.Strength
import com.beudbeud.fuji.model.WhiteBalance
import com.beudbeud.fuji.model.formatSigned
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Extracts a Recipe from the Fujifilm MakerNote of an out-of-camera JPEG.
 * Tag ids and value tables follow exiftool's Fujifilm.pm; raw values verified
 * against an X-T30 III JPEG. Returns null when the photo has no Fuji MakerNote
 * (edited/exported photos usually lose it).
 */
object FujiExif {

    fun parse(jpeg: ByteArray): Recipe? = runCatching { doParse(jpeg) }.getOrNull()

    // -- Fuji value tables (exiftool Fujifilm.pm) --

    private val SHARPNESS = mapOf(
        0x00 to -4, 0x01 to -3, 0x02 to -2, 0x82 to -1,
        0x03 to 0, 0x84 to 1, 0x04 to 2, 0x05 to 3, 0x06 to 4,
    )
    private val SATURATION = mapOf(
        0x000 to 0, 0x080 to 1, 0x100 to 2, 0x0C0 to 3, 0x0E0 to 4,
        0x180 to -1, 0x200 to -2, 0x1C0 to -3, 0x1E0 to -4,
    )
    private val MONO_FROM_SATURATION = mapOf(
        0x300 to FilmSimulation.MONOCHROME,
        0x301 to FilmSimulation.MONOCHROME_R,
        0x302 to FilmSimulation.MONOCHROME_YE,
        0x303 to FilmSimulation.MONOCHROME_G,
        0x310 to FilmSimulation.SEPIA,
        0x500 to FilmSimulation.ACROS,
        0x501 to FilmSimulation.ACROS_R,
        0x502 to FilmSimulation.ACROS_YE,
        0x503 to FilmSimulation.ACROS_G,
    )
    private val NOISE_REDUCTION = mapOf(
        0x000 to 0, 0x100 to 2, 0x180 to 1, 0x1C0 to 3, 0x1E0 to 4,
        0x200 to -2, 0x280 to -1, 0x2C0 to -3, 0x2E0 to -4,
    )
    private val FILM_MODE = mapOf(
        0x000 to FilmSimulation.PROVIA,
        0x120 to FilmSimulation.ASTIA,
        0x200 to FilmSimulation.VELVIA,
        0x400 to FilmSimulation.VELVIA,
        0x500 to FilmSimulation.PRO_NEG_STD,
        0x501 to FilmSimulation.PRO_NEG_HI,
        0x600 to FilmSimulation.CLASSIC_CHROME,
        0x700 to FilmSimulation.ETERNA,
        0x800 to FilmSimulation.CLASSIC_NEG,
        0x900 to FilmSimulation.ETERNA_BLEACH_BYPASS,
        0xA00 to FilmSimulation.NOSTALGIC_NEG,
        0xB00 to FilmSimulation.REALA_ACE,
    )
    private val WB = mapOf(
        0x000 to WhiteBalance.AUTO,
        0x100 to WhiteBalance.DAYLIGHT,
        0x200 to WhiteBalance.SHADE,
        0x300 to WhiteBalance.FLUORESCENT_1,
        0x301 to WhiteBalance.FLUORESCENT_2,
        0x302 to WhiteBalance.FLUORESCENT_3,
        0x400 to WhiteBalance.INCANDESCENT,
        0x600 to WhiteBalance.UNDERWATER,
        0xFF0 to WhiteBalance.KELVIN,
    )
    private val STRENGTH = mapOf(0 to Strength.OFF, 32 to Strength.WEAK, 64 to Strength.STRONG)

    // -- TIFF/EXIF plumbing --

    private class Entry(val type: Int, val count: Int, val fieldPos: Int)

    private class Tiff(val bb: ByteBuffer, val base: Int) {
        fun u16(pos: Int) = bb.getShort(pos).toInt() and 0xFFFF
        fun u32(pos: Int) = bb.getInt(pos)
        fun i32(pos: Int) = bb.getInt(pos)

        fun readIfd(ifdOffset: Int): Map<Int, Entry> {
            val entries = mutableMapOf<Int, Entry>()
            val start = base + ifdOffset
            val count = u16(start)
            for (i in 0 until count) {
                val p = start + 2 + i * 12
                entries[u16(p)] = Entry(u16(p + 2), u32(p + 4), p + 8)
            }
            return entries
        }

        /** Numeric value of a single-value entry (inline in the field). */
        fun value(e: Entry): Int = when (e.type) {
            1 -> bb.get(e.fieldPos).toInt() and 0xFF
            3 -> u16(e.fieldPos)
            8 -> bb.getShort(e.fieldPos).toInt()
            9 -> i32(e.fieldPos)
            else -> u32(e.fieldPos)
        }

        fun ascii(e: Entry): String {
            val pos = if (e.count > 4) base + u32(e.fieldPos) else e.fieldPos
            return buildString {
                for (i in 0 until e.count) {
                    val c = bb.get(pos + i).toInt() and 0xFF
                    if (c == 0) break
                    append(c.toChar())
                }
            }.trim()
        }
    }

    private fun doParse(jpeg: ByteArray): Recipe? {
        val tiffStart = findExifTiff(jpeg) ?: return null
        val order = if (jpeg[tiffStart] == 'M'.code.toByte()) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val tiff = Tiff(ByteBuffer.wrap(jpeg).order(order), tiffStart)

        val ifd0 = tiff.readIfd(tiff.u32(tiffStart + 4))
        val model = ifd0[0x0110]?.let { tiff.ascii(it) } ?: ""
        val exifIfd = ifd0[0x8769]?.let { tiff.readIfd(tiff.value(it)) } ?: return null

        val iso = exifIfd[0x8827]?.let { tiff.value(it) }
        val bias = exifIfd[0x9204]?.let {
            val pos = tiff.base + tiff.u32(it.fieldPos)
            formatBias(tiff.i32(pos), tiff.i32(pos + 4))
        }

        // Fuji MakerNote: "FUJIFILM" header, then a LE IFD whose offsets are
        // relative to the MakerNote start.
        val mnEntry = exifIfd[0x927C] ?: return null
        val mnStart = tiff.base + tiff.u32(mnEntry.fieldPos)
        val header = String(jpeg, mnStart, 8, Charsets.US_ASCII)
        if (header != "FUJIFILM") return null
        val mn = Tiff(ByteBuffer.wrap(jpeg).order(ByteOrder.LITTLE_ENDIAN), mnStart)
        val tags = mn.readIfd(mn.u32(mnStart + 8))

        fun tag(id: Int): Int? = tags[id]?.let { mn.value(it) }

        val saturationRaw = tag(0x1003) ?: 0
        val filmSim = MONO_FROM_SATURATION[saturationRaw]
            ?: FILM_MODE[tag(0x1401) ?: 0] ?: FilmSimulation.PROVIA

        val wbFineTune = tags[0x100A]?.let {
            val pos = mnStart + mn.u32(it.fieldPos)
            (mn.i32(pos) / 20) to (mn.i32(pos + 4) / 20)
        } ?: (0 to 0)

        val whiteBalance = WB[tag(0x1002) ?: 0] ?: WhiteBalance.AUTO
        val dynamicRange = if ((tag(0x1402) ?: 0) == 0) DynamicRange.AUTO else when (tag(0x1403)) {
            200 -> DynamicRange.DR200
            400 -> DynamicRange.DR400
            else -> DynamicRange.DR100
        }
        // Highlight/shadow tone: stored as tone × -16 (32 → -2, -8 → +0.5)
        val highlight = -(tag(0x1041) ?: 0) / 16.0
        val shadow = -(tag(0x1040) ?: 0) / 16.0

        val modelGen = CAMERA_MODELS.toMap()[model]
        val gen = modelGen ?: maxOf(Generation.X_TRANS_IV, filmSim.minGen)

        return Recipe(
            cameraModel = if (modelGen != null) model else "",
            generation = gen,
            filmSimulation = filmSim,
            whiteBalance = whiteBalance,
            kelvin = if (whiteBalance == WhiteBalance.KELVIN) tag(0x1005) else null,
            wbShiftRed = wbFineTune.first.coerceIn(-9, 9),
            wbShiftBlue = wbFineTune.second.coerceIn(-9, 9),
            dynamicRange = dynamicRange,
            highlight = highlight.coerceIn(-2.0, gen.toneMax),
            shadow = shadow.coerceIn(-2.0, gen.toneMax),
            color = (SATURATION[saturationRaw] ?: 0).coerceIn(-gen.colorRange, gen.colorRange),
            sharpness = (SHARPNESS[tag(0x1001) ?: 3] ?: 0).coerceIn(-gen.colorRange, gen.colorRange),
            noiseReduction = (NOISE_REDUCTION[tag(0x100E) ?: 0] ?: 0).coerceIn(-gen.colorRange, gen.colorRange),
            grainEffect = STRENGTH[tag(0x1047) ?: 0] ?: Strength.OFF,
            grainSize = if ((tag(0x104C) ?: 0) >= 32) GrainSize.LARGE else GrainSize.SMALL,
            colorChromeEffect = STRENGTH[tag(0x1048) ?: 0] ?: Strength.OFF,
            colorChromeFxBlue = STRENGTH[tag(0x104E) ?: 0] ?: Strength.OFF,
            clarity = ((tag(0x100F) ?: 0) / 1000).coerceIn(-5, 5),
            iso = iso?.toString() ?: "Auto",
            exposureCompensation = bias ?: "0",
        )
    }

    /** Locate the TIFF header inside the JPEG's APP1 Exif segment. */
    private fun findExifTiff(jpeg: ByteArray): Int? {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 4 < jpeg.size && jpeg[i] == 0xFF.toByte()) {
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xDA) break // start of scan — no more metadata
            val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (marker == 0xE1 && i + 10 < jpeg.size &&
                String(jpeg, i + 4, 4, Charsets.US_ASCII) == "Exif"
            ) {
                return i + 10
            }
            i += 2 + len
        }
        return null
    }

    /** EXIF exposure bias rational → "0", "-1", "+2/3" */
    private fun formatBias(num: Int, den: Int): String {
        if (num == 0 || den == 0) return "0"
        if (num % den == 0) return formatSigned(num / den)
        val g = gcd(kotlin.math.abs(num), kotlin.math.abs(den))
        val n = num / g
        val d = den / g
        return "${if (n > 0) "+" else ""}$n/$d"
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
