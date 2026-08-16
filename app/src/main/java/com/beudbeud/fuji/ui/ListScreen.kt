package com.beudbeud.fuji.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import com.beudbeud.fuji.model.FilmSimulation
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.beudbeud.fuji.R
import com.beudbeud.fuji.data.DebugLog
import com.beudbeud.fuji.data.FujiStyleCard
import com.beudbeud.fuji.data.RecipeRepository
import com.beudbeud.fuji.data.ptp.MONO_SIMS
import com.beudbeud.fuji.model.Recipe
import com.beudbeud.fuji.model.cameraLabel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    recipes: List<Recipe>,
    repo: RecipeRepository,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onCreateFromPhoto: (Recipe) -> Unit,
    onOpenCameraSlots: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var favOnly by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(SortOrder.NAME) }
    var simFilter by remember { mutableStateOf<FilmSimulation?>(null) }
    var cameraFilter by remember { mutableStateOf<String?>(null) }
    // null = all, true = black & white recipes, false = colour recipes
    var monoFilter by remember { mutableStateOf<Boolean?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var showTextImport by remember { mutableStateOf(false) }
    var showCameraImport by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Full backup: recipes + their photos, in one archive
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            // A backup carries every photo in the library, so it is never "small
            // enough" to write from the launcher callback's own thread.
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)!!.use { repo.exportZip(it) }
                    }.isSuccess
                }
                snackbar.showSnackbar(context.getString(if (ok) R.string.export_done else R.string.export_failed))
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                        // Accept both the ZIP backup and plain JSON exports from older versions
                        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                            repo.importZip(bytes.inputStream())
                        } else {
                            repo.importJson(bytes.decodeToString())
                        }
                    }
                }
                snackbar.showSnackbar(
                    message = result.fold(
                        onSuccess = { importMessage(context, it) },
                        onFailure = { importFailure(context, it) },
                    ),
                    duration = if (result.isSuccess) SnackbarDuration.Short else SnackbarDuration.Long,
                )
            }
        }
    }

    val photoImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val recipe = importRecipeImage(context, repo, uri)
                if (recipe == null) snackbar.showSnackbar(context.getString(R.string.photo_no_recipe))
                else onCreateFromPhoto(recipe)
            }
        }
    }

    val startQrScan = {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode ->
                val result = runCatching { repo.importJson(barcode.rawValue ?: "") }
                scope.launch {
                    snackbar.showSnackbar(
                        message = result.fold(
                            onSuccess = { importMessage(context, it) },
                            onFailure = { importFailure(context, it) },
                        ),
                        duration = if (result.isSuccess) SnackbarDuration.Short else SnackbarDuration.Long,
                    )
                }
            }
        Unit
    }

    val shown = recipes
        .filter { !favOnly || it.favorite }
        .filter { simFilter == null || it.filmSimulation == simFilter }
        .filter { cameraFilter == null || it.cameraLabel == cameraFilter }
        .filter { monoFilter == null || (it.filmSimulation in MONO_SIMS) == monoFilter }
        .filter {
            query.isBlank() || it.name.contains(query, ignoreCase = true) ||
                it.filmSimulation.label.contains(query, ignoreCase = true) ||
                it.cameraLabel.contains(query, ignoreCase = true) ||
                it.tags.any { tag -> tag.contains(query, ignoreCase = true) }
        }
        .let { list ->
            when (sort) {
                SortOrder.NAME -> list.sortedBy { it.name.lowercase() }
                SortOrder.RECENT -> list.sortedByDescending { it.updatedAt }
                // Group by film, alphabetical within each
                SortOrder.SIMULATION ->
                    list.sortedWith(compareBy({ it.filmSimulation.ordinal }, { it.name.lowercase() }))
            }
        }

    // Offer only values the library actually contains
    val simsPresent = remember(recipes) { recipes.map { it.filmSimulation }.distinct().sortedBy { it.ordinal } }
    val camerasPresent = remember(recipes) { recipes.map { it.cameraLabel }.distinct().sorted() }

    // Search suggestions from the data itself: tags, film simulations, cameras
    val suggestions = remember(recipes) {
        recipes.flatMap { it.tags }.distinct().sorted().map { "#$it" to it } +
            recipes.map { it.filmSimulation.label }.distinct().sorted().map { it to it } +
            recipes.map { it.cameraLabel }.distinct().sorted().map { it to it }
    }

    if (showCameraImport) {
        ImportFromCameraDialog(
            repo = repo,
            onDismiss = { showCameraImport = false },
            onImported = { n ->
                scope.launch { snackbar.showSnackbar(context.getString(R.string.import_done, n)) }
            },
        )
    }

    if (showTextImport) {
        var textInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTextImport = false },
            title = { Text(stringResource(R.string.import_text)) },
            text = {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text(stringResource(R.string.paste_recipe)) },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = textInput.isNotBlank(),
                    onClick = {
                        val recipes = FujiStyleCard.parseAll(textInput, tag = "import")
                        when {
                            recipes.isEmpty() -> scope.launch {
                                snackbar.showSnackbar(
                                    message = context.getString(R.string.text_parse_failed),
                                    duration = SnackbarDuration.Long,
                                )
                            }
                            recipes.size == 1 -> {
                                showTextImport = false
                                onCreateFromPhoto(recipes.first())
                            }
                            else -> {
                                showTextImport = false
                                val r = repo.addImported(recipes)
                                scope.launch { snackbar.showSnackbar(importMessage(context, r)) }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.create)) }
            },
            dismissButton = {
                TextButton(onClick = { showTextImport = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showLogs) {
        var logText by remember { mutableStateOf(DebugLog.read()) }
        AlertDialog(
            onDismissRequest = { showLogs = false },
            title = { Text(stringResource(R.string.debug_logs)) },
            text = {
                Text(
                    logText.ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { DebugLog.clear(); logText = "" }) {
                        Text(stringResource(R.string.clear))
                    }
                    TextButton(
                        enabled = logText.isNotBlank(),
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, logText)
                            context.startActivity(Intent.createChooser(intent, null))
                        },
                    ) { Text(stringResource(R.string.share)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogs = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(stringResource(R.string.search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(stringResource(R.string.app_name))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenCameraSlots) {
                        Icon(
                            painterResource(R.drawable.ic_camera_body),
                            stringResource(R.string.camera_slots),
                        )
                    }
                    IconButton(onClick = startQrScan) {
                        Icon(
                            painterResource(R.drawable.ic_qr_scan),
                            stringResource(R.string.scan_qr),
                        )
                    }
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(
                            if (searching) Icons.Default.Close else Icons.Default.Search,
                            stringResource(R.string.search),
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_json)) },
                                onClick = {
                                    menuOpen = false
                                    // Drive sometimes reports JSON as octet-stream
                                    importLauncher.launch(
                                        arrayOf(
                                            "application/zip", "application/json",
                                            "application/octet-stream", "text/plain",
                                        )
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_json)) },
                                onClick = {
                                    menuOpen = false
                                    exportLauncher.launch("reshipi-backup-${LocalDate.now()}.zip")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.debug_logs)) },
                                onClick = { menuOpen = false; showLogs = true },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            // Every way to create a recipe hangs off the "+", so the overflow menu
            // is left to the library-wide actions (backup, logs).
            Box {
                FloatingActionButton(onClick = { addOpen = true }) {
                    Icon(Icons.Default.Add, stringResource(R.string.new_recipe))
                }
                DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.new_from_scratch)) },
                        onClick = { addOpen = false; onAdd() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.new_from_image)) },
                        onClick = {
                            addOpen = false
                            photoImportLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.new_from_text)) },
                        onClick = { addOpen = false; showTextImport = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.new_from_camera)) },
                        onClick = { addOpen = false; showCameraImport = true },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (searching) {
                val visible = suggestions.filter { (_, value) ->
                    value.contains(query, ignoreCase = true) && !value.equals(query, ignoreCase = true)
                }
                if (visible.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        visible.forEach { (label, value) ->
                            SuggestionChip(onClick = { query = value }, label = { Text(label) })
                        }
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                FilterChip(
                    selected = favOnly,
                    onClick = { favOnly = !favOnly },
                    label = { Text(stringResource(R.string.favorites)) },
                    leadingIcon = { Icon(Icons.Default.Favorite, null) },
                )
                MenuChip(
                    label = simFilter?.label ?: stringResource(R.string.film_simulation),
                    selected = simFilter != null,
                    options = simsPresent.map { it.label to it },
                    onPick = { simFilter = it },
                )
                MenuChip(
                    label = when (monoFilter) {
                        true -> stringResource(R.string.filter_mono)
                        false -> stringResource(R.string.filter_color)
                        null -> stringResource(R.string.filter_mono_or_color)
                    },
                    selected = monoFilter != null,
                    options = listOf(
                        stringResource(R.string.filter_mono) to true,
                        stringResource(R.string.filter_color) to false,
                    ),
                    onPick = { monoFilter = it },
                )
                MenuChip(
                    label = cameraFilter ?: stringResource(R.string.camera_model),
                    selected = cameraFilter != null,
                    options = camerasPresent.map { it to it },
                    onPick = { cameraFilter = it },
                )
                MenuChip(
                    label = stringResource(sort.labelRes),
                    selected = sort != SortOrder.NAME,
                    options = SortOrder.entries.map { stringResource(it.labelRes) to it },
                    clearable = false,
                    onPick = { sort = it ?: SortOrder.NAME },
                )
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.empty_list),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(shown, key = { it.id }) { r ->
                        RecipeCard(r, repo, onOpen)
                    }
                }
            }
        }
    }
}

