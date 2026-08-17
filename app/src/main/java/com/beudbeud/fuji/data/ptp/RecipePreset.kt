package com.beudbeud.fuji.data.ptp

import com.beudbeud.fuji.data.DebugLog
import com.beudbeud.fuji.model.CAMERA_MODELS
import com.beudbeud.fuji.model.DynamicRange
import com.beudbeud.fuji.model.FilmSimulation
import com.beudbeud.fuji.model.Generation
import com.beudbeud.fuji.model.GrainSize
import com.beudbeud.fuji.model.Recipe
import com.beudbeud.fuji.model.Strength
import com.beudbeud.fuji.model.WhiteBalance
import kotlin.math.roundToInt

// Recipe → camera preset properties (0xD18E-0xD1A5).
// Encodings and write order from FilmKit's translateUIToPresetProps
// (github.com/eggricesoy/filmkit, MIT license), confirmed on X100VI.

internal val FILM_SIM_CODE = mapOf(
    FilmSimulation.PROVIA to 0x01,
    FilmSimulation.VELVIA to 0x02,
    FilmSimulation.ASTIA to 0x03,
    FilmSimulation.PRO_NEG_HI to 0x04,
    FilmSimulation.PRO_NEG_STD to 0x05,
    FilmSimulation.MONOCHROME to 0x06,
    FilmSimulation.MONOCHROME_YE to 0x07,
    FilmSimulation.MONOCHROME_R to 0x08,
    FilmSimulation.MONOCHROME_G to 0x09,
    FilmSimulation.SEPIA to 0x0A,
    FilmSimulation.CLASSIC_CHROME to 0x0B,
    FilmSimulation.ACROS to 0x0C,
    FilmSimulation.ACROS_YE to 0x0D,
    FilmSimulation.ACROS_R to 0x0E,
    FilmSimulation.ACROS_G to 0x0F,
    FilmSimulation.ETERNA to 0x10,
    FilmSimulation.CLASSIC_NEG to 0x11,
    FilmSimulation.ETERNA_BLEACH_BYPASS to 0x12,
    FilmSimulation.NOSTALGIC_NEG to 0x13,
    FilmSimulation.REALA_ACE to 0x14,
)

internal val WB_CODE = mapOf(
    WhiteBalance.AUTO to 0x0002,
    WhiteBalance.DAYLIGHT to 0x0004,
    WhiteBalance.SHADE to 0x8006,
    WhiteBalance.FLUORESCENT_1 to 0x8001,
    WhiteBalance.FLUORESCENT_2 to 0x8002,
    WhiteBalance.FLUORESCENT_3 to 0x8003,
    WhiteBalance.INCANDESCENT to 0x0006,
    WhiteBalance.UNDERWATER to 0x0008,
    WhiteBalance.KELVIN to 0x8007,
    // Confirmed on X-T30 III (2026-08) by reading back slots set to each mode.
    WhiteBalance.CUSTOM_1 to 0x8008,
    WhiteBalance.CUSTOM_2 to 0x8009,
    WhiteBalance.CUSTOM_3 to 0x800A,
)

/** HighIsoNR uses a proprietary non-linear encoding (from Wireshark captures). */
internal val NR_ENCODE = mapOf(
    -4 to 0x8000, -3 to 0x7000, -2 to 0x4000, -1 to 0x3000,
    0 to 0x2000, 1 to 0x1000, 2 to 0x0000, 3 to 0x6000, 4 to 0x5000,
)

internal val MONO_SIMS = setOf(
    FilmSimulation.MONOCHROME, FilmSimulation.MONOCHROME_YE,
    FilmSimulation.MONOCHROME_R, FilmSimulation.MONOCHROME_G,
    FilmSimulation.SEPIA, FilmSimulation.ACROS, FilmSimulation.ACROS_YE,
    FilmSimulation.ACROS_R, FilmSimulation.ACROS_G,
)

/**
 * Build the ordered property list for writing this recipe as a camera preset.
 * Order matters: Kelvin (0xD19C) must directly follow WB mode (0xD199),
 * and 0xD19F (Color) is rejected for monochrome film simulations.
 * Not stored in camera presets: ISO, exposure compensation, D-Range Priority.
 */
