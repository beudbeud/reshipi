package com.beudbeud.fuji.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.beudbeud.fuji.R
import com.beudbeud.fuji.data.FujiStyleCard
import com.beudbeud.fuji.data.RecipeRepository
import com.beudbeud.fuji.data.WebImport
import com.beudbeud.fuji.model.FilmSimulation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ponytail: nav state not saved across process death; rememberSaveable if it ever matters
sealed interface Screen {
    data object Home : Screen
    data class Detail(val id: String) : Screen
    data class Edit(val id: String?) : Screen
    data object CameraSlots : Screen
}

// Photo-first look: committed dark theme, warm silver accent, photos carry the color.
private val DarkColors = darkColorScheme(
    background = Color(0xFF0E0F10),
    surface = Color(0xFF141517),
    surfaceVariant = Color(0xFF1D1F22),
    surfaceContainer = Color(0xFF17181B),
    surfaceContainerHigh = Color(0xFF1D1F22),
    primary = Color(0xFFE6E1D8),
    onPrimary = Color(0xFF1A1A1A),
    primaryContainer = Color(0xFF2A2C2F),
    onPrimaryContainer = Color(0xFFE6E1D8),
    secondary = Color(0xFF9AA79E),
    secondaryContainer = Color(0xFF23272A),
    onSecondaryContainer = Color(0xFFCBD4CE),
    onBackground = Color(0xFFEAE8E4),
    onSurface = Color(0xFFEAE8E4),
    onSurfaceVariant = Color(0xFF9C9A96),
    outline = Color(0xFF3A3C40),
    outlineVariant = Color(0xFF2A2C2F),
)

/**
 * Film-box badge per simulation. Artwork from FujiSync (MIT) — see THIRD_PARTY.md.
 * Every FilmSimulation has one, so this is exhaustive on purpose: adding a
 * simulation without its badge fails to compile.
 */
fun filmBadge(sim: FilmSimulation): Int = when (sim) {
    FilmSimulation.PROVIA -> R.drawable.film_sim_badge_provia_standard
    FilmSimulation.VELVIA -> R.drawable.film_sim_badge_velvia_vivid
    FilmSimulation.ASTIA -> R.drawable.film_sim_badge_astia_soft
    FilmSimulation.CLASSIC_CHROME -> R.drawable.film_sim_badge_classic_chrome
    FilmSimulation.CLASSIC_NEG -> R.drawable.film_sim_badge_classic_negative
    FilmSimulation.NOSTALGIC_NEG -> R.drawable.film_sim_badge_nostalgic_negative
    FilmSimulation.REALA_ACE -> R.drawable.film_sim_badge_reala_ace
    FilmSimulation.PRO_NEG_HI -> R.drawable.film_sim_badge_pro_neg_hi
    FilmSimulation.PRO_NEG_STD -> R.drawable.film_sim_badge_pro_neg_standard
    FilmSimulation.ETERNA -> R.drawable.film_sim_badge_eterna_cinema
    FilmSimulation.ETERNA_BLEACH_BYPASS -> R.drawable.film_sim_badge_eterna_bleach_bypass
    FilmSimulation.ACROS -> R.drawable.film_sim_badge_acros
    FilmSimulation.ACROS_YE -> R.drawable.film_sim_badge_acros_yellow_filter
    FilmSimulation.ACROS_R -> R.drawable.film_sim_badge_acros_red_filter
    FilmSimulation.ACROS_G -> R.drawable.film_sim_badge_acros_green_filter
    FilmSimulation.MONOCHROME -> R.drawable.film_sim_badge_monochrome
    FilmSimulation.MONOCHROME_YE -> R.drawable.film_sim_badge_monochrome_yellow_filter
    FilmSimulation.MONOCHROME_R -> R.drawable.film_sim_badge_monochrome_red_filter
    FilmSimulation.MONOCHROME_G -> R.drawable.film_sim_badge_monochrome_green_filter
    FilmSimulation.SEPIA -> R.drawable.film_sim_badge_sepia
}


@Composable
fun App(repo: RecipeRepository, sharedText: String? = null, sharedImage: android.net.Uri? = null) {
    MaterialTheme(colorScheme = DarkColors) {
        var screen by remember { mutableStateOf<Screen>(Screen.Home) }
        // Pre-filled draft when creating a recipe from a photo's EXIF
        var photoDraft by remember { mutableStateOf<com.beudbeud.fuji.model.Recipe?>(null) }
        val context = LocalContext.current

        // Shared from another app: a recipe page URL or pasted recipe text
        LaunchedEffect(sharedText) {
            if (sharedText != null) {
                val url = Regex("https?://\\S+").find(sharedText)?.value
                val recipes = withContext(Dispatchers.IO) {
                    if (url != null) WebImport.fetch(url, repo)
                    else FujiStyleCard.parseAll(sharedText, tag = "import")
                }
                when {
                    recipes.isEmpty() -> Toast.makeText(
                        context,
                        if (url != null) R.string.url_parse_failed else R.string.text_parse_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                    recipes.size == 1 -> {
                        photoDraft = recipes.first()
                        screen = Screen.Edit(null)
                    }
                    else -> {
                        val r = repo.addImported(recipes)
                        Toast.makeText(context, importMessage(context, r), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Image shared from the gallery: same pipeline as "New recipe from an image"
        LaunchedEffect(sharedImage) {
            if (sharedImage != null) {
                importRecipeImage(context, repo, sharedImage) { recipe ->
                    if (recipe != null) {
                        photoDraft = recipe
                        screen = Screen.Edit(null)
                    } else {
                        Toast.makeText(context, R.string.photo_no_recipe, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        BackHandler(screen != Screen.Home) {
            screen = when (val s = screen) {
                is Screen.Edit -> if (s.id != null) Screen.Detail(s.id) else Screen.Home
                else -> Screen.Home
            }
        }
        val recipes by repo.recipes.collectAsState()
        when (val s = screen) {
            Screen.Home -> ListScreen(
                recipes = recipes,
                repo = repo,
                onOpen = { screen = Screen.Detail(it) },
                onAdd = { photoDraft = null; screen = Screen.Edit(null) },
                onCreateFromPhoto = { photoDraft = it; screen = Screen.Edit(null) },
                onOpenCameraSlots = { screen = Screen.CameraSlots },
            )
            is Screen.Detail -> {
                val recipe = recipes.find { it.id == s.id }
                if (recipe == null) {
                    LaunchedEffect(Unit) { screen = Screen.Home }
                } else {
                    DetailScreen(
                        recipe = recipe,
                        repo = repo,
                        onEdit = { screen = Screen.Edit(s.id) },
                        onBack = { screen = Screen.Home },
                        onDeleted = { screen = Screen.Home },
                    )
                }
            }
            Screen.CameraSlots -> CameraSlotsScreen(
                recipes = recipes,
                repo = repo,
                onBack = { screen = Screen.Home },
                onOpenRecipe = { screen = Screen.Detail(it) },
            )
            is Screen.Edit -> EditScreen(
                existing = if (s.id != null) recipes.find { it.id == s.id } else photoDraft,
                repo = repo,
                onDone = { saved -> screen = Screen.Detail(saved.id) },
                onBack = { screen = if (s.id != null) Screen.Detail(s.id) else Screen.Home },
            )
        }
    }
}
