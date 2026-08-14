package com.beudbeud.fuji.ui

import android.graphics.Bitmap
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
import com.beudbeud.fuji.model.CAMERA_MODELS
import com.beudbeud.fuji.model.DynamicRange
import com.beudbeud.fuji.model.FilmSimulation
import com.beudbeud.fuji.model.Generation
import com.beudbeud.fuji.model.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Long edge the two renders are decoded at. 2048 makes the decoder halve the
 * 6240px render instead of quartering it, so a 24px sensor patch still lands on
 * a 12px block and survives with a clean middle.
 */
private const val SAMPLE_DIM = 2048

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
    // Measured on an X-T30 III: a chart reaches 75% of the cube where the best
    // photograph reached 35% — so it is the default whenever it can run.
    var donorReady by remember { mutableStateOf(DonorRaf.exists(context)) }
    var synthetic by remember { mutableStateOf(donorReady) }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val text = cube
        if (uri != null && text != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
            }
            onDismiss()
        }
    }

    // Either a file the user just picked, or the container kept from last time
    val export = { source: suspend () -> ByteArray ->
        busy = true
        cube = null
        scope.launch {
            runCatching {
                val raf = withContext(Dispatchers.IO) { source() }
                // Keep the container the moment we have one that can carry a
                // chart. Waiting for the conversion to succeed made the cache
                // depend on the camera being plugged in and on the recipe
                // rendering, neither of which says anything about the file.
                if (DonorRaf.save(context, raf)) {
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
                        }

                        // Provia with every adjustment at rest — the "before" a LUT
                        // is meant to be applied on top of.
                        val neutral = Recipe(
                            filmSimulation = FilmSimulation.PROVIA,
                            dynamicRange = DynamicRange.DR100,
                        )
                        status = context.getString(R.string.lut_rendering_base)
                        val before = develop(camera, raf, neutral)
                        status = context.getString(R.string.lut_rendering_recipe)
                        val after = develop(camera, raf, recipe)

                        status = context.getString(R.string.lut_building)
                        val lut = withContext(Dispatchers.Default) { build(before, after) }
                        before.recycle()
                        after.recycle()
                        coverage = lut.coverage()
                        DebugLog.log("LUT built: ${(coverage * 100).toInt()}% coverage")
                        cube = lut.toCubeText(recipe.name)
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
        Unit
    }

    val rafPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            export { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
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
                }
                if (cube != null) {
                    Text(
                        stringResource(R.string.lut_coverage, (coverage * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
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
                        if (ready) export {
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

/** Upload, apply [recipe], convert, decode. The RAF is resent per pass. */
private fun develop(
    camera: com.beudbeud.fuji.data.ptp.FujiCamera,
    raf: ByteArray,
    recipe: Recipe,
): Bitmap {
    camera.sendRaf(raf)
    camera.setProfile(recipe.patchProfile(camera.getProfile(quiet = true)))
    camera.triggerConversion()
    return decodeSampled(camera.waitForResult(), SAMPLE_DIM)
        ?: throw java.io.IOException("Could not decode the converted JPEG")
}

private fun build(before: Bitmap, after: Bitmap): CubeLut {
    val lut = CubeLut()
    // Both renders come from one RAF, so they line up pixel for pixel
    val w = minOf(before.width, after.width)
    val h = minOf(before.height, after.height)
    val rowBefore = IntArray(w)
    val rowAfter = IntArray(w)
    for (y in 0 until h) {
        before.getPixels(rowBefore, 0, w, 0, y, w, 1)
        after.getPixels(rowAfter, 0, w, 0, y, w, 1)
        for (x in 0 until w) {
            val s = rowBefore[x]
            val d = rowAfter[x]
            lut.accumulate(
                (s shr 16) and 0xFF, (s shr 8) and 0xFF, s and 0xFF,
                (d shr 16) and 0xFF, (d shr 8) and 0xFF, d and 0xFF,
            )
        }
    }
    return lut
}

private fun sanitize(name: String) =
    name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "recipe" }
