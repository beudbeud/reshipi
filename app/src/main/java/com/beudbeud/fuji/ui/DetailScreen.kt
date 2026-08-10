package com.beudbeud.fuji.ui

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
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
    val gen = recipe.generation

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
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, stringResource(R.string.edit))
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, stringResource(R.string.delete))
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
            DetailRow(stringResource(R.string.generation), gen.label)
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
