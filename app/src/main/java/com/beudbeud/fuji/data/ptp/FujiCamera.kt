package com.beudbeud.fuji.data.ptp

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.beudbeud.fuji.data.DebugLog
import java.io.IOException

/**
 * Fujifilm camera session over USB PTP — port of FilmKit's FujiCamera
 * (github.com/eggricesoy/filmkit, MIT license).
 *
 * Custom preset properties (0xD18C slot select, 0xD18D name, 0xD18E-0xD1A5
 * settings) confirmed working on X100VI (FilmKit) and X-T30 III (this app,
 * 2026-08). Other X-Processor 5 bodies advertise the same properties in
 * DeviceInfo, which is checked before writing.
 */
class FujiCamera private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val diag: String,
) {
    companion object {
        const val FUJI_VENDOR_ID = 0x04CB
        private const val TIMEOUT_MS = 8000
        // 16KB: bulkTransfer's safe maximum on all API levels / host controllers.
        // Writes must use it too — a 256KB write is rejected outright (-1), which
        // only ever showed up on the first big one, the RAF upload. Keep any value
        // here a multiple of 512: a short packet tells the camera the phase is over.
        private const val READ_CHUNK = 16 * 1024
        private const val SEND_CHUNK = 16 * 1024
        private const val PRESET_SLOT = 0xD18C
        private const val PRESET_NAME = 0xD18D

        fun findDevice(manager: UsbManager): UsbDevice? =
            manager.deviceList.values.firstOrNull { it.vendorId == FUJI_VENDOR_ID }

        fun open(manager: UsbManager, device: UsbDevice): FujiCamera {
            val iface = (0 until device.interfaceCount).map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
                ?: device.getInterface(0)
            val connection = manager.openDevice(device) ?: throw IOException("Cannot open USB device")
            if (!connection.claimInterface(iface, true)) {
                connection.close()
                throw IOException("Cannot claim USB interface")
            }
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
                }
            }
            if (epIn == null || epOut == null) {
                connection.releaseInterface(iface)
                connection.close()
                throw IOException("No bulk endpoints found")
            }
            val diag = "if=${iface.interfaceClass}/${iface.interfaceSubclass}" +
                " in=0x${epIn.address.toString(16)} out=0x${epOut.address.toString(16)}" +
                " nIf=${device.interfaceCount}"
            DebugLog.log("USB open: ${device.productName} pid=0x${device.productId.toString(16)} $diag")
            return FujiCamera(connection, iface, epIn, epOut, diag)
        }
    }

    private var transactionId = 0
    private var sessionOpen = false

    private fun opName(opcode: Int) = "op 0x${opcode.toString(16)}"

    private fun send(type: Int, code: Int, txId: Int, params: IntArray, data: ByteArray) {
        val packet = packContainer(type, code, txId, params, data)
        val started = System.currentTimeMillis()
        var offset = 0
        while (offset < packet.size) {
            val len = minOf(SEND_CHUNK, packet.size - offset)
            val sent = connection.bulkTransfer(epOut, packet, offset, len, TIMEOUT_MS)
            if (sent != len) {
                throw IOException(
                    "USB write failed ($sent/$len at $offset/${packet.size}) ${opName(code)} [$diag]"
                )
            }
            offset += len
        }
        // A RAF is thousands of chunks; report the rate so a slow link is visible
        if (packet.size > SEND_CHUNK) {
            val ms = (System.currentTimeMillis() - started).coerceAtLeast(1)
            DebugLog.log("sent ${packet.size / 1024}KB ${opName(code)} in ${ms}ms (${packet.size / ms}KB/s)")
        }
    }

    private fun recv(opcode: Int, timeoutMs: Int = TIMEOUT_MS): PtpContainer {
        val buf = ByteArray(READ_CHUNK)
        // The first read can hit a transient -1 while the camera settles; retry briefly.
        var n = -1
        for (attempt in 1..3) {
            n = connection.bulkTransfer(epIn, buf, buf.size, timeoutMs)
            if (n >= 12) break
            Thread.sleep(300)
        }
        if (n < 12) throw IOException("USB read failed ($n) ${opName(opcode)} [$diag]")
        val total = containerLength(buf)
        val out = java.io.ByteArrayOutputStream(minOf(total, 64 shl 20))
        out.write(buf, 0, n)
        while (out.size() < total) {
            val m = connection.bulkTransfer(epIn, buf, buf.size, timeoutMs)
            if (m <= 0) throw IOException("USB read continuation failed ($m) ${opName(opcode)} [$diag]")
            out.write(buf, 0, m)
        }
        return unpackContainer(out.toByteArray())
    }

    /** Command with optional incoming data phase. Returns (responseCode, data). */
    private fun sendCommand(
        opcode: Int,
        params: IntArray = IntArray(0),
        timeoutMs: Int = TIMEOUT_MS,
    ): Pair<Int, ByteArray> {
        val txId = ++transactionId
        send(PtpContainerType.COMMAND, opcode, txId, params, ByteArray(0))
        var resp = recv(opcode, timeoutMs)
        var data = ByteArray(0)
        if (resp.type == PtpContainerType.DATA) {
            data = resp.data
            resp = recv(opcode, timeoutMs)
        }
        if (resp.type != PtpContainerType.RESPONSE) throw IOException("Expected RESPONSE, got ${resp.type}")
        return resp.code to data
    }

    /** Command with outgoing data phase (SetDevicePropValue). Returns responseCode. */
    private fun sendDataCommand(
        opcode: Int,
        params: IntArray,
        data: ByteArray,
        timeoutMs: Int = TIMEOUT_MS,
    ): Int {
        val txId = ++transactionId
        send(PtpContainerType.COMMAND, opcode, txId, params, ByteArray(0))
        send(PtpContainerType.DATA, opcode, txId, IntArray(0), data)
        val resp = recv(opcode, timeoutMs)
        if (resp.type != PtpContainerType.RESPONSE) throw IOException("Expected RESPONSE, got ${resp.type}")
        return resp.code
    }

    fun openSession() {
        val (code, _) = sendCommand(PtpOp.OPEN_SESSION, intArrayOf(1))
        DebugLog.log("OpenSession → 0x${code.toString(16)}")
        when (code) {
            PtpResp.OK -> sessionOpen = true
            PtpResp.SESSION_ALREADY_OPEN -> {
                // Stale session from a previous connection: close and retry once.
                DebugLog.log("Stale session, closing and retrying")
                runCatching { sendCommand(PtpOp.CLOSE_SESSION) }
                val (retry, _) = sendCommand(PtpOp.OPEN_SESSION, intArrayOf(1))
                if (retry != PtpResp.OK) throw IOException("OpenSession failed: 0x${retry.toString(16)}")
                sessionOpen = true
            }
            else -> throw IOException("OpenSession failed: 0x${code.toString(16)}")
        }
    }

    /** Property IDs advertised in PTP DeviceInfo. */
    fun supportedProperties(): Set<Int> {
        val (code, data) = sendCommand(PtpOp.GET_DEVICE_INFO)
        if (code != PtpResp.OK) throw IOException("GetDeviceInfo failed: 0x${code.toString(16)}")
        val r = PtpReader(data)
        r.u16(); r.u32(); r.u16(); r.str(); r.u16()
        r.u16array() // operations
        r.u16array() // events
        val props = r.u16array().toSet()
        DebugLog.log("DeviceInfo: ${props.size} properties, presetProps=${0xD18C in props}")
        return props
    }

    private fun readProp(propId: Int): ByteArray? = runCatching {
        val (code, data) = sendCommand(PtpOp.GET_DEVICE_PROP_VALUE, intArrayOf(propId))
        if (code == PtpResp.OK && data.isNotEmpty()) data else null
    }.getOrNull()

    private fun writeProp(propId: Int, bytes: ByteArray): Boolean =
        sendDataCommand(PtpOp.SET_DEVICE_PROP_VALUE, intArrayOf(propId), bytes) == PtpResp.OK

    class PresetWriteResult(val ok: Boolean, val warnings: List<String>)

    /** One custom-preset slot as stored on the camera. */
    class CameraPreset(val slot: Int, val name: String, val props: Map<Int, ByteArray>)

    /** Camera model reported in PTP DeviceInfo, e.g. "X-T30 III". */
    fun modelName(): String {
        val (code, data) = sendCommand(PtpOp.GET_DEVICE_INFO)
        if (code != PtpResp.OK) return ""
        val r = PtpReader(data)
        r.u16(); r.u32(); r.u16(); r.str(); r.u16()
        r.u16array(); r.u16array(); r.u16array(); r.u16array(); r.u16array()
        r.str() // manufacturer
        return r.str() // model
    }

    /**
     * Read the camera's custom-preset slots (C1-C7). Selecting a slot and
     * reading its properties is the same mechanism used for writing, run in
     * reverse; the originally selected slot is restored afterwards.
     */
    fun readPresets(slots: IntRange = 1..7): List<CameraPreset> {
        val original = readProp(PRESET_SLOT)?.let {
            java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
        } ?: 1
        val presets = mutableListOf<CameraPreset>()
        for (slot in slots) {
            if (!writeProp(PRESET_SLOT, packU16(slot))) {
                DebugLog.log("readPresets: cannot select slot C$slot")
                continue
            }
            Thread.sleep(100)
            val name = readProp(PRESET_NAME)?.let { parsePtpString(it) }.orEmpty()
            val props = buildMap {
                for (id in 0xD18E..0xD1A5) readProp(id)?.let { put(id, it) }
            }
            presets += CameraPreset(slot, name, props)
            // Log the raw white-balance and film-sim codes for every slot, named
            // or not: this is the diagnostic that identifies unmapped values
            // (custom WB), and an unnamed slot must not silently skip it.
            fun raw(id: Int) = props[id]?.takeIf { it.size >= 2 }
                ?.let { "0x%04X".format((it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8)) }
                ?: "-"
            DebugLog.log(
                "readPresets: C$slot \"$name\" (${props.size} props) " +
                    "wb=${raw(0xD199)} sim=${raw(0xD192)} kelvin=${raw(0xD19C)}"
            )
        }
        runCatching { writeProp(PRESET_SLOT, packU16(original)) }
        return presets
    }

    /**
     * True when a read-back differs from what we wrote only because the camera
     * stored an equivalent encoding of the same setting.
     *
     * Grain (0xD195) is the known case: the reference encoding is 1=Off,
     * 2=WeakSmall, 3=StrongSmall, 4=WeakLarge, 5=StrongLarge, but the X-T30 III
     * keeps the size dimension while off and normalizes a written 1 to 6
     * (off, small) or 7 (off, large) depending on the slot's current size. The
     * stored setting is still "off", so this is not a failed write.
     */
    private fun isNormalization(propId: Int, wrote: ByteArray, read: ByteArray): Boolean {
        if (propId != 0xD195 || wrote.size != 2 || read.size != 2) return false
        fun u16(b: ByteArray) = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
        val grainOff = setOf(1, 6, 7)
        return u16(wrote) in grainOff && u16(read) in grainOff
    }

    /**
     * Write a complete preset to a camera slot (1-7) with read-back verification.
     * Slot selection or name write failure is fatal; individual property
     * rejections and verify mismatches are reported as warnings.
     */
    fun writePreset(slot: Int, name: String, props: List<Pair<Int, ByteArray>>): PresetWriteResult {
        if (!writeProp(PRESET_SLOT, packU16(slot))) {
            return PresetWriteResult(false, listOf("Failed to select slot C$slot"))
        }
        Thread.sleep(100)
        if (!writeProp(PRESET_NAME, packPtpString(name))) {
            return PresetWriteResult(false, listOf("Failed to write preset name"))
        }

        val warnings = mutableListOf<String>()
        val written = mutableSetOf<Int>()
        for ((id, bytes) in props) {
            if (writeProp(id, bytes)) written += id
            else warnings += "0x${id.toString(16).uppercase()}: write rejected"
        }

        readProp(PRESET_NAME)?.let {
            val readBack = parsePtpString(it)
            if (readBack != name) warnings += "Name verify: wrote \"$name\", read \"$readBack\""
        }
        fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }
        for ((id, bytes) in props) {
            if (id !in written) continue
            val readBack = readProp(id) ?: continue
            if (isNormalization(id, bytes, readBack)) continue
            if (readBack.size == bytes.size && !readBack.contentEquals(bytes)) {
                warnings += "0x${id.toString(16).uppercase()}: verify mismatch " +
                    "(wrote ${hex(bytes)}, read ${hex(readBack)})"
            }
        }
        DebugLog.log("writePreset C$slot \"$name\": ${written.size}/${props.size} written, ${warnings.size} warnings")
        warnings.forEach { DebugLog.log("  warn: $it") }
        return PresetWriteResult(true, warnings)
    }

    fun close() {
        if (sessionOpen) runCatching { sendCommand(PtpOp.CLOSE_SESSION) }
        sessionOpen = false
        connection.releaseInterface(iface)
        connection.close()
    }

    // -- X RAW Studio protocol: in-camera RAW conversion --

    /** Upload a RAF file to the camera (Fuji vendor two-step transfer). */
    fun sendRaf(raf: ByteArray) {
        DebugLog.log("sendRaf: ${raf.size / 1024 / 1024}MB")
        val name = packPtpString("FUP_FILE.dat")
        val info = java.nio.ByteBuffer.allocate(52 + name.size + 3)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        info.putInt(0)                    // StorageID
        info.putShort(0xF802.toShort())   // ObjectFormat — must be 0xF802 for RAF
        info.putShort(0)                  // ProtectionStatus
        info.putInt(raf.size)             // CompressedSize
        info.putShort(0)                  // ThumbFormat
        info.putInt(0); info.putInt(0); info.putInt(0) // thumb size/w/h
        info.putInt(0); info.putInt(0)    // image w/h
        info.putInt(0)                    // bit depth
        info.putInt(0)                    // parent
        info.putShort(0)                  // association type
        info.putInt(0); info.putInt(0)    // association desc, sequence
        info.put(name)
        info.put(byteArrayOf(0, 0, 0))    // capture/modification date, keywords
        var code = sendDataCommand(PtpOp.FUJI_SEND_OBJECT_INFO, intArrayOf(0, 0, 0), info.array())
        if (code != PtpResp.OK) throw IOException("SendObjectInfo failed: 0x${code.toString(16)}")
        code = sendDataCommand(PtpOp.FUJI_SEND_OBJECT, IntArray(0), raf, timeoutMs = 60_000)
        if (code != PtpResp.OK) throw IOException("SendObject failed: 0x${code.toString(16)}")
    }

    /** Current conversion profile (0xD185) — requires a RAF loaded first. */
    fun getProfile(): ByteArray {
        val (code, data) = sendCommand(PtpOp.GET_DEVICE_PROP_VALUE, intArrayOf(FujiProp.RAW_CONV_PROFILE))
        if (code != PtpResp.OK || data.isEmpty()) {
            throw IOException("getProfile failed: 0x${code.toString(16)} (${data.size} bytes)")
        }
        DebugLog.log("getProfile: ${data.size} bytes")
        return data
    }

    fun setProfile(profile: ByteArray) {
        val code = sendDataCommand(PtpOp.SET_DEVICE_PROP_VALUE, intArrayOf(FujiProp.RAW_CONV_PROFILE), profile)
        if (code != PtpResp.OK) throw IOException("setProfile failed: 0x${code.toString(16)}")
    }

    fun triggerConversion() {
        val code = sendDataCommand(PtpOp.SET_DEVICE_PROP_VALUE, intArrayOf(FujiProp.START_RAW_CONVERSION), packU16(0))
        if (code != PtpResp.OK) throw IOException("StartRawConversion failed: 0x${code.toString(16)}")
    }

    /** Poll for the converted JPEG, download and delete it on the camera. */
    fun waitForResult(timeoutMs: Long = 30_000): ByteArray {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val (code, data) = sendCommand(PtpOp.GET_OBJECT_HANDLES, intArrayOf(-1, 0, 0))
            if (code != PtpResp.OK) throw IOException("GetObjectHandles failed: 0x${code.toString(16)}")
            if (data.size >= 8) {
                val bb = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                if (bb.int > 0) {
                    val handle = bb.int
                    val (getCode, jpeg) = sendCommand(PtpOp.GET_OBJECT, intArrayOf(handle), timeoutMs = 60_000)
                    if (getCode != PtpResp.OK) throw IOException("GetObject failed: 0x${getCode.toString(16)}")
                    runCatching { sendCommand(PtpOp.DELETE_OBJECT, intArrayOf(handle)) }
                    DebugLog.log("conversion result: ${jpeg.size / 1024}KB")
                    return jpeg
                }
            }
            Thread.sleep(1000)
        }
        throw IOException("Conversion timeout (${timeoutMs / 1000}s)")
    }
}
