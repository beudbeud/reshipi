package com.beudbeud.fuji.data

import android.content.Context
import com.beudbeud.fuji.R
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Supplies the RAF container that synthetic charts are painted into, so a
 * chart-based LUT needs no file from the user.
 *
 * The sensor data is about to be overwritten anyway, so it is never stored:
 * only the part of the file up to the first photosite is kept, about 5 MB of
 * 56. Rebuilding pads the rest with zeroes, which [SyntheticRaf.chart] then
 * covers completely.
 *
 * Two sources, in order:
 *  - a container kept from a RAF the user opened, which always matches their body;
 *  - one bundled in the app for the X-T30 III, carrying no serial number and a
 *    preview of the chart rather than the photograph it was derived from. It
 *    compresses to 10 KB because everything in it that mattered was structure.
 */
object DonorRaf {
    private fun head(context: Context) = File(context.filesDir, "donor.rafhead")

    /** Tombstone for the bundled container once a body has refused it. */
    private fun rejected(context: Context) = File(context.filesDir, "donor.rejected")

    /** Whether a chart export can run without asking for a file. */
    fun exists(context: Context) = head(context).length() > 0 || !rejected(context).exists()

    /**
     * Remembers [raf]'s container, returning whether it could be kept. A
     * compressed file has no container we can paint into, so it is not one.
     */
    fun save(context: Context, raf: ByteArray): Boolean {
        val layout = SyntheticRaf.layout(raf) ?: return false
        val ok = runCatching {
            head(context).writeBytes(raf.copyOfRange(0, layout.pixels))
        }.isSuccess
        // A container of their own supersedes any earlier refusal
        if (ok) rejected(context).delete()
        return ok
    }

    /** A full-size RAF ready to be painted, or null when none can be had. */
    fun load(context: Context): ByteArray? =
        usable(runCatching { head(context).readBytes() }.getOrNull())
            ?: if (rejected(context).exists()) null else usable(bundled(context))

    private fun bundled(context: Context): ByteArray? = runCatching {
        context.resources.openRawResource(R.raw.donor_x_t30_iii).use {
            GZIPInputStream(it).readBytes()
        }
    }.getOrNull()

    private fun usable(head: ByteArray?): ByteArray? {
        if (head == null || head.size < 0x6C) return null
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

    /**
     * Drops the container a body has just refused. A kept one simply goes; when
     * there was none, the refusal was the bundled X-T30 III container, and that
     * one has to be remembered or every export would offer it again.
     */
    fun forget(context: Context) {
        if (!head(context).delete()) runCatching { rejected(context).writeBytes(ByteArray(0)) }
    }
}