fun Recipe.toPresetProps(): List<Pair<Int, ByteArray>> = buildList {
    add(0xD18E to packU16(7)) // ImageSize L 3:2 (observed default)
    add(0xD18F to packU16(4)) // ImageQuality (observed default)
    // ponytail: presets can't store DR Auto — mapped to DR100
    val dr = when (dynamicRange) {
        DynamicRange.DR200 -> 200
        DynamicRange.DR400 -> 400
        else -> 100
    }
    add(0xD190 to packU16(dr))
    add(0xD191 to packU16(0))
    add(0xD192 to packU16(FILM_SIM_CODE.getValue(filmSimulation)))
    val grain = when {
        grainEffect == Strength.OFF -> 1
        grainSize == GrainSize.SMALL -> if (grainEffect == Strength.WEAK) 2 else 3
        else -> if (grainEffect == Strength.WEAK) 4 else 5
    }
    add(0xD195 to packU16(grain))
    add(0xD196 to packU16(colorChromeEffect.ordinal + 1))
    add(0xD197 to packU16(colorChromeFxBlue.ordinal + 1))
    add(0xD198 to packU16(1)) // SmoothSkin off (not modeled in Recipe)
    // Unknown code (custom WB): leave the slot's existing white balance rather
    // than write a guess. The send flow surfaces this as a warning.
    WB_CODE[whiteBalance]?.let { add(0xD199 to packU16(it)) }
    if (whiteBalance == WhiteBalance.KELVIN && kelvin != null) {
        add(0xD19C to packU16(kelvin.coerceIn(2500, 10000)))
    }
    add(0xD19A to packI16(wbShiftRed))
    add(0xD19B to packI16(wbShiftBlue))
    add(0xD19D to packI16((highlight * 10).roundToInt()))
    add(0xD19E to packI16((shadow * 10).roundToInt()))
    if (filmSimulation !in MONO_SIMS) add(0xD19F to packI16(color * 10))
    add(0xD1A0 to packI16(sharpness * 10))
    NR_ENCODE[noiseReduction]?.let { add(0xD1A1 to packU16(it)) }
    add(0xD1A2 to packI16(clarity * 10))
    add(0xD1A3 to packU16(1)) // LongExpNR on (observed default)
    add(0xD1A4 to packU16(1)) // ColorSpace sRGB
    add(0xD1A5 to packU16(7)) // unknown, constant in all observed presets
}

/**
 * Decode a preset read back from the camera (properties 0xD18E-0xD1A5) into a
 * Recipe — the inverse of [toPresetProps]. Unknown or sentinel values fall back
 * to the Recipe defaults rather than failing, so a partially-read slot still
 * produces something editable.
 */
fun recipeFromPresetProps(name: String, props: Map<Int, ByteArray>, cameraModel: String): Recipe {
    fun i16(id: Int): Int? = props[id]?.takeIf { it.size >= 2 }?.let {
        java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
    }
    fun u16(id: Int): Int? = i16(id)?.and(0xFFFF)
    // Tone values are stored x10; 0x8000 is "unset", meaning the shooting value.
    fun tone(id: Int): Double = i16(id)?.takeIf { it != -32768 }?.let { it / 10.0 } ?: 0.0

    val simByCode = FILM_SIM_CODE.entries.associate { (k, v) -> v to k }
    val wbByCode = WB_CODE.entries.associate { (k, v) -> v to k }
    val nrByCode = NR_ENCODE.entries.associate { (k, v) -> v to k }

    val sim = simByCode[u16(0xD192)] ?: FilmSimulation.PROVIA
    val wbCode = u16(0xD199)
    // Log unmapped codes: this is how the custom-WB values get identified from
    // a real camera instead of guessed.
    if (wbCode != null && wbByCode[wbCode] == null) {
        DebugLog.log("unknown WB code 0x${wbCode.toString(16).uppercase()} in preset \"$name\"")
    }
    val wb = wbByCode[wbCode] ?: WhiteBalance.AUTO
    // Grain: 1=off, 2=weak/small, 3=strong/small, 4=weak/large, 5=strong/large.
    // Some bodies keep the size while off (6=off/small, 7=off/large).
    val grainRaw = u16(0xD195) ?: 1
    val generation = CAMERA_MODELS.toMap()[cameraModel] ?: Generation.X_TRANS_V

    return Recipe(
        name = name,
        cameraModel = cameraModel,
        generation = generation,
        filmSimulation = sim,
        whiteBalance = wb,
        kelvin = u16(0xD19C)?.takeIf { wb == WhiteBalance.KELVIN && it > 0 },
        wbShiftRed = (i16(0xD19A) ?: 0).coerceIn(-9, 9),
        wbShiftBlue = (i16(0xD19B) ?: 0).coerceIn(-9, 9),
        dynamicRange = when (u16(0xD190)) {
            200 -> DynamicRange.DR200
            400 -> DynamicRange.DR400
            else -> DynamicRange.DR100
        },
        highlight = tone(0xD19D).coerceIn(-2.0, generation.toneMax),
        shadow = tone(0xD19E).coerceIn(-2.0, generation.toneMax),
        color = tone(0xD19F).roundToInt().coerceIn(-generation.colorRange, generation.colorRange),
        sharpness = tone(0xD1A0).roundToInt().coerceIn(-generation.colorRange, generation.colorRange),
        noiseReduction = (nrByCode[u16(0xD1A1)] ?: 0).coerceIn(-generation.colorRange, generation.colorRange),
        grainEffect = when (grainRaw) {
            2, 4 -> Strength.WEAK
            3, 5 -> Strength.STRONG
            else -> Strength.OFF
        },
        grainSize = if (grainRaw == 4 || grainRaw == 5 || grainRaw == 7) GrainSize.LARGE else GrainSize.SMALL,
        colorChromeEffect = Strength.entries.getOrElse((u16(0xD196) ?: 1) - 1) { Strength.OFF },
        colorChromeFxBlue = Strength.entries.getOrElse((u16(0xD197) ?: 1) - 1) { Strength.OFF },
        clarity = tone(0xD1A2).roundToInt().coerceIn(-5, 5),
        tags = listOf("camera"),
    )
}

