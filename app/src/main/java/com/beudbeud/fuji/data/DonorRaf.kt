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
        fun u32(i: Int) = ((head[i].toLong() and 0xFF) shl 24) or
            ((head[i + 1].toLong() and 0xFF) shl 16) or
            ((head[i + 2].toLong() and 0xFF) shl 8) or (head[i + 3].toLong() and 0xFF)
        // The size comes out of a file the user picked, so it is a claim, not a
        // fact: honouring it unchecked turns a corrupt header into a 4GB
        // allocation. Anything beyond a plausible RAF means this is not one.
        val total = u32(0x64) + u32(0x68)
        if (total > MAX_RAF) return head
        return head.copyOf(maxOf(total.toInt(), head.size))
    }

    /** Largest uncompressed RAF we expect: 102MP at 16 bits is under this. */
    private const val MAX_RAF = 256L shl 20

    /**
     * Drops the container a body has just refused. A kept one simply goes; when
     * there was none, the refusal was the bundled X-T30 III container, and that
     * one has to be remembered or every export would offer it again.
     */
    fun forget(context: Context) {
        if (!head(context).delete()) runCatching { rejected(context).writeBytes(ByteArray(0)) }
    }

    /**
     * Forgets everything about containers, refusals included, so the next export
     * starts from nothing.
     *
     * [forget] cannot do this: it records a refusal rather than clearing one, and
     * a container is otherwise only ever replaced by a successful save. That
     * leaves no way out of one that is kept but unusable — a write cut short on a
     * full disk, say — because the export never gets far enough to reject it.
     */
    fun reset(context: Context) {
        head(context).delete()
        rejected(context).delete()
    }
}
