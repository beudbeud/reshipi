package com.beudbeud.fuji.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.beudbeud.fuji.R
import com.beudbeud.fuji.data.DebugLog
import com.beudbeud.fuji.data.ptp.FujiCamera
import com.beudbeud.fuji.data.ptp.toPresetProps
import com.beudbeud.fuji.model.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

@Composable
fun SendToCameraDialog(recipe: Recipe, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var slot by remember { mutableIntStateOf(7) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

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
                    busy = true
                    status = context.getString(R.string.camera_writing)
                    scope.launch {
                        status = sendRecipe(context, recipe, slot)
                        busy = false
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
}

/** Full send flow: find camera, get permission, write preset. Returns a status message. */
private suspend fun sendRecipe(context: Context, recipe: Recipe, slot: Int): String {
    DebugLog.log("send \"${recipe.name}\" → C$slot")
    val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    val device = FujiCamera.findDevice(manager)
        ?: return context.getString(R.string.camera_not_found).also {
            DebugLog.log("no Fuji USB device (${manager.deviceList.size} devices attached)")
        }
    if (!requestUsbPermission(context, manager, device)) {
        DebugLog.log("USB permission denied")
        return context.getString(R.string.camera_permission_denied)
    }
    return withContext(Dispatchers.IO) {
        runCatching {
            val camera = FujiCamera.open(manager, device)
            try {
                camera.openSession()
                if (0xD18C !in camera.supportedProperties()) {
                    return@runCatching context.getString(R.string.camera_no_presets)
                }
                val result = camera.writePreset(slot, recipe.name.take(25), recipe.toPresetProps())
                when {
                    !result.ok -> context.getString(R.string.camera_failed, result.warnings.joinToString())
                    result.warnings.isEmpty() -> context.getString(R.string.camera_done, slot)
                    else -> context.getString(R.string.camera_done, slot) + "\n" +
                        result.warnings.joinToString("\n")
                }
            } finally {
                camera.close()
            }
        }.getOrElse {
            DebugLog.log("send failed: ${it.message ?: it.javaClass.simpleName}")
            context.getString(R.string.camera_failed, it.message ?: it.javaClass.simpleName)
        }
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
