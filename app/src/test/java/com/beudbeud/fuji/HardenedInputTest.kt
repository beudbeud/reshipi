package com.beudbeud.fuji

import com.beudbeud.fuji.data.DonorRaf
import com.beudbeud.fuji.data.WebImport
import com.beudbeud.fuji.model.Recipe
import com.beudbeud.fuji.ui.recipeQrContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The paths that take bytes from somewhere we do not control: a web page the
 * user shared, a file they picked, a recipe on its way into a QR code.
 */
class HardenedInputTest {

    @Test
    fun anOversizedPageIsRefusedInsteadOfBufferedWhole() {
        // 8MB is the cap; the stream claims far more than that.
        val huge = object : java.io.InputStream() {
            var served = 0L
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                served += len
                return len
            }
        }
        assertNull(WebImport.readCapped(huge))
        // ...and it stopped near the cap rather than reading the stream out
        assertEquals(true, huge.served < 16L shl 20)
    }

    @Test
    fun aPageUnderTheCapIsReadWhole() {
        val body = ByteArray(100_000) { (it % 251).toByte() }
        val read = WebImport.readCapped(body.inputStream())
        assertNotNull(read)
        assertEquals(body.size, read!!.size)
        assertEquals(body.toList(), read.toList())
    }

    @Test
    fun aRafClaimingAnAbsurdSizeIsNotAllocated() {
        // 0x64/0x68 are the CFA offset and length; a corrupt header can claim
        // anything, and honouring it unchecked is a multi-gigabyte allocation.
        val head = ByteArray(0x80)
        for (i in 0x64..0x6B) head[i] = 0x7F
        val rebuilt = DonorRaf.padded(head)
        assertEquals("the bogus size must be refused, not honoured", head.size, rebuilt.size)
    }

    @Test
    fun photosAreLeftOutOfAQrPayload() {
        // Photo filenames are local names — the scanning device has no such files.
        val recipe = Recipe(name = "Dorothea", photos = listOf("a.jpg", "b.jpg"))
        val payload = recipeQrContent(recipe)
        assertFalse(payload.contains("a.jpg"))
        assertFalse(payload.contains("b.jpg"))
        // ...but the recipe itself still travels
        assertEquals(true, payload.contains("Dorothea"))
    }
}
