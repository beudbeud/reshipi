package com.beudbeud.fuji.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.beudbeud.fuji.model.Recipe
import com.beudbeud.fuji.model.RecipeExport
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.serialization.json.Json

/** Same payload as the file export (single-recipe RecipeExport), compact encoding. */
private val qrJson = Json { encodeDefaults = false }

fun recipeQrContent(recipe: Recipe): String =
    qrJson.encodeToString(RecipeExport.serializer(), RecipeExport(recipes = listOf(recipe)))

private fun qrBitmap(content: String, size: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(
        content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1),
    )
    val pixels = IntArray(size * size) { i ->
        if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE
    }
    return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}

@Composable
fun QrDialog(recipe: Recipe, onDismiss: () -> Unit) {
    val bitmap = remember(recipe) { qrBitmap(recipeQrContent(recipe)) }
    Dialog(onDismissRequest = onDismiss) {
        // QR needs a white background in both themes
        Surface(shape = RoundedCornerShape(12.dp), color = androidx.compose.ui.graphics.Color.White) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = recipe.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(16.dp),
                )
                Text(
                    recipe.name,
                    color = androidx.compose.ui.graphics.Color.Black,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }
    }
}
