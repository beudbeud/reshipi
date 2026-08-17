package com.beudbeud.fuji.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.beudbeud.fuji.R
import com.beudbeud.fuji.data.CubeLut
import com.beudbeud.fuji.data.DebugLog
import com.beudbeud.fuji.data.DonorRaf
import com.beudbeud.fuji.data.RafFile
import com.beudbeud.fuji.data.SyntheticRaf
import com.beudbeud.fuji.data.ptp.FujiProp
import com.beudbeud.fuji.data.ptp.patchProfile
import com.beudbeud.fuji.data.ptp.profileDump
import com.beudbeud.fuji.data.ptp.profileParams
import com.beudbeud.fuji.model.CAMERA_MODELS
import com.beudbeud.fuji.model.DRangePriority
import com.beudbeud.fuji.model.DynamicRange
import com.beudbeud.fuji.model.FilmSimulation
import com.beudbeud.fuji.model.Generation
import com.beudbeud.fuji.model.Recipe
import com.beudbeud.fuji.model.Strength
import com.beudbeud.fuji.model.WhiteBalance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Rows of the render decoded at a time. The two JPEGs are read at full
 * resolution, a strip at a time, so a 24px sensor patch stays 24px wide instead
 * of collapsing to 12 — the difference between a handful of clean pixels per
 * patch and a couple of hundred. A strip of each render costs about 6MB.
 */
private const val STRIP_ROWS = 256

/**
 * How far a neighbouring pixel may differ, per channel, for a pixel to count as
 * sitting inside a flat patch rather than on the seam between two.
 */
private const val FLAT_TOLERANCE = 6

/** Per-channel difference above which a pixel counts as actually changed. */
private const val CHANGE_TOLERANCE = 2

/** One sample pair in every this many is kept back to audit the finished cube. */
private const val AUDIT_STRIDE = 400

/** What a pass over the two renders found. */
private class Measured(
    val lut: CubeLut,
    val sampled: Long,
    val changed: Long,
    /** Packed before/after pairs the cube never saw fitted to it. */
    val auditSrc: IntArray,
    val auditDst: IntArray,
)

/**
 * Mean error, per channel, between what the cube predicts for each audited
 * input and what the camera actually produced for it.
 *
 * Everything else measured here describes the cube's shape; this asks the only
 * question that matters — whether it reproduces the very data it was built
 * from. A large error means the fault is in this file, not in the camera.
 */
private fun auditError(grid: FloatArray, size: Int, src: IntArray, dst: IntArray): IntArray {
    if (src.isEmpty()) return IntArray(3)
    val total = LongArray(3)
    val out = FloatArray(3)
    for (n in src.indices) {
        val s = src[n]
        val f = floatArrayOf(
            ((s shr 16) and 0xFF) * (size - 1) / 255f,
            ((s shr 8) and 0xFF) * (size - 1) / 255f,
            (s and 0xFF) * (size - 1) / 255f,
        )
        val i0 = IntArray(3) { f[it].toInt().coerceIn(0, size - 2) }
        val t = FloatArray(3) { f[it] - i0[it] }
        out[0] = 0f; out[1] = 0f; out[2] = 0f
        for (dr in 0..1) for (dg in 0..1) for (db in 0..1) {
            val w = (if (dr == 1) t[0] else 1 - t[0]) *
                (if (dg == 1) t[1] else 1 - t[1]) *
                (if (db == 1) t[2] else 1 - t[2])
            if (w <= 0f) continue
            val c = (((i0[2] + db) * size) + i0[1] + dg) * size + i0[0] + dr
            for (k in 0..2) out[k] += w * grid[c * 3 + k]
        }
        val d = dst[n]
        total[0] += kotlin.math.abs((out[0] * 255).toInt() - ((d shr 16) and 0xFF))
        total[1] += kotlin.math.abs((out[1] * 255).toInt() - ((d shr 8) and 0xFF))
        total[2] += kotlin.math.abs((out[2] * 255).toInt() - (d and 0xFF))
    }
    return IntArray(3) { (total[it] / src.size).toInt() }
}

