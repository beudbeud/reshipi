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
import androidx.compose.ui.graphics.Color
import com.beudbeud.fuji.data.RecipeRepository
import com.beudbeud.fuji.model.FilmSimulation

// ponytail: nav state not saved across process death; rememberSaveable if it ever matters
sealed interface Screen {
    data object Home : Screen
    data class Detail(val id: String) : Screen
    data class Edit(val id: String?) : Screen
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

/** Accent color per film simulation — used for placeholder covers and chips. */
fun simAccent(sim: FilmSimulation): Color = when (sim) {
    FilmSimulation.PROVIA -> Color(0xFF5B7A99)
    FilmSimulation.VELVIA -> Color(0xFFB35340)
    FilmSimulation.ASTIA -> Color(0xFFB08BA0)
    FilmSimulation.CLASSIC_CHROME -> Color(0xFF6E7F72)
    FilmSimulation.CLASSIC_NEG -> Color(0xFFB08D57)
    FilmSimulation.NOSTALGIC_NEG -> Color(0xFFC0996A)
    FilmSimulation.REALA_ACE -> Color(0xFF7A9E7E)
    FilmSimulation.PRO_NEG_STD -> Color(0xFF9A8F86)
    FilmSimulation.PRO_NEG_HI -> Color(0xFF8A7F76)
    FilmSimulation.ETERNA -> Color(0xFF5C6B7A)
    FilmSimulation.ETERNA_BLEACH_BYPASS -> Color(0xFF8C93A0)
    FilmSimulation.SEPIA -> Color(0xFF9C7B52)
    else -> Color(0xFF85878B) // Acros & Monochrome family
}

@Composable
fun App(repo: RecipeRepository) {
    MaterialTheme(colorScheme = DarkColors) {
        var screen by remember { mutableStateOf<Screen>(Screen.Home) }
        // Pre-filled draft when creating a recipe from a photo's EXIF
        var photoDraft by remember { mutableStateOf<com.beudbeud.fuji.model.Recipe?>(null) }
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
            is Screen.Edit -> EditScreen(
                existing = if (s.id != null) recipes.find { it.id == s.id } else photoDraft,
                repo = repo,
                onDone = { saved -> screen = Screen.Detail(saved.id) },
                onBack = { screen = if (s.id != null) Screen.Detail(s.id) else Screen.Home },
            )
        }
    }
}
