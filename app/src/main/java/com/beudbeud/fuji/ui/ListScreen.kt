package com.beudbeud.fuji.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
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
import com.beudbeud.fuji.data.FujiExif
import com.beudbeud.fuji.data.FujiStyleCard
import com.beudbeud.fuji.data.RecipeRepository
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.beudbeud.fuji.model.Recipe
import com.beudbeud.fuji.model.cameraLabel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    recipes: List<Recipe>,
    repo: RecipeRepository,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onCreateFromPhoto: (Recipe) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var favOnly by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var showTextImport by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)!!
                    .use { it.write(repo.exportJson().toByteArray()) }
            }.isSuccess
            scope.launch {
                snackbar.showSnackbar(context.getString(if (ok) R.string.export_done else R.string.export_failed))
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val result = runCatching {
                val text = context.contentResolver.openInputStream(uri)!!
                    .use { it.readBytes().decodeToString() }
                repo.importJson(text)
            }
            scope.launch {
                snackbar.showSnackbar(
                    result.fold(
                        onSuccess = { context.getString(R.string.import_done, it) },
                        onFailure = { context.getString(R.string.import_failed) },
                    )
                )
            }
        }
    }

    val photoImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            importRecipeImage(context, repo, uri) { recipe ->
                if (recipe == null) {
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.photo_no_recipe)) }
                } else {
                    onCreateFromPhoto(recipe)
                }
            }
        }
    }

    val startQrScan = {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode ->
                val n = runCatching { repo.importJson(barcode.rawValue ?: "") }.getOrNull()
                scope.launch {
                    snackbar.showSnackbar(
                        if (n != null) context.getString(R.string.import_done, n)
                        else context.getString(R.string.import_failed)
                    )
                }
            }
        Unit
    }

    val shown = recipes
        .filter { !favOnly || it.favorite }
        .filter {
            query.isBlank() || it.name.contains(query, ignoreCase = true) ||
                it.filmSimulation.label.contains(query, ignoreCase = true) ||
                it.cameraLabel.contains(query, ignoreCase = true) ||
                it.tags.any { tag -> tag.contains(query, ignoreCase = true) }
        }
        .sortedBy { it.name.lowercase() }

    // Search suggestions from the data itself: tags, film simulations, cameras
    val suggestions = remember(recipes) {
        recipes.flatMap { it.tags }.distinct().sorted().map { "#$it" to it } +
            recipes.map { it.filmSimulation.label }.distinct().sorted().map { it to it } +
            recipes.map { it.cameraLabel }.distinct().sorted().map { it to it }
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
                        val recipe = FujiStyleCard.parse(textInput, tag = "import")
                        if (recipe == null) {
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.card_parse_failed)) }
                        } else {
                            showTextImport = false
                            onCreateFromPhoto(recipe)
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
                                text = { Text(stringResource(R.string.new_from_photo)) },
                                onClick = {
                                    menuOpen = false
                                    photoImportLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_text)) },
                                onClick = { menuOpen = false; showTextImport = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_json)) },
                                onClick = {
                                    menuOpen = false
                                    // Drive sometimes reports JSON as octet-stream
                                    importLauncher.launch(
                                        arrayOf("application/json", "application/octet-stream", "text/plain")
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_json)) },
                                onClick = {
                                    menuOpen = false
                                    exportLauncher.launch("fuji-recipes-${LocalDate.now()}.json")
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
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, stringResource(R.string.new_recipe))
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
            FilterChip(
                selected = favOnly,
                onClick = { favOnly = !favOnly },
                label = { Text(stringResource(R.string.favorites)) },
                leadingIcon = { Icon(Icons.Default.Favorite, null) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
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
    val accent = simAccent(r.filmSimulation)
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
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(accent.copy(alpha = 0.50f), accent.copy(alpha = 0.15f))
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        r.filmSimulation.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                }
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