/**
 * Exports the recipe as a .cube 3D LUT, measured from the camera itself.
 *
 * The chosen RAF is developed twice: once with a neutral profile, once with the
 * recipe. Comparing the two renders pixel by pixel gives the colour transform
 * the recipe applies, which is written out for Lightroom, Capture One,
 * Darktable or Resolve.
 */
@Composable
fun LutExportDialog(recipe: Recipe, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var cube by remember { mutableStateOf<String?>(null) }
    var coverage by remember { mutableStateOf(0f) }
    var warning by remember { mutableStateOf<String?>(null) }
    // Measured on an X-T30 III: a chart reaches 75% of the cube where the best
    // photograph reached 35% — so it is the default whenever it can run.
    var donorReady by remember { mutableStateOf(DonorRaf.exists(context)) }
    var synthetic by remember { mutableStateOf(donorReady) }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val text = cube
        // A 33³ cube is around a megabyte of text
        if (uri != null && text != null) scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
                }
            }
            onDismiss()
        }
    }

    // [keep] is for a file the user just picked; the container loaded from last
    // time is already stored, and writing it back costs 5MB for nothing.
    fun export(keep: Boolean, source: suspend () -> ByteArray) {
        busy = true
        cube = null
        warning = null
        scope.launch {
            runCatching {
                val raf = withContext(Dispatchers.IO) { source() }
                // Keep the container the moment we have one that can carry a
                // chart. Waiting for the conversion to succeed made the cache
                // depend on the camera being plugged in and on the recipe
                // rendering, neither of which says anything about the file.
                if (keep && withContext(Dispatchers.IO) { DonorRaf.save(context, raf) }) {
                    donorReady = true
                    DebugLog.log("donor kept: ${RafFile.cameraModel(raf)}")
                }
                val camera = connectFujiCamera(context)
                withContext(Dispatchers.IO) {
                    try {
                        if (FujiProp.RAW_CONV_PROFILE !in camera.supportedProperties()) {
                            throw java.io.IOException(context.getString(R.string.raf_unsupported))
                        }
                        val rafModel = RafFile.cameraModel(raf)
                            ?: throw java.io.IOException(
                                context.getString(
                                    if (RafFile.isJpeg(raf)) R.string.raf_is_jpeg
                                    else R.string.raf_not_a_raf
                                )
                            )
                        val body = camera.modelName()
                        // The chart geometry and profile patching are only
                        // validated on X-Trans V-class bodies; unknown or older
                        // generations are refused rather than mis-measured.
                        val gen = CAMERA_MODELS.firstOrNull { it.first.equals(body, ignoreCase = true) }?.second
                        if (gen != Generation.X_TRANS_V) {
                            throw java.io.IOException(context.getString(R.string.lut_gen_unsupported, body))
                        }
                        if (!rafModel.equals(body, ignoreCase = true)) {
                            // Whatever we just kept is from another body, and a
                            // kept container is reused silently — drop it rather
                            // than fail identically for ever.
                            DonorRaf.forget(context)
                            donorReady = false
                            throw java.io.IOException(
                                context.getString(R.string.raf_wrong_camera, rafModel, body)
                            )
                        }

                        // The donor only lends its container: the sensor data is
                        // replaced by a chart that sweeps the whole cube, so the
                        // measurement stops depending on what was photographed.
                        if (synthetic) {
                            status = context.getString(R.string.lut_painting_chart)
                            if (!SyntheticRaf.chart(raf)) {
                                throw java.io.IOException(context.getString(R.string.raf_compressed))
                            }
                        } else {
                            DebugLog.log("no chart: measuring from the photograph itself")
                        }

                        // Both passes share one dynamic range, the recipe's, so the
                        // only thing left between them is colour.
                        //
                        // Dynamic range is a tone curve, and a tone curve does not
                        // lift every channel by the same amount — it lifts hardest
                        // wherever the signal sits lowest. In-camera RAW conversion
                        // ignores white balance, so the reference render is not
                        // neutral to begin with (measured R141 G128 B145 on an
                        // X-T30 III, green lowest). Render it at DR100 against a
                        // recipe at DR400 and the curve lifts that low green
                        // hardest, turning an imbalance into a cast: greys came out
                        // with a third of the range of green on them, black and
                        // white staying clean because a curve is pinned at both
                        // ends. The dynamic range itself is what a LUT gives up
                        // here; a raw developer has its own controls for it.
                        val range = if (recipe.dynamicRange == DynamicRange.AUTO) {
                            DynamicRange.DR100
                        } else {
                            recipe.dynamicRange
                        }
                        // D-Range Priority takes the dynamic range and the
                        // highlight/shadow tones over outright — the menus grey out
                        // and it drives them instead. Carrying it in one pass and
                        // not the other therefore reopens exactly the gap the shared
                        // dynamic range above closes, so both passes get it and it
                        // cancels. Auto is pinned off for the same reason DR Auto is:
                        // the camera decides it per scene, which is not a fixed
                        // transform and cannot be baked into a cube.
                        val priority = if (recipe.dRangePriority == DRangePriority.AUTO) {
                            DRangePriority.OFF
                        } else {
                            recipe.dRangePriority
                        }
                        // Provia with every adjustment at rest — the "before" a LUT
                        // is meant to be applied on top of.
                        val neutral = Recipe(
                            filmSimulation = FilmSimulation.PROVIA,
                            dynamicRange = range,
                            dRangePriority = priority,
                        )
                        // Grain is spatial: a LUT cannot carry it, so leaving it on
                        // only scatters every patch's pixels around its true colour.
                        // Dynamic Range Auto is pinned to the reference's DR100:
                        // "whatever the camera decides" is not a fixed transform, so
                        // it cannot be baked into a LUT, and leaving it to the profile
                        // would make the result depend on whether the second pass
                        // reused the loaded RAF or re-sent it.
                        // White balance is matched to the reference rather than
                        // sent: the profile cannot carry the mode, so the R/B
                        // shifts tuned to sit on top of that mode would be half of
                        // a coupled pair. On an X-T30 III this changes nothing —
                        // converting the same chart with the shifts at -6/-4 and
                        // at 0/0 gave identical output — because in-camera
                        // conversion ignores white balance outright. It stays as
                        // the honest thing to send, not as a fix for anything.
                        val forLut = recipe.copy(
                            grainEffect = Strength.OFF,
                            // Clarity is local contrast: what it does to a pixel
                            // depends on that pixel's surroundings, so the same
                            // input colour comes out differently in different
                            // parts of the frame. A cube maps colour to colour and
                            // has nowhere to put that. Grain was already dropped
                            // for exactly this reason and clarity was not, though
                            // the dialog has always said neither can be captured.
                            clarity = 0,
                            // Sharpness and noise reduction read a pixel's
                            // neighbours just as grain and clarity do, so the same
                            // input colour comes out one way on an edge and another
                            // in a flat area — nothing a cube can hold. They were
                            // left on while the reference render had them at rest,
                            // which measured a difference on every seam and taught
                            // the cube a colour shift that was really a halo.
                            sharpness = 0,
                            noiseReduction = 0,
                            dynamicRange = range,
                            dRangePriority = priority,
                            whiteBalance = neutral.whiteBalance,
                            kelvin = null,
                            wbShiftRed = 0,
                            wbShiftBlue = 0,
                        )

                        status = context.getString(R.string.lut_rendering_base)
                        val beforeJpeg = develop(context, camera, raf, neutral, upload = true)
                        status = context.getString(R.string.lut_rendering_recipe)
                        // The camera still holds the RAF from the first pass, so the
                        // second one usually costs no upload at all — that is tens of
                        // megabytes and most of the export's wall clock. Not every
                        // body may keep it, hence the retry.
                        val afterJpeg =
                            runCatching { develop(context, camera, raf, forLut, upload = false) }
                                .getOrElse {
                                    DebugLog.log("recipe pass needed the RAF again: ${it.message}")
                                    develop(context, camera, raf, forLut, upload = true)
                                }

                        status = context.getString(R.string.lut_building)
                        val measured = withContext(Dispatchers.Default) {
                            measure(beforeJpeg, afterJpeg)
                        }
                        val lut = measured.lut
                        val err = withContext(Dispatchers.Default) {
                            auditError(lut.build(), lut.size, measured.auditSrc, measured.auditDst)
                        }
                        DebugLog.log(
                            "lut audit on ${measured.auditSrc.size} held-back pairs: " +
                                "mean error R${err[0]} G${err[1]} B${err[2]} of 255"
                        )
                        coverage = lut.coverage()
                        DebugLog.log(
                            "LUT built: ${(coverage * 100).toInt()}% coverage, " +
                                "${measured.sampled} flat samples, ${measured.changed} changed"
                        )
                        // Both renders identical means the camera quietly ignored the
                        // recipe. That exports a valid-looking identity LUT, so say so
                        // rather than let it pass for a measurement.
                        if (measured.sampled == 0L || measured.changed * 100 < measured.sampled) {
                            warning = context.getString(R.string.lut_no_change)
                        }
                        // What the cube is a transform *of*, and what the camera
                        // was never asked to put in it. Both are settings of this
                        // export, not of the recipe, so nothing else records them.
                        // English and unlocalised on purpose: the file outlives the
                        // phone it was written on.
                        val dropped = buildList {
                            if (recipe.whiteBalance != WhiteBalance.AUTO ||
                                recipe.wbShiftRed != 0 || recipe.wbShiftBlue != 0
                            ) {
                                add(
                                    "white balance (${recipe.whiteBalance.name}" +
                                        (recipe.kelvin?.takeIf {
                                            recipe.whiteBalance == WhiteBalance.KELVIN
                                        }?.let { " ${it}K" } ?: "") +
                                        ", shift R${recipe.wbShiftRed} B${recipe.wbShiftBlue})" +
                                        " — in-camera conversion ignores it, dial it in yourself"
                                )
                            }
                            if (recipe.grainEffect != Strength.OFF) add("grain")
                            if (recipe.clarity != 0) add("clarity ${recipe.clarity}")
                            if (recipe.sharpness != 0) add("sharpness ${recipe.sharpness}")
                            if (recipe.noiseReduction != 0) {
                                add("noise reduction ${recipe.noiseReduction}")
                            }
                        }
                        cube = lut.toCubeText(
                            recipe.name,
                            buildList {
                                add("INPUT: sRGB — set the application's colour space to sRGB, not Rec.709")
                                add(
                                    "BASE: apply on a Provia render at ${range.label}" +
                                        if (priority != DRangePriority.OFF) {
                                            ", D-Range Priority ${priority.name.lowercase()}"
                                        } else ""
                                )
                                if (dropped.isNotEmpty()) {
                                    add("NOT IN THIS CUBE: ${dropped.joinToString("; ")}")
                                }
                            },
                        )
                        status = null
                    } finally {
                        camera.close()
                    }
                }
            }.onFailure {
                DebugLog.log("LUT export failed: ${it.message ?: it.javaClass.simpleName}")
                status = context.getString(R.string.camera_failed, it.message ?: it.javaClass.simpleName)
            }
            busy = false
        }
    }

    val rafPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            export(keep = true) {
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.lut_export)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (busy) CircularProgressIndicator(Modifier.padding(8.dp))
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (!busy && cube == null && status == null) {
                    Text(
                        stringResource(
                            when {
                                !synthetic -> R.string.lut_hint
                                donorReady -> R.string.lut_hint_chart_ready
                                else -> R.string.lut_hint_chart_needs_donor
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Checkbox(checked = synthetic, onCheckedChange = { synthetic = it })
                        Text(
                            stringResource(R.string.lut_synthetic),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    // With a container kept, the chart never asks for a file again,
                    // so this is the only way back to the picker — and the only way
                    // out of a container that turns out to be unusable.
                    if (donorReady) {
                        TextButton(onClick = {
                            DonorRaf.reset(context)
                            donorReady = false
                            DebugLog.log("donor container reset by the user")
                        }) {
                            Text(
                                stringResource(R.string.lut_forget_donor),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (cube != null) {
                    Text(
                        stringResource(R.string.lut_coverage, (coverage * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    warning?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.lut_caveat),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (cube == null) {
                // A chart needs no particular photo, so once any RAF has been
                // seen the button just runs — no picker, nothing to choose.
                val ready = synthetic && donorReady
                Button(
                    enabled = !busy,
                    onClick = {
                        if (ready) export(keep = false) {
                            DonorRaf.load(context)
                                ?: throw java.io.IOException(context.getString(R.string.raf_no_donor))
                        }
                        else rafPicker.launch(arrayOf("*/*"))
                    },
                ) {
                    Text(stringResource(if (ready) R.string.lut_build_now else R.string.raf_choose))
                }
            } else {
                Button(onClick = { saver.launch(sanitize(recipe.name) + ".cube") }) {
                    Text(stringResource(R.string.lut_save))
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

/** The profile slot holding the dynamic range, as 100 / 200 / 400. */
private const val DR_SLOT = 6

/**
 * Apply [recipe], convert, and bring back the JPEG. [upload] sends the RAF
 * first; the second pass tries to reuse the one the camera already holds.
 */
private fun develop(
    context: android.content.Context,
    camera: com.beudbeud.fuji.data.ptp.FujiCamera,
    raf: ByteArray,
    recipe: Recipe,
    upload: Boolean,
): ByteArray {
    if (upload) camera.sendRaf(raf)
    val patched = recipe.patchProfile(camera.getProfile(quiet = true))
    // Both passes' profiles side by side: the difference between them is the
    // whole of what the LUT can possibly measure. Named by the recipe, not by
    // its film simulation — every recipe built on Eterna used to log "Eterna",
    // which hid that an export was running on a different recipe than the one
    // being read off the screen.
    DebugLog.log(
        "patchProfile out [${recipe.name.ifBlank { "neutral" }} / " +
            "${recipe.filmSimulation.label}]: ${profileDump(patched)}"
    )
    camera.setProfile(patched)
    // Written is not accepted. The camera silently clamps a slot it will not
    // take — index 12 is one it always clamps, whatever white balance we hand
    // it — and every rejection so far has been found by noticing a render that
    // looked wrong, not by being told. Read the profile back and name the slots
    // that did not survive.
    val wanted = profileParams(patched)
    val got = profileParams(camera.getProfile(quiet = true))
    val refused = wanted.indices.filter { it < got.size && wanted[it] != got[it] }
    if (refused.isNotEmpty()) {
        DebugLog.log(
            "profile refused: " + refused.joinToString(" ") { "$it=${wanted[it]}→${got[it]}" }
        )
    }
    // The dynamic range is the one rejection that cannot be shrugged off: a RAF
    // converts at the range it was shot at or below, never above, so a DR400
    // recipe on a DR100 frame comes back as a DR100 render that measures
    // perfectly and is a cube of a rendering nobody asked for. Both passes send
    // the same range, so this fails on the first of them, before the second
    // conversion is spent.
    if (DR_SLOT in refused) {
        throw java.io.IOException(
            context.getString(R.string.lut_dr_refused, wanted[DR_SLOT], got[DR_SLOT])
        )
    }
    camera.triggerConversion()
    return camera.waitForResult()
}

@Suppress("DEPRECATION")
private fun regionDecoder(jpeg: ByteArray): android.graphics.BitmapRegionDecoder =
    android.graphics.BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false)
        ?: throw java.io.IOException("Could not decode the converted JPEG")

/**
 * Measures the two renders against each other at full resolution.
 *
 * Only pixels sitting inside a flat area contribute. On a seam between two
 * patches the demosaic, the JPEG's half-resolution chroma and the DCT all smear
 * the two colours together — and the neutral render blends them differently from
 * the recipe render, because a recipe bends contrast and saturation non
 * linearly. Such a pair is not a colour transform, it is an artefact of the
 * boundary, and feeding it in teaches the cube mappings that do not exist. The
 * same test throws out texture edges when the source is a photograph instead of
 * a chart, for exactly the same reason.
 */
private fun measure(beforeJpeg: ByteArray, afterJpeg: ByteArray): Measured {
    val decBefore = regionDecoder(beforeJpeg)
    val decAfter = regionDecoder(afterJpeg)
    // Both renders come from one RAF, so they line up pixel for pixel
    val w = minOf(decBefore.width, decAfter.width)
    val h = minOf(decBefore.height, decAfter.height)
    val lut = CubeLut()
    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
    val pixBefore = IntArray(w * STRIP_ROWS)
    val pixAfter = IntArray(w * STRIP_ROWS)
    var sampled = 0L
    var changed = 0L
    // How many of the 256 output levels the neutral render actually produces, per
    // channel. The chart sweeps 33 raw levels per axis; if far fewer come out the
    // other side, the sweep is collapsing before it reaches the cube, and no
    // amount of clean sampling can cover cells the camera never renders.
    val seen = Array(3) { BooleanArray(256) }
    val meanBefore = LongArray(3)
    val meanAfter = LongArray(3)
    val auditSrc = ArrayList<Int>()
    val auditDst = ArrayList<Int>()

    var top = 0
    while (top < h) {
        val rows = minOf(STRIP_ROWS, h - top)
        val rect = android.graphics.Rect(0, top, w, top + rows)
        // One strip bitmap alive at a time; the pixels outlive it, the bitmap does not
        decBefore.decodeRegion(rect, opts).let {
            it.getPixels(pixBefore, 0, w, 0, 0, w, rows)
            it.recycle()
        }
        decAfter.decodeRegion(rect, opts).let {
            it.getPixels(pixAfter, 0, w, 0, 0, w, rows)
            it.recycle()
        }

        // The first and last row of a strip have no neighbour inside it. That
        // costs two rows in every 256, which is cheaper than overlapping reads.
        for (y in 1 until rows - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val s = pixBefore[i]
                val sr = (s shr 16) and 0xFF
                val sg = (s shr 8) and 0xFF
                val sb = s and 0xFF
                if (!flat(sr, sg, sb, pixBefore[i - 1]) ||
                    !flat(sr, sg, sb, pixBefore[i + 1]) ||
                    !flat(sr, sg, sb, pixBefore[i - w]) ||
                    !flat(sr, sg, sb, pixBefore[i + w])
                ) continue
                val d = pixAfter[i]
                val dr = (d shr 16) and 0xFF
                val dg = (d shr 8) and 0xFF
                val db = d and 0xFF
                lut.accumulate(sr, sg, sb, dr, dg, db)
                seen[0][sr] = true
                seen[1][sg] = true
                seen[2][sb] = true
                // The two renders' own colour balance, before the cube touches
                // them. If the recipe render is already far greener than the
                // neutral one, the camera made it so and the cube is only
                // reporting it; if the two are level, the fault is downstream.
                meanBefore[0] += sr; meanBefore[1] += sg; meanBefore[2] += sb
                meanAfter[0] += dr; meanAfter[1] += dg; meanAfter[2] += db
                if (sampled % AUDIT_STRIDE == 0L) {
                    auditSrc.add(s and 0xFFFFFF)
                    auditDst.add(d and 0xFFFFFF)
                }
                sampled++
                if (kotlin.math.abs(dr - sr) > CHANGE_TOLERANCE ||
                    kotlin.math.abs(dg - sg) > CHANGE_TOLERANCE ||
                    kotlin.math.abs(db - sb) > CHANGE_TOLERANCE
                ) changed++
            }
        }
        top += rows
    }
    decBefore.recycle()
    decAfter.recycle()
    DebugLog.log(
        "measured ${w}x$h, distinct neutral levels R=${seen[0].count { it }} " +
            "G=${seen[1].count { it }} B=${seen[2].count { it }} of 256"
    )
    if (sampled > 0) {
        fun mean(v: LongArray) = "R${v[0] / sampled} G${v[1] / sampled} B${v[2] / sampled}"
        DebugLog.log("render means: neutral ${mean(meanBefore)} | recipe ${mean(meanAfter)}")
    }
    return Measured(lut, sampled, changed, auditSrc.toIntArray(), auditDst.toIntArray())
}

/** True when [other] is close enough to be the same flat colour. */
private fun flat(r: Int, g: Int, b: Int, other: Int): Boolean =
    kotlin.math.abs(((other shr 16) and 0xFF) - r) <= FLAT_TOLERANCE &&
        kotlin.math.abs(((other shr 8) and 0xFF) - g) <= FLAT_TOLERANCE &&
        kotlin.math.abs((other and 0xFF) - b) <= FLAT_TOLERANCE

private fun sanitize(name: String) =
    name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "recipe" }
