package com.beudbeud.fuji.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.beudbeud.fuji.model.CAMERA_MODELS
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.beudbeud.fuji.R
import com.beudbeud.fuji.data.RecipeRepository
import com.beudbeud.fuji.model.DRangePriority
import com.beudbeud.fuji.model.DynamicRange
import com.beudbeud.fuji.model.FilmSimulation
import com.beudbeud.fuji.model.Generation
import com.beudbeud.fuji.model.GrainSize
import com.beudbeud.fuji.model.Recipe
import com.beudbeud.fuji.model.Strength
import com.beudbeud.fuji.model.WhiteBalance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    existing: Recipe?,
    repo: RecipeRepository,
    onDone: (Recipe) -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember { mutableStateOf(existing ?: Recipe()) }
    val gen = draft.generation

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) draft = draft.copy(photos = draft.photos + repo.addPhoto(uri))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (existing == null) R.string.new_recipe else R.string.edit_recipe)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = { repo.upsert(draft); onDone(draft) },
                        enabled = draft.name.isNotBlank(),
                    ) { Text(stringResource(R.string.save)) }
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
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            // One entry per camera model (which fixes the feature set), plus
            // generic per-generation entries for recipes with no known body.
            val cameraOptions = remember { Generation.entries.map { it.label to it } + CAMERA_MODELS }
            val selectedCamera = cameraOptions.firstOrNull { it.first == draft.cameraModel }
                ?: (gen.label to gen)
            EnumDropdown(
                stringResource(R.string.camera_model), cameraOptions, selectedCamera,
                { (label, g) -> if (label == g.label) label else "$label · ${g.label}" }
            ) { (label, newGen) ->
                // clamp everything the new generation doesn't support or ranges differently
                draft = draft.copy(
                    cameraModel = if (label == newGen.label) "" else label,
                    generation = newGen,
                    filmSimulation = if (draft.filmSimulation.minGen <= newGen) draft.filmSimulation
                    else FilmSimulation.PROVIA,
                    highlight = draft.highlight.coerceIn(-2.0, newGen.toneMax),
                    shadow = draft.shadow.coerceIn(-2.0, newGen.toneMax),
                    color = draft.color.coerceIn(-newGen.colorRange, newGen.colorRange),
                    sharpness = draft.sharpness.coerceIn(-newGen.colorRange, newGen.colorRange),
                    noiseReduction = draft.noiseReduction.coerceIn(-newGen.colorRange, newGen.colorRange),
                )
            }
            EnumDropdown(
                stringResource(R.string.film_simulation), gen.filmSimulations,
                draft.filmSimulation, { it.label }
            ) { draft = draft.copy(filmSimulation = it) }

            SectionHeader(stringResource(R.string.white_balance))
            EnumDropdown(
                stringResource(R.string.white_balance), WhiteBalance.entries,
                draft.whiteBalance, { stringResource(it.labelRes) }
            ) { draft = draft.copy(whiteBalance = it) }
            if (draft.whiteBalance == WhiteBalance.KELVIN) {
                OutlinedTextField(
                    value = draft.kelvin?.toString() ?: "",
                    onValueChange = { draft = draft.copy(kelvin = it.toIntOrNull()) },
                    label = { Text(stringResource(R.string.kelvin)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            StepperRow(stringResource(R.string.wb_shift_red), draft.wbShiftRed, -9, 9) {
                draft = draft.copy(wbShiftRed = it)
            }
            StepperRow(stringResource(R.string.wb_shift_blue), draft.wbShiftBlue, -9, 9) {
                draft = draft.copy(wbShiftBlue = it)
            }

            SectionHeader(stringResource(R.string.section_tone))
            EnumDropdown(
                stringResource(R.string.dynamic_range), DynamicRange.entries,
                draft.dynamicRange, { it.label }
            ) { draft = draft.copy(dynamicRange = it) }
            if (gen.hasDRangePriority) {
                EnumDropdown(
                    stringResource(R.string.d_range_priority), DRangePriority.entries,
                    draft.dRangePriority, { stringResource(it.labelRes) }
                ) { draft = draft.copy(dRangePriority = it) }
            }
            StepperRow(stringResource(R.string.highlight), draft.highlight, -2.0, gen.toneMax, gen.toneStep) {
                draft = draft.copy(highlight = it)
            }
            StepperRow(stringResource(R.string.shadow), draft.shadow, -2.0, gen.toneMax, gen.toneStep) {
                draft = draft.copy(shadow = it)
            }
            StepperRow(stringResource(R.string.color), draft.color, -gen.colorRange, gen.colorRange) {
                draft = draft.copy(color = it)
            }
            StepperRow(stringResource(R.string.sharpness), draft.sharpness, -gen.colorRange, gen.colorRange) {
                draft = draft.copy(sharpness = it)
            }
            StepperRow(stringResource(R.string.noise_reduction), draft.noiseReduction, -gen.colorRange, gen.colorRange) {
                draft = draft.copy(noiseReduction = it)
            }

            if (gen.hasGrainEffect || gen.hasColorChrome || gen.hasClarity) {
                SectionHeader(stringResource(R.string.section_effects))
            }
            if (gen.hasGrainEffect) {
                EnumDropdown(
                    stringResource(R.string.grain_effect), Strength.entries,
                    draft.grainEffect, { stringResource(it.labelRes) }
                ) { draft = draft.copy(grainEffect = it) }
            }
            if (gen.hasGrainSize && draft.grainEffect != Strength.OFF) {
                EnumDropdown(
                    stringResource(R.string.grain_size), GrainSize.entries,
                    draft.grainSize, { stringResource(it.labelRes) }
                ) { draft = draft.copy(grainSize = it) }
            }
            if (gen.hasColorChrome) {
                EnumDropdown(
                    stringResource(R.string.color_chrome_effect), Strength.entries,
                    draft.colorChromeEffect, { stringResource(it.labelRes) }
                ) { draft = draft.copy(colorChromeEffect = it) }
                EnumDropdown(
                    stringResource(R.string.color_chrome_fx_blue), Strength.entries,
                    draft.colorChromeFxBlue, { stringResource(it.labelRes) }
                ) { draft = draft.copy(colorChromeFxBlue = it) }
            }
            if (gen.hasClarity) {
                StepperRow(stringResource(R.string.clarity), draft.clarity, -5, 5) {
                    draft = draft.copy(clarity = it)
                }
            }

            SectionHeader(stringResource(R.string.section_exposure))
            OutlinedTextField(
                value = draft.iso,
                onValueChange = { draft = draft.copy(iso = it) },
                label = { Text(stringResource(R.string.iso)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = draft.exposureCompensation,
                onValueChange = { draft = draft.copy(exposureCompensation = it) },
                label = { Text(stringResource(R.string.exposure_compensation)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = draft.notes,
                onValueChange = { draft = draft.copy(notes = it) },
                label = { Text(stringResource(R.string.notes)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            var tagsText by remember { mutableStateOf(draft.tags.joinToString(", ")) }
            OutlinedTextField(
                value = tagsText,
                onValueChange = {
                    tagsText = it
                    draft = draft.copy(tags = it.split(',').map(String::trim).filter(String::isNotEmpty))
                },
                label = { Text(stringResource(R.string.tags_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            SectionHeader(stringResource(R.string.photos))
            LazyRow {
                items(draft.photos) { name ->
                    Box(Modifier.padding(end = 8.dp)) {
                        AsyncImage(
                            model = repo.photoFile(name),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        IconButton(
                            onClick = { draft = draft.copy(photos = draft.photos - name) },
                            modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                        ) {
                            Icon(Icons.Default.Close, stringResource(R.string.remove_photo))
                        }
                    }
                }
            }
            Button(
                onClick = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(stringResource(R.string.add_photo)) }
            Spacer(Modifier.height(32.dp))
        }
    }
}
