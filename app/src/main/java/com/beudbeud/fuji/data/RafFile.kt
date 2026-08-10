package com.beudbeud.fuji.data

/**
 * The little we need from a RAF header.
 *
 * ```
 * 0x00  "FUJIFILMCCD-RAW " magic (16 bytes)
 * 0x10  format version     (4)
 * 0x14  camera serial      (8)
 * 0x1C  camera model       (32, NUL padded)
 * ```
 *
 * In-camera conversion only accepts a file shot on the body it is talking to,
 * so the model here decides whether a conversion can work at all.
 */
object RafFile {
    private const val MAGIC = "FUJIFILMCCD-RAW"
    private const val MODEL_OFFSET = 0x1C
    private const val MODEL_LENGTH = 32

    fun isRaf(bytes: ByteArray): Boolean =
        bytes.size > MODEL_OFFSET + MODEL_LENGTH &&
            bytes.decodeToString(0, MAGIC.length) == MAGIC

    /** Camera model that produced the file, or null when this is not a RAF. */
    fun cameraModel(bytes: ByteArray): String? {
        if (!isRaf(bytes)) return null
        // The field is NUL padded, and model names contain spaces ("X-T30 III"),
        // so only a NUL ends it — cutting at the first space would truncate.
        return bytes.decodeToString(MODEL_OFFSET, MODEL_OFFSET + MODEL_LENGTH)
            .substringBefore('\u0000')
            .trim()
            .ifBlank { null }
    }
}
