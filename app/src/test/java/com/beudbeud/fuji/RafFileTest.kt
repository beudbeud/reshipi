package com.beudbeud.fuji

import com.beudbeud.fuji.data.RafFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RafFileTest {
    /** A RAF header: magic, version, serial, then the NUL-padded model. */
    private fun header(model: String): ByteArray {
        val out = ByteArray(1024)
        "FUJIFILMCCD-RAW ".toByteArray().copyInto(out, 0)
        "0201".toByteArray().copyInto(out, 0x10)
        model.toByteArray().copyInto(out, 0x1C)
        return out
    }

    @Test
    fun readsTheCameraModel() {
        // Spaces are part of the name — only the NUL padding ends the field
        assertEquals("X-T30 III", RafFile.cameraModel(header("X-T30 III")))
        assertEquals("X100VI", RafFile.cameraModel(header("X100VI")))
        assertTrue(RafFile.isRaf(header("X-T5")))
    }

    @Test
    fun rejectsAnythingElse() {
        assertFalse(RafFile.isRaf(ByteArray(1024)))
        assertNull(RafFile.cameraModel("not a raf at all".toByteArray()))
        assertNull(RafFile.cameraModel(ByteArray(4)))       // too short to index
        assertNull(RafFile.cameraModel(header("")))          // no model recorded
    }
}
