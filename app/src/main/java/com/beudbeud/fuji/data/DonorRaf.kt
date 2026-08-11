package com.beudbeud.fuji.data

import android.content.Context
import java.io.File

/**
 * Keeps one RAF around to carry synthetic charts, so a chart-based LUT only
 * ever needs a file picked once.
 *
 * The sensor data is about to be overwritten anyway, so it is not stored: only
 * the part of the file up to the first photosite is kept, roughly 5 MB instead
 * of 56. Rebuilding pads the rest with zeroes, which [SyntheticRaf.chart] then
 * paints over completely.
 */
object DonorRaf {
    private fun file(context: Context) = File(context.filesDir, "donor.rafhead")

    fun exists(context: Context) = file(context).length() > 0

    /**
     * Remembers [raf]'s container, returning whether it could be kept. A
     * compressed file has no container we can paint into, so it is not one.
     */
    fun save(context: Context, raf: ByteArray): Boolean {
        val layout = SyntheticRaf.layout(raf) ?: return false
        return runCatching { file(context).writeBytes(raf.copyOfRange(0, layout.pixels)) }.isSuccess
    }

    /** The stored container, resized back to a full RAF, or null if none is kept. */
    fun load(context: Context): ByteArray? {
        val head = runCatching { file(context).readBytes() }.getOrNull() ?: return null
        if (head.size < 0x6C) return null
        // A head from an older version, or a truncated write: start over
        val full = runCatching { padded(head) }.getOrNull() ?: return null
        return if (SyntheticRaf.layout(full) != null) full else null
    }

    /**
     * ponytail: the file is taken to end with the sensor block, which is what
     * every RAF seen so far does. A body that appended a trailer would lose it.
     */
    internal fun padded(head: ByteArray): ByteArray {
        fun u32(i: Int) = ((head[i].toInt() and 0xFF) shl 24) or
            ((head[i + 1].toInt() and 0xFF) shl 16) or
            ((head[i + 2].toInt() and 0xFF) shl 8) or (head[i + 3].toInt() and 0xFF)
        val total = u32(0x64) + u32(0x68)
        return head.copyOf(maxOf(total, head.size))
    }

    fun forget(context: Context) {
        file(context).delete()
    }
}