/** Photo-first grid card: cover photo (or a film-sim tinted placeholder), scrim, overlay info. */
@Composable
private fun RecipeCard(r: Recipe, repo: RecipeRepository, onOpen: (String) -> Unit) {
    Card(
        onClick = { onOpen(r.id) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(Modifier.aspectRatio(0.85f)) {
            val cover = r.photos.firstOrNull()
            if (cover != null) {
                AsyncImage(
                    model = repo.photoFile(cover),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // No photo: the film-box badge stands in, which reads far better
                // than the simulation name on a flat gradient.
                Image(
                    painter = painterResource(filmBadge(r.filmSimulation)),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to Color(0xCC000000),
                        )
                    )
            )
            IconButton(
                onClick = { repo.toggleFavorite(r.id) },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    if (r.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    stringResource(R.string.favorite),
                    tint = if (r.favorite) Color(0xFFE57373) else Color.White.copy(alpha = 0.75f),
                )
            }
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            ) {
                Text(
                    r.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 2,
                )
                Text(
                    "${r.filmSimulation.label} · ${r.cameraLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }
}

private enum class SortOrder(val labelRes: Int) {
    NAME(R.string.sort_name),
    RECENT(R.string.sort_recent),
    SIMULATION(R.string.sort_simulation),
}

/**
 * A chip that drops down a list of choices. [clearable] adds an "All" entry that
 * picks null — sorting always has an answer, so it opts out.
 */
@Composable
private fun <T> MenuChip(
    label: String,
    selected: Boolean,
    options: List<Pair<String, T>>,
    onPick: (T?) -> Unit,
    clearable: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (clearable) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.filter_all)) },
                    onClick = { open = false; onPick(null) },
                )
            }
            options.forEach { (text, value) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { open = false; onPick(value) },
                )
            }
        }
    }
}
