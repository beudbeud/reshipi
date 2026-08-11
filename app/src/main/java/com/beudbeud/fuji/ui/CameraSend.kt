package com.beudbeud.fuji.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.beudbeud.fuji.R
import com.beudbeud.fuji.data.DebugLog
import com.beudbeud.fuji.data.RecipeRepository
import com.beudbeud.fuji.data.ptp.FujiCamera
import com.beudbeud.fuji.data.ptp.WB_CODE
import com.beudbeud.fuji.data.ptp.recipeFromPresetProps
import com.beudbeud.fuji.data.ptp.toPresetProps
import com.beudbeud.fuji.model.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Camera slot names are capped at 22 characters. */
internal const val SLOT_NAME_MAX = 22

@Composable
fun SendToCameraDialog(recipe: Recipe, repo: RecipeRepository, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var slot by remember { mutableIntStateOf(7) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // Name of the recipe currently in the slot, when asking before overwriting
    var askOverwrite by remember { mutableStateOf<String?>(null) }

    val doSend = { backup: Boolean ->
        busy = true
        status = context.getString(R.string.camera_writing)
        scope.launch {
            val (ok, message) = sendRecipe(context, recipe, slot, repo.takeIf { backup })
            busy = false
            if (ok) {
                // Clean success: close and confirm with a toast
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                onDismiss()
            } else {
                status = message
            }
        }
        Unit
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.send_to_camera)) },
        text = {
            Column {
                Text(stringResource(R.string.camera_slot))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                    for (s in 1..7) {
                        FilterChip(
                            selected = slot == s,
                            onClick = { if (!busy) slot = s },
                            label = { Text("C$s") },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    // Occupied slot → confirm (and offer a backup) before writing
                    busy = true
                    status = context.getString(R.string.camera_reading)
                    scope.launch {
                        val existing = readSlotName(context, slot)
                        if (existing == null) {
                            doSend(false)
                        } else {
                            busy = false
                            status = null
                            askOverwrite = existing
                        }
                    }
                },
            ) { Text(stringResource(R.string.send)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )

    askOverwrite?.let { existing ->
        OverwriteSlotDialog(
            slot = slot,
            existingName = existing,
            onDismiss = { askOverwrite = null },
            onConfirm = { backup ->
                askOverwrite = null
                doSend(backup)
            },
        )
    }
}

/** "C2 already contains X — overwrite?", with the backup choice where it matters. */
@Composable
internal fun OverwriteSlotDialog(
    slot: Int,
    existingName: String,
    onDismiss: () -> Unit,
    onConfirm: (backup: Boolean) -> Unit,
) {
    var backup by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.slot_overwrite_title, slot)) },
        text = {
            Column {
                Text(stringResource(R.string.slot_overwrite_text, slot, existingName))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp).clickable { backup = !backup },
                ) {
                    Checkbox(checked = backup, onCheckedChange = { backup = it })
                    Text(
                        stringResource(R.string.backup_slot_first),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(backup) }) { Text(stringResource(R.string.overwrite)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** The name of the recipe in [slot], or null when the slot is empty or unreadable.
 *  Connection problems end up as null too: the write that follows fails with the
 *  proper message, which beats failing twice. */
private suspend fun readSlotName(context: Context, slot: Int): String? = runCatching {
    val camera = connectFujiCamera(context)
    withContext(Dispatchers.IO) {
        try {
            camera.readPresets(slot..slot).firstOrNull()?.name?.takeIf { it.isNotBlank() }
        } finally {
            camera.close()
        }
    }
}.getOrNull()

/**
 * Write [recipe] into slot [slot], backing the slot up into [repo] first when given.
 * Returns a message to show, or null when it succeeded with nothing to report.
 */
internal suspend fun writeRecipeToSlot(
    context: Context,
    recipe: Recipe,
    slot: Int,
    repo: RecipeRepository?,
): String? {
    val (ok, message) = sendRecipe(context, recipe, slot, repo)
    return if (ok) null else message
}

/** Find the camera, get USB permission, open a PTP session. Throws with a user-facing message. */
internal suspend fun connectFujiCamera(context: Context): FujiCamera {
    val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    val device = FujiCamera.findDevice(manager)
    if (device == null) {
        DebugLog.log("no Fuji USB device (${manager.deviceList.size} devices attached)")
        throw java.io.IOException(context.getString(R.string.camera_not_found))
    }
    if (!requestUsbPermission(context, manager, device)) {
        DebugLog.log("USB permission denied")
        throw java.io.IOException(context.getString(R.string.camera_permission_denied))
    }
    return withContext(Dispatchers.IO) {
        FujiCamera.open(manager, device).also { it.openSession() }
    }
}

/**
 * Full send flow. Returns (cleanSuccess, message) — warnings keep the dialog open.
 * When [backupRepo] is given, the slot's current content is read and saved to the
 * library before it gets overwritten.
 */
private suspend fun sendRecipe(
    context: Context,
    recipe: Recipe,
    slot: Int,
    backupRepo: RecipeRepository?,
): Pair<Boolean, String> {
    DebugLog.log("send \"${recipe.name}\" → C$slot (backup=${backupRepo != null})")
    return runCatching {
        val camera = connectFujiCamera(context)
        withContext(Dispatchers.IO) {
            try {
                if (0xD18C !in camera.supportedProperties()) {
                    return@withContext false to context.getString(R.string.camera_no_presets)
                }
                val notes = mutableListOf<String>()

                if (backupRepo != null) {
                    val existing = camera.readPresets(slot..slot).firstOrNull()
                    // An empty slot has nothing worth keeping
                    if (existing != null && existing.name.isNotBlank() && existing.props.isNotEmpty()) {
                        val saved = recipeFromPresetProps(
                            existing.name, existing.props, camera.modelName(),
                        ).let { it.copy(tags = it.tags + "backup") }
                        if (backupRepo.addImported(listOf(saved)).added > 0) {
                            DebugLog.log("backed up C$slot as \"${saved.name}\"")
                            notes += context.getString(R.string.backup_slot_saved, slot, saved.name)
                        }
                    }
                }

                val result = camera.writePreset(slot, recipe.name.take(SLOT_NAME_MAX), recipe.toPresetProps())
                // White balances without a confirmed camera code are not written
                if (WB_CODE[recipe.whiteBalance] == null) {
                    notes += context.getString(R.string.camera_wb_custom_warning)
                }
                when {
                    !result.ok -> false to context.getString(R.string.camera_failed, result.warnings.joinToString())
                    result.warnings.isEmpty() && notes.isEmpty() ->
                        true to context.getString(R.string.camera_done, slot)
                    else -> false to (context.getString(R.string.camera_done, slot) + "\n" +
                        (notes + result.warnings).joinToString("\n"))
                }
            } finally {
                camera.close()
            }
        }
    }.getOrElse {
        DebugLog.log("send failed: ${it.message ?: it.javaClass.simpleName}")
        false to context.getString(R.string.camera_failed, it.message ?: it.javaClass.simpleName)
    }
}

private suspend fun requestUsbPermission(
    context: Context,
    manager: UsbManager,
    device: UsbDevice,
): Boolean {
    if (manager.hasPermission(device)) return true
    return suspendCancellableCoroutine { cont ->
        val action = "com.beudbeud.fuji.USB_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                context.unregisterReceiver(this)
                if (cont.isActive) {
                    cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(
            context, 0, Intent(action).setPackage(context.packageName), flags,
        )
        manager.requestPermission(device, pending)
        cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
    }
}
