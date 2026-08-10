package com.beudbeud.fuji.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.beudbeud.fuji.R
import com.beudbeud.fuji.data.DebugLog
import com.beudbeud.fuji.data.RafFile
import com.beudbeud.fuji.data.RecipeRepository
import com.beudbeud.fuji.data.ptp.FujiProp
import com.beudbeud.fuji.data.ptp.patchProfile
import com.beudbeud.fuji.data.ptp.profileDump
import com.beudbeud.fuji.data.ptp.profileParams
import com.beudbeud.fuji.model.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Develops a RAF in-camera with the recipe's settings (X RAW Studio protocol)
 * and shows the resulting JPEG.
 */
@Composable
fun RafPreviewDialog(recipe: Recipe, repo: RecipeRepository, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var resultJpeg by remember { mutableStateOf<ByteArray?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }

    val rafPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        resultJpeg = null
        preview = null
        scope.launch {
            status = context.getString(R.string.raf_uploading)
            runCatching {
                val raf = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                }
                DebugLog.log("RAF preview \"${recipe.name}\": ${raf.size / 1024 / 1024}MB")
                val camera = connectFujiCamera(context)
                withContext(Dispatchers.IO) {
                    try {
                        // If the body does not advertise the X RAW Studio properties,
                        // no amount of retrying will produce a conversion.
                        if (FujiProp.RAW_CONV_PROFILE !in camera.supportedProperties()) {
                            throw java.io.IOException(context.getString(R.string.raf_unsupported))
                        }
                        // In-camera conversion only accepts a file this body shot.
                        // Checking here turns a bare 0x2002 later into a real answer.
                        val rafModel = RafFile.cameraModel(raf)
                        val body = camera.modelName()
                        DebugLog.log(
                            "RAF from \"$rafModel\" v${RafFile.formatVersion(raf)} " +
                                "${raf.size / 1024}KB, camera is \"$body\""
                        )
                        if (rafModel == null) {
                            DebugLog.log("not a RAF: ${RafFile.describe(raf)}")
                            throw java.io.IOException(
                                context.getString(
                                    if (RafFile.isJpeg(raf)) R.string.raf_is_jpeg
                                    else R.string.raf_not_a_raf
                                )
                            )
                        }
                        if (!rafModel.equals(body, ignoreCase = true)) {
                            throw java.io.IOException(
                                context.getString(R.string.raf_wrong_camera, rafModel, body)
                            )
                        }
                        camera.sendRaf(raf)
                        status = context.getString(R.string.raf_developing)
                        val patched = recipe.patchProfile(camera.getProfile())
                        DebugLog.log("patchProfile out: ${profileDump(patched)}")
                        camera.setProfile(patched)
                        // Read it straight back: slots the camera rejected, ignored
                        // or clamped are the ones our index map has wrong.
                        runCatching {
                            val after = profileParams(camera.getProfile())
                            val sent = profileParams(patched)
                            val drift = sent.indices
                                .filter { it < after.size && after[it] != sent[it] }
                                .joinToString(" ") { "$it: sent=${sent[it]} kept=${after[it]}" }
                            DebugLog.log(
                                if (drift.isEmpty()) "profile readback: identical"
                                else "profile readback drift — $drift"
                            )
                            // A clamped slot means our map is wrong about it. Ask the
                            // camera directly what it will take there, rather than
                            // guessing one value per round trip with the user.
                            sent.indices
                                .filter { it < after.size && after[it] != sent[it] }
                                .forEach { camera.probeProfileIndex(patched, it, PROBE_VALUES) }
                        }
                        camera.triggerConversion()
                        val jpeg = camera.waitForResult()
                        status = context.getString(R.string.raf_downloading)
                        resultJpeg = jpeg
                        preview = decodeSampled(jpeg, 1280)
                        status = null
                    } finally {
                        camera.close()
                    }
                }
            }.onFailure {
                DebugLog.log("RAF preview failed: ${it.message ?: it.javaClass.simpleName}")
                status = context.getString(R.string.camera_failed, it.message ?: it.javaClass.simpleName)
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.raf_preview)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                preview?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
                if (busy) CircularProgressIndicator(Modifier.padding(8.dp))
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                if (!busy && preview == null && status == null) {
                    Text(stringResource(R.string.raf_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (resultJpeg == null) {
                Button(
                    enabled = !busy,
                    // RAF has no standard MIME type — accept everything
                    onClick = { rafPicker.launch(arrayOf("*/*")) },
                ) { Text(stringResource(R.string.raf_choose)) }
            } else {
                Button(onClick = {
                    resultJpeg?.let { repo.upsert(recipe.copy(photos = recipe.photos + repo.addPhotoBytes(it))) }
                    onDismiss()
                }) { Text(stringResource(R.string.raf_add_photo)) }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

private fun decodeSampled(jpeg: ByteArray, maxDim: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
        sample *= 2
    }
    val bitmap = BitmapFactory.decodeByteArray(
        jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return null
    // The camera records the shooting orientation in EXIF rather than rotating
    // the pixels; BitmapFactory ignores it, so a portrait frame shows on its side.
    val degrees = exifRotation(jpeg)
    if (degrees == 0f) return bitmap
    return Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height,
        Matrix().apply { postRotate(degrees) }, true,
    )
}

private fun exifRotation(jpeg: ByteArray): Float = runCatching {
    when (ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(
        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
    )) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
}.getOrDefault(0f)

/** Small ordinals first, then the preset-space white balance codes. */
private val PROBE_VALUES =
    (0..12).toList() + listOf(0x8001, 0x8002, 0x8003, 0x8006, 0x8007, 0x8008)
