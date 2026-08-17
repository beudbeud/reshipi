package com.beudbeud.fuji.data

import android.content.Context

/**
 * The software an exported LUT is going to be applied in.
 *
 * A .cube says nothing about the encoding it was measured in, so every
 * application guesses — Darktable's guess for a .cube is Rec.709 where this one
 * is sRGB. And white balance is not in the cube at all: it belongs upstream, in
 * whatever module the application white-balances with, and only some of them can
 * be told a channel gain. What that means in practice differs enough per
 * application to be worth writing down for the one the user actually has.
 */
enum class RawDeveloper {
    DARKTABLE,
    RESOLVE,
    LIGHTROOM,
    OTHER;

    /**
     * How to apply the cube, and how to set the white balance it expects in
     * front of it. English and unlocalised: these lines go into the .cube, which
     * outlives the phone that wrote it and gets passed around.
     *
     * [shift] is the white balance shift as channel gains, or null when the
     * recipe has none.
     */
    fun instructions(wb: String?, shift: Pair<Double, Double>?): List<String> = buildList {
        when (this@RawDeveloper) {
            DARKTABLE -> {
                add(
                    "APPLY IN darktable: module \"lut 3D\", application colour space" +
                        " sRGB — not Rec.709, which is what it assumes for a .cube —" +
                        " placed between the input and output colour profile modules."
                )
                if (wb != null || shift != null) {
                    add(
                        "WHITE BALANCE FIRST, module \"white balance\"" +
                            (wb?.let { ", preset $it" } ?: "") +
                            (shift?.let {
                                ", then expand \"channel coefficients\" and multiply" +
                                    " red by %.2f and blue by %.2f".format(
                                        java.util.Locale.US, it.first, it.second,
                                    )
                            } ?: "") + "."
                    )
                }
            }
            RESOLVE -> {
                add(
                    "APPLY IN Resolve: the image has to be in sRGB gamut and gamma at" +
                        " the node the LUT sits on — a Color Space Transform to sRGB" +
                        " in front of it, and its inverse after, if the timeline is" +
                        " anything else."
                )
                if (wb != null || shift != null) {
                    add(
                        "WHITE BALANCE FIRST, in Camera Raw or the primaries" +
                            (wb?.let { ": $it" } ?: "") +
                            (shift?.let {
                                ", with the red gain at %.2f and the blue at %.2f of" +
                                    " what that leaves them".format(
                                        java.util.Locale.US, it.first, it.second,
                                    )
                            } ?: "") + "."
                    )
                }
            }
            LIGHTROOM -> {
                add(
                    "APPLY IN Lightroom: expect a deviation. Develop does not load a" +
                        " .cube — it has to be built into a creative profile — and it" +
                        " works in linear ProPhoto where this cube is sRGB, so the" +
                        " transform lands somewhere near rather than on."
                )
                if (shift != null) {
                    add(
                        "WHITE BALANCE FIRST, and by eye: Lightroom has Temp and Tint" +
                            " and no channel gains, and the relation between the two is" +
                            " particular to each camera. This recipe wants red at %.2f"
                                .format(java.util.Locale.US, shift.first) +
                            " and blue at %.2f of their weight%s — under 1.00 is cooler"
                                .format(java.util.Locale.US, shift.second, wb?.let { " on $it" } ?: "") +
                            " for red, greener for blue. Judge it on a grey."
                    )
                } else if (wb != null) {
                    add("WHITE BALANCE FIRST: $wb.")
                }
            }
            OTHER -> {
                add("APPLY: the cube is measured in sRGB. Tell the application so.")
                if (wb != null || shift != null) {
                    add(
                        "WHITE BALANCE FIRST — it is not in the cube" +
                            (wb?.let { ": $it" } ?: "") +
                            (shift?.let {
                                ", red x%.2f blue x%.2f on top of it".format(
                                    java.util.Locale.US, it.first, it.second,
                                )
                            } ?: "") + "."
                    )
                }
            }
        }
    }

    companion object {
        private const val KEY = "raw_developer"

        fun of(context: Context): RawDeveloper {
            val name = prefs(context).getString(KEY, null) ?: return OTHER
            return entries.firstOrNull { it.name == name } ?: OTHER
        }

        fun set(context: Context, value: RawDeveloper) {
            prefs(context).edit().putString(KEY, value.name).apply()
            DebugLog.log("raw developer set to $value")
        }
    }
}
