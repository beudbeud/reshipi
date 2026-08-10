package com.beudbeud.fuji.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.beudbeud.fuji.data.RecipeRepository

// ponytail: nav state not saved across process death; rememberSaveable if it ever matters
sealed interface Screen {
    data object Home : Screen
    data class Detail(val id: String) : Screen
    data class Edit(val id: String?) : Screen
}

@Composable
fun App(repo: RecipeRepository) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors) {
        var screen by remember { mutableStateOf<Screen>(Screen.Home) }
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
                onAdd = { screen = Screen.Edit(null) },
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
                existing = recipes.find { it.id == s.id },
                repo = repo,
                onDone = { saved -> screen = Screen.Detail(saved.id) },
                onBack = { screen = if (s.id != null) Screen.Detail(s.id) else Screen.Home },
            )
        }
    }
}
