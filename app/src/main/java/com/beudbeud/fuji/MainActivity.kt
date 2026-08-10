package com.beudbeud.fuji

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.beudbeud.fuji.data.DebugLog
import com.beudbeud.fuji.data.RecipeRepository
import com.beudbeud.fuji.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(applicationContext)
        // ponytail: repository recreated on rotation — it just reloads a small JSON file
        val repo = RecipeRepository(applicationContext)
        val sharedText = if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else null
        setContent { App(repo, sharedText) }
    }
}
