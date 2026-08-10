package com.beudbeud.fuji.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * FujiStyle cards are a photo (or a gray placeholder) above a light info panel
 * carrying the title, settings text and "Made with FUJISTYLE APP". For the
 * illustration we want just the photo — crop off the panel, and when the top is
 * a uniform gray placeholder, attach nothing so the recipe shows its gradient.
 *
 * Algorithm validated on real cards: a row is "panel" when most pixels are
 * bright and desaturated; the photo/panel boundary is the last clearly-photo
 * row above a sustained panel run. If the top quarter is already mostly panel,
 * there is no real photo.
 */
object CardCrop {

    /** Cropped JPEG of the card's photo, or null when the card has no real photo. */
    fun extractPhoto(context: Context, uri: Uri): ByteArray? = runCatching {
        val bmp = decodeSampled(context, uri, 1080) ?: return null
        val w = bmp.width
        val h = bmp.height
        val frac = DoubleArray(h) { y -> panelFrac(bmp, w, y) }

        // Top quarter mostly panel → gray placeholder, no photo to keep.
        val topMean = (0 until h / 4).sumOf { frac[it] } / (h / 4)
        if (topMean > 0.5) return null

        // Photo bottom = last clearly-photo row followed by a sustained panel run.
        var boundary = h
        for (y in (h * 35 / 100) until (h * 90 / 100)) {
            if (frac[y] < 0.30) {
                val ahead = (y + 1 until minOf(y + 61, h))
                if (ahead.count() > 0 && ahead.sumOf { frac[it] } / ahead.count() >= 0.75) {
                    boundary = y
                }
            }
        }
        if (boundary >= h) return null // no clear panel → leave to caller (keep whole)

        val cropped = Bitmap.createBitmap(bmp, 0, 0, w, boundary)
        ByteArrayOutputStream().use { out ->
            cropped.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }.getOrNull()

    /** Fraction of sampled pixels in row y that look like the light info panel. */
    private fun panelFrac(bmp: Bitmap, w: Int, y: Int): Double {
        var cnt = 0
        var n = 0
        var x = 0
        while (x < w) {
            val p = bmp.getPixel(x, y)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            if (mx > 200 && (mx - mn) < 24) cnt++
            n++
            x += 8
        }
        return if (n == 0) 0.0 else cnt.toDouble() / n
    }

    private fun decodeSampled(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
