package com.beudbeud.fuji

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.beudbeud.fuji.data.RecipeRepository
import com.beudbeud.fuji.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ponytail: repository recreated on rotation — it just reloads a small JSON file
        val repo = RecipeRepository(applicationContext)
        setContent { App(repo) }
    }
}
