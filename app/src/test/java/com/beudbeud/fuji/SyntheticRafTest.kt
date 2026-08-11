package com.beudbeud.fuji

import com.beudbeud.fuji.data.DonorRaf
import com.beudbeud.fuji.data.SyntheticRaf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticRafTest {
    // The real X-Trans mosaic, 0=red 1=green 2=blue
    private val pattern = byteArrayOf(
        1, 0, 2, 1, 2, 0,
        2, 1, 1, 0, 1, 1,
        0, 1, 1, 2, 1, 1,
        1, 2, 0, 1, 0, 2,
        0, 1, 1, 2, 1, 1,
        2, 1, 1, 0, 1, 1,
    )

    private val width = 72
    private val height = 48
    private val headerAt = 0x100
    private val cfaAt = 0x200
    private val padding = 8
    private val pixelsAt = cfaAt + padding

    /** A minimal but structurally real donor: header, tag list, sensor block. */
    private fun donor(cfaLength: Int = width * height * 2 + padding): ByteArray {
        val raf = ByteArray(cfaAt + cfaLength)
        "FUJIFILMCCD-RAW ".toByteArray().copyInto(raf, 0)
        "X-T30 III".toByteArray().copyInto(raf, 0x1C)
        fun u32(at: Int, v: Int) {
            raf[at] = (v ushr 24).toByte(); raf[at + 1] = (v ushr 16).toByte()
            raf[at + 2] = (v ushr 8).toByte(); raf[at + 3] = v.toByte()
        }
        fun u16(at: Int, v: Int) {
            raf[at] = (v ushr 8).toByte(); raf[at + 1] = v.toByte()
        }
        u32(0x54, 0x80); u32(0x58, 0)          // preview, unused here
        u32(0x5C, headerAt); u32(0x60, 52)     // 4 + (4+4) + (4+36)
        u32(0x64, cfaAt); u32(0x68, cfaLength)

        u32(headerAt, 2)                        // two tags
        u16(headerAt + 4, 0x0100); u16(headerAt + 6, 4)
        u16(headerAt + 8, height); u16(headerAt + 10, width)
        u16(headerAt + 12, 0x0131); u16(headerAt + 14, 36)
        pattern.copyInto(raf, headerAt + 16)
        return raf
    }

    private fun ByteArray.site(y: Int, x: Int): Int {
        val i = pixelsAt + (y * width + x) * 2
        return (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)
    }

    @Test
    fun readsGeometryOutOfTheDonorItself() {
        val layout = SyntheticRaf.layout(donor())!!
        assertEquals(width, layout.width)
        assertEquals(height, layout.height)
        assertEquals(pixelsAt, layout.pixels)
        assertTrue(pattern.contentEquals(layout.pattern))
    }

    @Test
    fun refusesWhatItCannotRewrite() {
        // A compressed RAF: the block is far smaller than the readout needs
        assertNull(SyntheticRaf.layout(donor(cfaLength = 4096)))
        assertFalse(SyntheticRaf.chart(donor(cfaLength = 4096), patchPx = 24, steps = 2))
        assertNull(SyntheticRaf.layout(ByteArray(4096)))
    }

    @Test
    fun paintsFlatPatchesOnTheRightPhotosites() {
        val raf = donor()
        val header = raf.copyOfRange(0, pixelsAt)
        assertTrue(SyntheticRaf.chart(raf, patchPx = 24, steps = 2))

        // Everything before the pixel data must survive untouched
        assertTrue(header.contentEquals(raf.copyOfRange(0, pixelsAt)))

        // 3 x 2 patches; with two levels the sweep runs 0,0,0 then 1,0,0 ...
        // Patch 0 is the all-zero corner of the cube.
        assertEquals(0, raf.site(0, 0))
        assertEquals(0, raf.site(7, 13))

        // Patch 1 is full red. At phase 1, site (0,2) of a patch is a red
        // photosite, (0,0) is green and (0,5) is blue.
        assertEquals(16383, raf.site(0, 24 + 2))
        assertEquals(0, raf.site(0, 24 + 0))
        assertEquals(0, raf.site(0, 24 + 5))
        // ...and it repeats every 6 sites, so the patch really is one flat colour
        assertEquals(16383, raf.site(6, 24 + 8))
        assertEquals(16383, raf.site(12, 24 + 14))
    }

    @Test
    fun aStoredContainerRebuildsIntoAUsableRaf() {
        // What DonorRaf keeps: the file up to the first photosite, nothing more
        val original = donor()
        val stored = original.copyOfRange(0, SyntheticRaf.layout(original)!!.pixels)
        assertTrue("the sensor data must not be stored", stored.size < original.size)

        val rebuilt = DonorRaf.padded(stored)
        assertEquals(original.size, rebuilt.size)
        val layout = SyntheticRaf.layout(rebuilt)!!
        assertEquals(width, layout.width)
        assertEquals(pixelsAt, layout.pixels)
        // ...and it still takes a chart, which is the whole point of keeping it
        assertTrue(SyntheticRaf.chart(rebuilt, patchPx = 24, steps = 2))
        assertEquals(16383, rebuilt.site(0, 24 + 2))
    }

    @Test
    fun everyCubeColourGetsAPatchWhenThereIsRoom() {
        // 3 x 2 patches can only hold 6 of the 8 two-level combinations, so the
        // sweep must not silently stop after the first row.
        val raf = donor()
        SyntheticRaf.chart(raf, patchPx = 24, steps = 2)
        val reds = (0 until 2).flatMap { r -> (0 until 3).map { c -> raf.site(r * 24, c * 24 + 2) } }
        assertEquals(listOf(0, 16383, 0, 16383, 0, 16383), reds)
        // green is the second axis: it turns on for patches 2 and 3
        val greens = (0 until 2).flatMap { r -> (0 until 3).map { c -> raf.site(r * 24, c * 24) } }
        assertEquals(listOf(0, 0, 16383, 16383, 0, 0), greens)
        assertNotNull(SyntheticRaf.layout(raf))
    }
}
