package com.beudbeud.fuji.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.beudbeud.fuji.R
import com.beudbeud.fuji.data.RecipeRepository
import com.beudbeud.fuji.model.Recipe
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
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var favOnly by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
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

    val shown = recipes
        .filter { !favOnly || it.favorite }
        .filter {
            query.isBlank() || it.name.contains(query, ignoreCase = true) ||
                it.filmSimulation.label.contains(query, ignoreCase = true)
        }
        .sortedBy { it.name.lowercase() }

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
                                text = { Text(stringResource(R.string.export_json)) },
                                onClick = {
                                    menuOpen = false
                                    exportLauncher.launch("fuji-recipes-${LocalDate.now()}.json")
                                },
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
                                text = { Text(stringResource(R.string.scan_qr)) },
                                onClick = {
                                    menuOpen = false
                                    val options = GmsBarcodeScannerOptions.Builder()
                                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                        .build()
                                    GmsBarcodeScanning.getClient(context, options).startScan()
                                        .addOnSuccessListener { barcode ->
                                            val n = runCatching {
                                                repo.importJson(barcode.rawValue ?: "")
                                            }.getOrNull()
                                            scope.launch {
                                                snackbar.showSnackbar(
                                                    if (n != null) context.getString(R.string.import_done, n)
                                                    else context.getString(R.string.import_failed)
                                                )
                                            }
                                        }
                                },
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
                LazyColumn {
                    items(shown, key = { it.id }) { r ->
                        Card(
                            onClick = { onOpen(r.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${r.filmSimulation.label} · ${r.generation.label}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { repo.toggleFavorite(r.id) }) {
                                    Icon(
                                        if (r.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        stringResource(R.string.favorite),
                                        tint = if (r.favorite) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
