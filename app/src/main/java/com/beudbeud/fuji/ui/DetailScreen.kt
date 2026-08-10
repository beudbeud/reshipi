package com.beudbeud.fuji.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.beudbeud.fuji.R
import com.beudbeud.fuji.data.RecipeRepository
import com.beudbeud.fuji.model.Recipe
import com.beudbeud.fuji.model.Strength
import com.beudbeud.fuji.model.WhiteBalance
import com.beudbeud.fuji.model.cameraLabel
import com.beudbeud.fuji.model.formatSigned

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    recipe: Recipe,
    repo: RecipeRepository,
    onEdit: () -> Unit,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var fullPhoto by remember { mutableStateOf<String?>(null) }
    var showSend by remember { mutableStateOf(false) }
    var showRafPreview by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val gen = recipe.generation
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recipe.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { repo.toggleFavorite(recipe.id) }) {
                        Icon(
                            if (recipe.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            stringResource(R.string.favorite),
                            tint = if (recipe.favorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { showSend = true }) {
                        Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send_to_camera))
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, stringResource(R.string.edit))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.share_text)) },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = { menuOpen = false; shareRecipe(context, recipe) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.share_qr)) },
                                onClick = { menuOpen = false; showQr = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.raf_preview)) },
                                onClick = { menuOpen = false; showRafPreview = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                                onClick = { menuOpen = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (recipe.photos.isNotEmpty()) {
                LazyRow(Modifier.padding(vertical = 8.dp)) {
                    items(recipe.photos) { name ->
                        AsyncImage(
                            model = repo.photoFile(name),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(120.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { fullPhoto = name },
                        )
                    }
                }
                HorizontalDivider()
            }
            DetailRow(stringResource(R.string.camera_model), recipe.cameraLabel)
            DetailRow(stringResource(R.string.film_simulation), recipe.filmSimulation.label)
            DetailRow(
                stringResource(R.string.white_balance),
                stringResource(recipe.whiteBalance.labelRes) +
                    (recipe.kelvin?.let { " ${it}K" }.takeIf { recipe.whiteBalance == WhiteBalance.KELVIN } ?: "") +
                    "  R${formatSigned(recipe.wbShiftRed)} B${formatSigned(recipe.wbShiftBlue)}",
            )
            DetailRow(stringResource(R.string.dynamic_range), recipe.dynamicRange.label)
            if (gen.hasDRangePriority) {
                DetailRow(stringResource(R.string.d_range_priority), stringResource(recipe.dRangePriority.labelRes))
            }
            DetailRow(stringResource(R.string.highlight), formatSigned(recipe.highlight))
            DetailRow(stringResource(R.string.shadow), formatSigned(recipe.shadow))
            DetailRow(stringResource(R.string.color), formatSigned(recipe.color))
            DetailRow(stringResource(R.string.sharpness), formatSigned(recipe.sharpness))
            DetailRow(stringResource(R.string.noise_reduction), formatSigned(recipe.noiseReduction))
            if (gen.hasGrainEffect) {
                var grain = stringResource(recipe.grainEffect.labelRes)
                if (gen.hasGrainSize && recipe.grainEffect != Strength.OFF) {
                    grain += " · " + stringResource(recipe.grainSize.labelRes)
                }
                DetailRow(stringResource(R.string.grain_effect), grain)
            }
            if (gen.hasColorChrome) {
                DetailRow(stringResource(R.string.color_chrome_effect), stringResource(recipe.colorChromeEffect.labelRes))
                DetailRow(stringResource(R.string.color_chrome_fx_blue), stringResource(recipe.colorChromeFxBlue.labelRes))
            }
            if (gen.hasClarity) {
                DetailRow(stringResource(R.string.clarity), formatSigned(recipe.clarity))
            }
            DetailRow(stringResource(R.string.iso), recipe.iso)
            DetailRow(stringResource(R.string.exposure_compensation), recipe.exposureCompensation)
            if (recipe.notes.isNotBlank()) {
                SectionHeader(stringResource(R.string.notes))
                Text(recipe.notes, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showSend) {
        SendToCameraDialog(recipe = recipe, onDismiss = { showSend = false })
    }

    if (showQr) {
        QrDialog(recipe = recipe, onDismiss = { showQr = false })
    }

    if (showRafPreview) {
        RafPreviewDialog(recipe = recipe, repo = repo, onDismiss = { showRafPreview = false })
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_text, recipe.name)) },
            confirmButton = {
                TextButton(onClick = { repo.delete(recipe.id); onDeleted() }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    fullPhoto?.let { name ->
        Dialog(onDismissRequest = { fullPhoto = null }) {
            AsyncImage(
                model = repo.photoFile(name),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { fullPhoto = null },
            )
        }
    }
}

/** Forum-style text block, only fields the recipe's generation actually has. */
private fun shareRecipe(context: Context, recipe: Recipe) {
    val gen = recipe.generation
    fun s(res: Int) = context.getString(res)
    val text = buildString {
        appendLine(recipe.name)
        appendLine("${s(R.string.camera_model)}: ${recipe.cameraLabel}")
        appendLine("${s(R.string.film_simulation)}: ${recipe.filmSimulation.label}")
        append("${s(R.string.white_balance)}: ${s(recipe.whiteBalance.labelRes)}")
        if (recipe.whiteBalance == WhiteBalance.KELVIN) recipe.kelvin?.let { append(" ${it}K") }
        appendLine(" R${formatSigned(recipe.wbShiftRed)} B${formatSigned(recipe.wbShiftBlue)}")
        appendLine("${s(R.string.dynamic_range)}: ${recipe.dynamicRange.label}")
        if (gen.hasDRangePriority) {
            appendLine("${s(R.string.d_range_priority)}: ${s(recipe.dRangePriority.labelRes)}")
        }
        appendLine("${s(R.string.highlight)}: ${formatSigned(recipe.highlight)}")
        appendLine("${s(R.string.shadow)}: ${formatSigned(recipe.shadow)}")
        appendLine("${s(R.string.color)}: ${formatSigned(recipe.color)}")
        appendLine("${s(R.string.sharpness)}: ${formatSigned(recipe.sharpness)}")
        appendLine("${s(R.string.noise_reduction)}: ${formatSigned(recipe.noiseReduction)}")
        if (gen.hasGrainEffect) {
            append("${s(R.string.grain_effect)}: ${s(recipe.grainEffect.labelRes)}")
            if (gen.hasGrainSize && recipe.grainEffect != Strength.OFF) {
                append(" · ${s(recipe.grainSize.labelRes)}")
            }
            appendLine()
        }
        if (gen.hasColorChrome) {
            appendLine("${s(R.string.color_chrome_effect)}: ${s(recipe.colorChromeEffect.labelRes)}")
            appendLine("${s(R.string.color_chrome_fx_blue)}: ${s(recipe.colorChromeFxBlue.labelRes)}")
        }
        if (gen.hasClarity) {
            appendLine("${s(R.string.clarity)}: ${formatSigned(recipe.clarity)}")
        }
        appendLine("${s(R.string.iso)}: ${recipe.iso}")
        appendLine("${s(R.string.exposure_compensation)}: ${recipe.exposureCompensation}")
        if (recipe.notes.isNotBlank()) {
            appendLine()
            appendLine(recipe.notes)
        }
    }
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, recipe.name)
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(intent, null))
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
