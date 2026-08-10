package com.beudbeud.fuji.data.ptp

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

/**
 * Fujifilm camera session over USB PTP — port of FilmKit's FujiCamera
 * (github.com/eggricesoy/filmkit, MIT license).
 *
 * Custom preset properties (0xD18C slot select, 0xD18D name, 0xD18E-0xD1A5
 * settings) confirmed on X100VI; other X-Processor 5 bodies advertise the
 * same properties in DeviceInfo, which is checked before writing.
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
        // 16KB: bulkTransfer's safe maximum on all API levels / host controllers
        private const val READ_CHUNK = 16 * 1024
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
            return FujiCamera(connection, iface, epIn, epOut, diag)
        }
    }

    private var transactionId = 0
    private var sessionOpen = false

    private fun opName(opcode: Int) = "op 0x${opcode.toString(16)}"

    private fun send(type: Int, code: Int, txId: Int, params: IntArray, data: ByteArray) {
        val packet = packContainer(type, code, txId, params, data)
        val sent = connection.bulkTransfer(epOut, packet, packet.size, TIMEOUT_MS)
        if (sent != packet.size) {
            throw IOException("USB write failed ($sent/${packet.size}) ${opName(code)} [$diag]")
        }
    }

    private fun recv(opcode: Int): PtpContainer {
        val buf = ByteArray(READ_CHUNK)
        // The first read can hit a transient -1 while the camera settles; retry briefly.
        var n = -1
        for (attempt in 1..3) {
            n = connection.bulkTransfer(epIn, buf, buf.size, TIMEOUT_MS)
            if (n >= 12) break
            Thread.sleep(300)
        }
        if (n < 12) throw IOException("USB read failed ($n) ${opName(opcode)} [$diag]")
        var data = buf.copyOf(n)
        val total = containerLength(data)
        while (data.size < total) {
            val m = connection.bulkTransfer(epIn, buf, buf.size, TIMEOUT_MS)
            if (m <= 0) throw IOException("USB read continuation failed ($m) ${opName(opcode)} [$diag]")
            data += buf.copyOf(m)
        }
        return unpackContainer(data)
    }

    /** Command with optional incoming data phase. Returns (responseCode, data). */
    private fun sendCommand(opcode: Int, params: IntArray = IntArray(0)): Pair<Int, ByteArray> {
        val txId = ++transactionId
        send(PtpContainerType.COMMAND, opcode, txId, params, ByteArray(0))
        var resp = recv(opcode)
        var data = ByteArray(0)
        if (resp.type == PtpContainerType.DATA) {
            data = resp.data
            resp = recv(opcode)
        }
        if (resp.type != PtpContainerType.RESPONSE) throw IOException("Expected RESPONSE, got ${resp.type}")
        return resp.code to data
    }

    /** Command with outgoing data phase (SetDevicePropValue). Returns responseCode. */
    private fun sendDataCommand(opcode: Int, params: IntArray, data: ByteArray): Int {
        val txId = ++transactionId
        send(PtpContainerType.COMMAND, opcode, txId, params, ByteArray(0))
        send(PtpContainerType.DATA, opcode, txId, IntArray(0), data)
        val resp = recv(opcode)
        if (resp.type != PtpContainerType.RESPONSE) throw IOException("Expected RESPONSE, got ${resp.type}")
        return resp.code
    }

    fun openSession() {
        val (code, _) = sendCommand(PtpOp.OPEN_SESSION, intArrayOf(1))
        when (code) {
            PtpResp.OK -> sessionOpen = true
            PtpResp.SESSION_ALREADY_OPEN -> {
                // Stale session from a previous connection: close and retry once.
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
        return r.u16array().toSet()
    }

    private fun readProp(propId: Int): ByteArray? = runCatching {
        val (code, data) = sendCommand(PtpOp.GET_DEVICE_PROP_VALUE, intArrayOf(propId))
        if (code == PtpResp.OK && data.isNotEmpty()) data else null
    }.getOrNull()

    private fun writeProp(propId: Int, bytes: ByteArray): Boolean =
        sendDataCommand(PtpOp.SET_DEVICE_PROP_VALUE, intArrayOf(propId), bytes) == PtpResp.OK

    class PresetWriteResult(val ok: Boolean, val warnings: List<String>)

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
        for ((id, bytes) in props) {
            if (id !in written) continue
            val readBack = readProp(id) ?: continue
            if (readBack.size == bytes.size && !readBack.contentEquals(bytes)) {
                warnings += "0x${id.toString(16).uppercase()}: verify mismatch"
            }
        }
        return PresetWriteResult(true, warnings)
    }

    fun close() {
        if (sessionOpen) runCatching { sendCommand(PtpOp.CLOSE_SESSION) }
        sessionOpen = false
        connection.releaseInterface(iface)
        connection.close()
    }
}
