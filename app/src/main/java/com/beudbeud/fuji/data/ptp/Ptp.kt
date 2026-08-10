package com.beudbeud.fuji.data.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder

// PTP/USB protocol primitives — Kotlin port of FilmKit's ptp layer
// (github.com/eggricesoy/filmkit, MIT license).

object PtpOp {
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_DEVICE_PROP_VALUE = 0x1015
    const val SET_DEVICE_PROP_VALUE = 0x1016
}

object PtpResp {
    const val OK = 0x2001
    const val SESSION_ALREADY_OPEN = 0x201E
}

object PtpContainerType {
    const val COMMAND = 1
    const val DATA = 2
    const val RESPONSE = 3
}

class PtpContainer(
    val type: Int,
    val code: Int,
    val transactionId: Int,
    val params: IntArray,
    val data: ByteArray,
)

private const val HEADER_SIZE = 12

fun packContainer(type: Int, code: Int, transactionId: Int, params: IntArray, data: ByteArray): ByteArray {
    val buf = ByteBuffer.allocate(HEADER_SIZE + params.size * 4 + data.size).order(ByteOrder.LITTLE_ENDIAN)
    buf.putInt(buf.capacity())
    buf.putShort(type.toShort())
    buf.putShort(code.toShort())
    buf.putInt(transactionId)
    params.forEach { buf.putInt(it) }
    buf.put(data)
    return buf.array()
}

fun unpackContainer(raw: ByteArray): PtpContainer {
    require(raw.size >= HEADER_SIZE) { "Container too short: ${raw.size} bytes" }
    val view = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    view.int // total length
    val type = view.short.toInt() and 0xFFFF
    val code = view.short.toInt() and 0xFFFF
    val transactionId = view.int

    var params = IntArray(0)
    var data = ByteArray(0)
    if (type == PtpContainerType.DATA) {
        data = raw.copyOfRange(HEADER_SIZE, raw.size)
    } else if (type == PtpContainerType.RESPONSE) {
        val count = minOf((raw.size - HEADER_SIZE) / 4, 5)
        params = IntArray(count) { view.int }
    }
    return PtpContainer(type, code, transactionId, params, data)
}

/** Total length field from a raw container's first 4 bytes. */
fun containerLength(raw: ByteArray): Int =
    ByteBuffer.wrap(raw, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int

// -- Binary pack helpers (little-endian) --

fun packU16(value: Int): ByteArray =
    ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

fun packI16(value: Int): ByteArray = packU16(value)

/** PTP string: length byte (incl. null) + UTF-16LE chars + null terminator. */
fun packPtpString(str: String): ByteArray {
    if (str.isEmpty()) return byteArrayOf(0)
    val buf = ByteBuffer.allocate(1 + (str.length + 1) * 2).order(ByteOrder.LITTLE_ENDIAN)
    buf.put((str.length + 1).toByte())
    str.forEach { buf.putShort(it.code.toShort()) }
    buf.putShort(0)
    return buf.array()
}

/** Parse a PTP string (length byte + UCS-2LE chars). */
fun parsePtpString(data: ByteArray): String {
    if (data.isEmpty()) return ""
    val view = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val numChars = view.get().toInt() and 0xFF
    return buildString {
        repeat(minOf(numChars, (data.size - 1) / 2)) {
            val ch = view.short.toInt() and 0xFFFF
            if (ch != 0) append(ch.toChar())
        }
    }
}

/** Cursor-based reader for PTP datasets (GetDeviceInfo etc.). */
class PtpReader(data: ByteArray) {
    private val view = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    fun u16(): Int = view.short.toInt() and 0xFFFF
    fun u32(): Int = view.int

    fun str(): String {
        val numChars = view.get().toInt() and 0xFF
        return buildString {
            repeat(numChars) {
                val ch = view.short.toInt() and 0xFFFF
                if (ch != 0) append(ch.toChar())
            }
        }
    }

    fun u16array(): IntArray {
        val count = u32()
        return IntArray(count) { u16() }
    }
}