/**
 * Patch the camera's native d185 conversion profile with this recipe.
 * Field indices from FilmKit's NativeIdx (confirmed on X100VI): the profile
 * ends with numParams (u16 at offset 0) int32 fields. Untouched fields keep
 * the camera's sentinels so it falls back to the RAF's shooting values.
 */
/**
 * The conversion profile's parameter array. The layout is a header of unknown
 * content followed by [numParams] little-endian ints; only the tail is decoded.
 */
fun profileParams(profile: ByteArray): List<Int> {
    if (profile.size < 2) return emptyList()
    val bb = java.nio.ByteBuffer.wrap(profile).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val n = bb.getShort(0).toInt() and 0xFFFF
    val off = profile.size - n * 4
    if (n !in 8..64 || off < 2) return emptyList()
    return (0 until n).map { bb.getInt(off + it * 4) }
}

/** "0=1 1=0 2=400 …" — every slot, for correlating the layout against known settings. */
fun profileDump(profile: ByteArray): String =
    profileParams(profile).mapIndexed { i, v -> "$i=$v" }.joinToString(" ")

fun Recipe.patchProfile(base: ByteArray): ByteArray {
    val out = base.copyOf()
    val bb = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val numParams = bb.getShort(0).toInt() and 0xFFFF
    val off = out.size - numParams * 4
    require(numParams in 8..64 && off >= 2) { "Unexpected profile layout (${out.size}B/$numParams params)" }
    fun set(idx: Int, v: Int) = bb.putInt(off + idx * 4, v)

    // The parameter indices below were confirmed on an X100VI. If another body
    // orders them differently the conversion renders wrong, so log the layout and
    // the values we are about to overwrite — enough to spot a mismatch from a report.
    DebugLog.log("patchProfile in : ${out.size}B/$numParams params — ${profileDump(base)}")

    set(8, FILM_SIM_CODE.getValue(filmSimulation))
    when (dynamicRange) {
        DynamicRange.DR100 -> set(6, 100)
        DynamicRange.DR200 -> set(6, 200)
        DynamicRange.DR400 -> set(6, 400)
        DynamicRange.AUTO -> Unit // keep the RAF's value
    }
    // Index 7 is live, and all four of its values render differently — measured
    // by converting one chart at each and reading the mean of the result:
    //
    //   7=0  R163 G179 B162   7=1  R149 G151 B159
    //   7=2  R146 G160 B153   7=3  R153 G167 B154
    //
    // What is not established is which value is which. The ordinal goes out bare,
    // 0 for off, where the neighbours above write ordinal + 1 and send 1 for off,
    // and brightness does not settle it: 7=2 renders darker than 7=3, and strong
    // compressing less than weak is not something D-Range Priority does. Nor can
    // it settle it — D-Range Priority maps tones from the histogram, and a chart
    // that sweeps the cube uniformly is not a scene, so the order it comes out in
    // says more about the chart than about the encoding.
    //
    // The body's own makernotes would settle it: shoot at weak and at strong and
    // read what it writes for DRangePriorityFixed.
    set(7, dRangePriority.ordinal)
    val grain = when {
        grainEffect == Strength.OFF -> 1
        grainSize == GrainSize.SMALL -> if (grainEffect == Strength.WEAK) 2 else 3
        else -> if (grainEffect == Strength.WEAK) 4 else 5
    }
    set(9, grain)
    set(10, colorChromeEffect.ordinal + 1)
    set(25, colorChromeFxBlue.ordinal + 1)
    // Index 12 is the white balance mode, and the camera clamps it to 0 whatever
    // we write — probed with every ordinal 0-12 and every 0x800X code on an
    // X-T30 III, only 0 survives. RAW conversion keeps the shot's own white
    // balance.
    //
    // The R/B shifts below survive a read back, which is not the same as being
    // honoured: converting one chart twice, once with 13/14 at -6/-4 and once at
    // 0/0, returned JPEGs of identical size and a LUT identical to three decimal
    // places. In-camera conversion appears to ignore white balance entirely.
    // They are still written, since storing them costs nothing and a body that
    // does honour them wants them.
    if (whiteBalance == WhiteBalance.KELVIN && kelvin != null) set(15, kelvin)
    set(13, wbShiftRed)
    set(14, wbShiftBlue)
    set(16, (highlight * 10).roundToInt())
    set(17, (shadow * 10).roundToInt())
    if (filmSimulation !in MONO_SIMS) set(18, color * 10)
    set(19, sharpness * 10)
    NR_ENCODE[noiseReduction]?.let { set(20, it) }
    set(27, clarity * 10)
    return out
}
