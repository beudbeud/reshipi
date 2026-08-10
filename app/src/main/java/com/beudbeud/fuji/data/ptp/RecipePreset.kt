package com.beudbeud.fuji.data.ptp

import com.beudbeud.fuji.model.DynamicRange
import com.beudbeud.fuji.model.FilmSimulation
import com.beudbeud.fuji.model.GrainSize
import com.beudbeud.fuji.model.Recipe
import com.beudbeud.fuji.model.Strength
import com.beudbeud.fuji.model.WhiteBalance
import kotlin.math.roundToInt

// Recipe → camera preset properties (0xD18E-0xD1A5).
// Encodings and write order from FilmKit's translateUIToPresetProps
// (github.com/eggricesoy/filmkit, MIT license), confirmed on X100VI.

private val FILM_SIM_CODE = mapOf(
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

private val WB_CODE = mapOf(
    WhiteBalance.AUTO to 0x0002,
    WhiteBalance.DAYLIGHT to 0x0004,
    WhiteBalance.SHADE to 0x8006,
    WhiteBalance.FLUORESCENT_1 to 0x8001,
    WhiteBalance.FLUORESCENT_2 to 0x8002,
    WhiteBalance.FLUORESCENT_3 to 0x8003,
    WhiteBalance.INCANDESCENT to 0x0006,
    WhiteBalance.UNDERWATER to 0x0008,
    WhiteBalance.KELVIN to 0x8007,
)

/** HighIsoNR uses a proprietary non-linear encoding (from Wireshark captures). */
private val NR_ENCODE = mapOf(
    -4 to 0x8000, -3 to 0x7000, -2 to 0x4000, -1 to 0x3000,
    0 to 0x2000, 1 to 0x1000, 2 to 0x0000, 3 to 0x6000, 4 to 0x5000,
)

private val MONO_SIMS = setOf(
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
    add(0xD199 to packU16(WB_CODE.getValue(whiteBalance)))
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
