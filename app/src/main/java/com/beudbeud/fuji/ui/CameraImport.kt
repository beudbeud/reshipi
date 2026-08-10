package com.beudbeud.fuji.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.beudbeud.fuji.data.DebugLog
import com.beudbeud.fuji.data.RecipeRepository
import com.beudbeud.fuji.data.ptp.recipeFromPresetProps
import com.beudbeud.fuji.model.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reads the camera's C1-C7 custom presets over USB and lets the user pick which
 * ones to add to the library.
 */
@Composable
fun ImportFromCameraDialog(repo: RecipeRepository, onDismiss: () -> Unit, onImported: (Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    val found = remember { mutableStateListOf<Pair<Int, Recipe>>() }
    val selected = remember { mutableStateListOf<Int>() }

    LaunchedEffect(Unit) {
        status = context.getString(R.string.camera_reading)
        runCatching {
            val camera = connectFujiCamera(context)
            withContext(Dispatchers.IO) {
                try {
                    if (0xD18C !in camera.supportedProperties()) {
                        return@withContext context.getString(R.string.camera_no_presets)
                    }
                    val model = camera.modelName()
                    camera.readPresets().forEach { preset ->
                        // A slot the user never configured has no name and nothing to import
                        if (preset.name.isBlank() || preset.props.isEmpty()) return@forEach
                        found += preset.slot to
                            recipeFromPresetProps(preset.name, preset.props, model)
                        selected += preset.slot
                    }
                    null
                } finally {
                    camera.close()
                }
            }
        }.onSuccess { message ->
            status = message ?: if (found.isEmpty()) context.getString(R.string.camera_no_slots) else null
        }.onFailure {
            DebugLog.log("readPresets failed: ${it.message ?: it.javaClass.simpleName}")
            status = context.getString(R.string.camera_failed, it.message ?: it.javaClass.simpleName)
        }
        busy = false
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.import_from_camera)) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (busy) CircularProgressIndicator(Modifier.padding(8.dp))
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                found.forEach { (slot, recipe) ->
                    ListItem(
                        headlineContent = { Text("C$slot — ${recipe.name}") },
                        supportingContent = { Text(recipe.filmSimulation.label) },
                        leadingContent = {
                            Checkbox(
                                checked = slot in selected,
                                onCheckedChange = { on ->
                                    if (on) selected += slot else selected -= slot
                                },
                            )
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && selected.isNotEmpty(),
                onClick = {
                    val picked = found.filter { it.first in selected }.map { it.second }
                    scope.launch {
                        picked.forEach { repo.upsert(it) }
                        onImported(picked.size)
                        onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.import_selected, selected.size)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
