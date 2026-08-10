package com.beudbeud.fuji.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.beudbeud.fuji.R
import com.beudbeud.fuji.data.DebugLog
import com.beudbeud.fuji.data.RecipeRepository
import com.beudbeud.fuji.data.ptp.recipeFromPresetProps
import com.beudbeud.fuji.model.Recipe
import com.beudbeud.fuji.model.formatSigned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "My camera": what is actually loaded in slots C1-C7 right now, with the
 * ability to import a slot into the library or overwrite it with a recipe.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSlotsScreen(
    recipes: List<Recipe>,
    repo: RecipeRepository,
    onBack: () -> Unit,
    onOpenRecipe: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val slots: SnapshotStateList<Pair<Int, Recipe?>> = remember { mutableStateListOf() }
    var pickForSlot by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        busy = true
        status = context.getString(R.string.camera_reading)
        slots.clear()
        runCatching {
            val camera = connectFujiCamera(context)
            withContext(Dispatchers.IO) {
                try {
                    if (0xD18C !in camera.supportedProperties()) {
                        return@withContext context.getString(R.string.camera_no_presets)
                    }
                    val model = camera.modelName()
                    camera.readPresets().forEach { preset ->
                        val recipe = if (preset.name.isBlank() || preset.props.isEmpty()) null
                        else recipeFromPresetProps(preset.name, preset.props, model)
                        slots += preset.slot to recipe
                    }
                    null
                } finally {
                    camera.close()
                }
            }
        }.onSuccess { status = it }
            .onFailure {
                DebugLog.log("slot board failed: ${it.message ?: it.javaClass.simpleName}")
                status = context.getString(R.string.camera_failed, it.message ?: it.javaClass.simpleName)
            }
        busy = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.camera_slots)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(enabled = !busy, onClick = { reload++ }) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (busy) CircularProgressIndicator(Modifier.padding(16.dp).align(Alignment.CenterHorizontally))
            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(slots, key = { it.first }) { (slot, recipe) ->
                    // A slot written from the library keeps its (truncated) name, so
                    // that is the only handle we have to link it back to a recipe.
                    val known = recipe?.let { r ->
                        recipes.firstOrNull { it.name.take(SLOT_NAME_MAX).equals(r.name, ignoreCase = true) }
                    }
                    SlotCard(
                        slot = slot,
                        recipe = recipe,
                        known = known,
                        enabled = !busy,
                        onImport = {
                            if (recipe != null) {
                                repo.upsert(recipe)
                                status = context.getString(R.string.import_done, 1)
                            }
                        },
                        onWrite = { pickForSlot = slot },
                        onOpenKnown = { known?.let { onOpenRecipe(it.id) } },
                    )
                }
            }
        }
    }

    if (pickForSlot > 0) {
        RecipePickerDialog(
            recipes = recipes,
            slot = pickForSlot,
            onDismiss = { pickForSlot = 0 },
            onPicked = { picked ->
                val target = pickForSlot
                pickForSlot = 0
                scope.launch {
                    busy = true
                    status = context.getString(R.string.camera_writing)
                    // Reuse the send path so the slot is backed up first
                    writeRecipeToSlot(context, picked, target, repo)?.let { status = it }
                    busy = false
                    reload++
                }
            },
        )
    }
}

@Composable
private fun SlotCard(
    slot: Int,
    recipe: Recipe?,
    known: Recipe?,
    enabled: Boolean,
    onImport: () -> Unit,
    onWrite: () -> Unit,
    onOpenKnown: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable(enabled = recipe != null) { expanded = !expanded }
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 12.dp),
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            recipe?.let { simAccent(it.filmSimulation) }
                                ?: MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("C$slot", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        recipe?.name ?: stringResource(R.string.slot_empty),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    recipe?.let {
                        Text(
                            it.filmSimulation.label + " · " + summarize(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (recipe != null) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (expanded && recipe != null) {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    HorizontalDivider(Modifier.padding(bottom = 4.dp))
                    RecipeSettingsRows(recipe)
                }
            }
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(end = 4.dp, bottom = 4.dp),
            ) {
                // Already in the library under this name: opening it beats importing a duplicate
                if (known != null) {
                    TextButton(enabled = enabled, onClick = onOpenKnown) {
                        Text(stringResource(R.string.open_in_library))
                    }
                } else if (recipe != null) {
                    TextButton(enabled = enabled, onClick = onImport) {
                        Text(stringResource(R.string.import_from_camera))
                    }
                }
                TextButton(enabled = enabled, onClick = onWrite) {
                    Text(stringResource(R.string.write_here))
                }
            }
        }
    }
}

/** One-line gist of the settings, so the collapsed card already says something useful. */
@Composable
private fun summarize(r: Recipe): String = listOf(
    stringResource(r.whiteBalance.labelRes),
    "DR${r.dynamicRange.label.filter { it.isDigit() }.ifEmpty { "A" }}",
    "H${formatSigned(r.highlight)}",
    "S${formatSigned(r.shadow)}",
).joinToString(" · ")

@Composable
private fun RecipePickerDialog(
    recipes: List<Recipe>,
    slot: Int,
    onDismiss: () -> Unit,
    onPicked: (Recipe) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("C$slot — ${stringResource(R.string.write_here)}") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                recipes.sortedBy { it.name.lowercase() }.forEach { r ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPicked(r) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(r.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            r.filmSimulation.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
