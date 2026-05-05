/*
 * openZinkBooth
 * Copyright (C) 2026 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.photo.openzinkbooth.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import androidx.annotation.StringRes
import com.photo.openzinkbooth.R
import com.photo.openzinkbooth.core.utils.LogManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date
import java.util.UUID

// ---------------------------------------------------------------------------
// Sprocket RFCOMM Protocol
//
// The printer communicates over two simultaneous Bluetooth channels.
// This class handles the Bluetooth Classic (RFCOMM) channel, which is used
// for all write operations (settings, printing). The BLE GATT channel
// (SprocketPrinter) handles session setup and status polling.
//
// Frame encoding:
//
//   Small frame  (payload ≤ 255 bytes):
//     [magic: 3 bytes] [0x01] [len: 1] [cmd] [payload...]
//
//   Large frame  (payload > 255 bytes, used for file transfers):
//     [magic: 3 bytes] [0x02] [len: 2 LE] [cmd] [payload...]
//
//   Response (printer → phone):
//     The printer sends 0x48 as a 1-byte sentinel, then the rest of the frame.
//     Full buffer: [magic: 3] [flag] [len] [cmd] [payload...]
//     decodeFrame() strips the header and returns [cmd][payload...].
// ---------------------------------------------------------------------------

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

private val FRAME_MAGIC = byteArrayOf(0x48, 0x50, 0x2B)  // "HP+"
private const val FLAG_SMALL: Byte = 0x01
private const val FLAG_LARGE: Byte = 0x02

// System attribute field tags (RD_SYS_ATT)
private object AttrTag {
    const val SOFTWARE_VERSION    = 0x01.toByte()
    const val IMMUTABLE_NAME      = 0x03.toByte()
    const val CUSTOM_NAME         = 0x04.toByte()
    const val SERIAL_NUMBER       = 0x05.toByte()
    const val DEVICE_ID           = 0x06.toByte()
    const val FEATURE_SET_VERSION = 0x08.toByte()
    const val DEVICE_SUPER_MODEL  = 0x09.toByte()
    const val DEVICE_SUB_MODEL    = 0x0A.toByte()
    const val BLUETOOTH_MAC       = 0x0B.toByte()
    const val HARDWARE_VERSION    = 0x0C.toByte()
    const val FIRST_PRINT        = 0x0E.toByte()
}

// System config field tags (RD/WR_SYS_CFG)
private object CfgTag {
    const val SLEEP_TIMER     = 0x01.toByte()
    const val OFF_TIMER       = 0x02.toByte()
    const val USER_COLOR      = 0x03.toByte()
    const val PAUSE_PRINTING  = 0x04.toByte()
    const val HOSTS_THRESHOLD = 0x05.toByte()
}

// File transfer status codes (FILE_WRITE_RSP)
private object WriteStatus {
    const val OK        = 0x01.toByte()
    const val COMPLETE  = 0x02.toByte()
    const val CANCELLED = 0x03.toByte()
    const val FAILED    = 0x04.toByte()
}

// ---------------------------------------------------------------------------
// Public domain types
// ---------------------------------------------------------------------------
enum class SleepTimer(
    val minutes: Int,
    @StringRes val labelRes: Int
) {
    NEVER(0, R.string.sleep_timer_never),
    MIN_3(3, R.string.sleep_timer_3),
    MIN_5(5, R.string.sleep_timer_5),
    MIN_10(10, R.string.sleep_timer_10);

    companion object {
        fun fromMinutes(m: Int) = entries.firstOrNull { it.minutes == m } ?: NEVER
    }
}

enum class AutoOff(
    val minutes: Int,
    @StringRes val labelRes: Int
) {
    NEVER(0, R.string.auto_off_never),
    HOUR_1(60, R.string.auto_off_1h),
    HOUR_2(120, R.string.auto_off_2h),
    HOUR_5(300, R.string.auto_off_5h);

    companion object {
        fun fromMinutes(m: Int) = entries.firstOrNull { it.minutes == m } ?: NEVER
    }
}

/** LED color presets with verified RGB values. */
enum class UserColor(
    val r: Int,
    val g: Int,
    val b: Int,
    @StringRes val labelRes: Int
) {
    PINK(  0xFF, 0x00, 0x22, R.string.color_pink),
    PURPLE(0x33, 0x00, 0x33, R.string.color_purple),
    BLUE(  0x00, 0x00, 0x80, R.string.color_blue),
    YELLOW(0xFF, 0xC9, 0xDD, R.string.color_yellow),
    GREEN( 0x9A, 0xFF, 0x00, R.string.color_green);

    fun toBytes() = byteArrayOf(r.toByte(), g.toByte(), b.toByte())

    companion object {
        fun closest(r: Int, g: Int, b: Int): UserColor =
            entries.minBy {
                kotlin.math.abs(it.r - r) +
                        kotlin.math.abs(it.g - g) +
                        kotlin.math.abs(it.b - b)
            }
    }
}

/** Read-only printer identity. Null when disconnected. */
data class PrinterIdentity(
    val softwareVersion: String? = null,
    val serialNumber: String? = null,
    val bluetoothMacAddress: String? = null,
    val hardwareVersion: String? = null,
    val customName: String? = null,
    val immutableName: String? = null,
    val firstPrintDate: Date? = null,
    val deviceSuperModel: Int? = null,
    val deviceSubModel: Int? = null,
)

/**
 * Printer configuration — used for both reading and writing.
 *
 * All fields are nullable:
 * - When read from the printer all fields are populated.
 * - When writing, only non-null fields are sent to the printer.
 *   Use [copy] to change individual settings without affecting others:
 *   `config.copy(sleepTimer = SleepTimer.MIN_5)`
 *
 * [hostsThreshold] is read-only and never written back.
 */
data class PrinterConfig(
    val sleepTimer: SleepTimer? = null,
    val autoOff: AutoOff? = null,
    val userColor: UserColor? = null,
    val pausePrinting: Boolean? = null,
    val hostsThreshold: Int? = null,
)

// ---------------------------------------------------------------------------
// SprocketRfcomm
// ---------------------------------------------------------------------------

@SuppressLint("MissingPermission")
class SprocketRfcomm(private val scope: CoroutineScope) {

    private val TAG = "SprocketRfcomm"

    // Each FILE_WRITE_REQ carries 998 bytes of image data plus 1 cmd byte and
    // 1 file-handle byte = 1000 bytes total payload, which fits in a large frame.
    private val WRITE_CHUNK_SIZE = 998

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    // Ensures only one coroutine accesses the socket at a time.
    // Without this, concurrent calls corrupt the request/response ordering.
    private val socketMutex = Mutex()

    private val _identity = MutableStateFlow<PrinterIdentity?>(null)
    val identity: StateFlow<PrinterIdentity?> = _identity

    private val _config = MutableStateFlow<PrinterConfig?>(null)
    val config: StateFlow<PrinterConfig?> = _config

    val isConnected: Boolean
        get() = socket?.isConnected == true

    // ---------------------------------------------------------------------------
    // Connection lifecycle
    // ---------------------------------------------------------------------------

    /**
     * Opens an RFCOMM socket to [device], runs the session setup sequence,
     * then loads identity and config into their respective StateFlows.
     *
     * Classic BT pairing is initiated automatically if not already bonded.
     * The printer uses Just Works pairing so no PIN dialog appears.
     */
    suspend fun connect(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext
        LogManager.d(TAG, "Connecting to ${device.address}")
        ensureBonded(device)
        openSocket(device)
        setupSession()
        try {
            _identity.value = fetchIdentity()
            LogManager.d(TAG, "Identity: fw=${_identity.value?.softwareVersion} " +
                    "sn=${_identity.value?.serialNumber}")
        } catch (e: Exception) {
            LogManager.w(TAG, "Identity load failed: ${e.message}")
        }
        try {
            _config.value = fetchConfig()
            LogManager.d(TAG, "Config: sleep=${_config.value?.sleepTimer} " +
                    "autoOff=${_config.value?.autoOff} color=${_config.value?.userColor}")
        } catch (e: Exception) {
            LogManager.w(TAG, "Config load failed: ${e.message}")
        }
    }

    fun disconnect() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null; input = null; output = null
        _identity.value = null
        _config.value = null
        LogManager.d(TAG, "Disconnected")
    }

    private fun ensureBonded(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        LogManager.d(TAG, "Initiating Classic BT pairing")
        device.createBond()
        val deadline = System.currentTimeMillis() + 15_000
        while (device.bondState != BluetoothDevice.BOND_BONDED) {
            check(System.currentTimeMillis() < deadline) {
                "Bonding timed out (state=${device.bondState})"
            }
            Thread.sleep(500)
        }
        LogManager.d(TAG, "Bonded")
    }

    private fun openSocket(device: BluetoothDevice) {
        var lastException: IOException? = null
        repeat(3) { attempt ->
            try {
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                socket = s; input = s.inputStream; output = s.outputStream
                LogManager.d(TAG, "Socket connected (attempt ${attempt + 1})")
                return
            } catch (e: IOException) {
                lastException = e
                LogManager.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}")
                Thread.sleep(500)
            }
        }
        throw lastException ?: IOException("RFCOMM connect failed")
    }

    // ---------------------------------------------------------------------------
    // Session setup
    //
    // The printer requires this sequence before accepting write commands.
    // Some steps may return ERROR when the BLE session is already active —
    // the printer shares session state across both transports. Errors here
    // are non-fatal; the identity/config reads that follow will confirm
    // whether the session is usable.
    // ---------------------------------------------------------------------------
    private fun setupSession() {
        // Step 0: Identify this transport as Bluetooth Classic (0x02).
        // Must be the first command on this channel.
        val ifCfgRsp = sendAndReceive(byteArrayOf(Cmd.IF_CONFIG_REQ, 0x02), "IF_CONFIG_REQ")
        LogManager.d(TAG, "IF_CONFIG_RSP: ${ifCfgRsp.toHex()}")

        // Step 1: Announce our maximum receive size.
        val connRsp = sendAndReceive(command(Cmd.CONN_SETUP_REQ) { putShortLE(4096) }, "CONN_SETUP_REQ")
        if (connRsp.firstOrNull() == Cmd.ERROR) {
            LogManager.w(TAG, "CONN_SETUP returned ERROR 0x%02X — BLE session already active".format(
                connRsp.getOrElse(2) { 0 }.toInt() and 0xFF))
        } else {
            LogManager.d(TAG, "CONN_SETUP_RSP: ${connRsp.toHex()}")
        }

        // Step 2: Read device ID and feature version.
        sendAndReceive(
            byteArrayOf(Cmd.RD_SYS_ATT_REQ, AttrTag.DEVICE_ID, AttrTag.FEATURE_SET_VERSION),
            "RD_SYS_ATT (deviceId)")

        // Step 3: Sync current time (unix timestamp + timezone offset in seconds).
        val now = (System.currentTimeMillis() / 1000L).toInt()
        val tz  = java.util.TimeZone.getDefault().rawOffset / 1000
        val timeRsp = sendAndReceive(command(Cmd.SET_TIME_REQ) { putIntLE(now); putIntLE(tz) }, "SET_TIME_REQ")
        if (timeRsp.firstOrNull() == Cmd.ERROR) LogManager.w(TAG, "SET_TIME returned ERROR — continuing")

        // Step 4: Read current color, then allocate print resources with it.
        val cfgRsp = sendAndReceive(
            byteArrayOf(Cmd.RD_SYS_CFG_REQ, CfgTag.USER_COLOR), "RD_SYS_CFG (color)")
        val currentColor = if (cfgRsp.size >= 5 && cfgRsp[0] == Cmd.RD_SYS_CFG_RSP)
            byteArrayOf(cfgRsp[2], cfgRsp[3], cfgRsp[4])
        else UserColor.GREEN.toBytes()

        val allocRsp = sendAndReceive(
            byteArrayOf(Cmd.RES_ALLOC_REQ, 0x01) + currentColor, "RES_ALLOC_REQ")
        if (allocRsp.firstOrNull() == Cmd.ERROR) LogManager.w(TAG, "RES_ALLOC returned ERROR — continuing")

        // Step 5: Status check.
        sendAndReceive(
            byteArrayOf(Cmd.RD_STATUS_REQ, 0x01, 0x02, 0x03, 0x05, 0x06, 0x07, 0x09),
            "RD_STATUS_REQ")

        LogManager.d(TAG, "Session setup complete")
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    suspend fun readIdentity(): PrinterIdentity = withContext(Dispatchers.IO) {
        socketMutex.withLock { fetchIdentity() }
    }

    suspend fun readConfig(): PrinterConfig = withContext(Dispatchers.IO) {
        socketMutex.withLock { fetchConfig() }
    }

    /**
     * Writes all non-null fields from [config] to the printer, then reads
     * back the confirmed state. Use [PrinterConfig.copy] to update only
     * the fields that changed:
     *
     *     printer.control.writeConfig(currentConfig.copy(sleepTimer = SleepTimer.MIN_5))
     */
    suspend fun writeConfig(config: PrinterConfig): PrinterConfig = withContext(Dispatchers.IO) {
        socketMutex.withLock {
            val rsp = sendAndReceive(buildWriteConfigPayload(config), "WR_SYS_CFG_REQ")
            check(rsp.isNotEmpty() && rsp[0] == Cmd.WR_SYS_CFG_RSP) {
                "WR_SYS_CFG failed: cmd=0x%02X".format(rsp.firstOrNull() ?: 0)
            }
        }
        // Read back outside the lock — readConfig() acquires it internally.
        readConfig()
    }

    /**
     * Prints [jpeg] with the given [color].
     * [onProgress] receives values 0–100 as data is transferred.
     */
    suspend fun print(
        jpeg: ByteArray,
        color: UserColor = UserColor.GREEN,
        onProgress: (Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        socketMutex.withLock {
            val jobId  = startJob()
            val handle = startPrint(jobId, jpeg.size)
            writeJobProperties(jobId, color)
            transferFile(handle, jpeg, onProgress)
        }
    }

    // ---------------------------------------------------------------------------
    // Fetch helpers — called with socketMutex already held
    // ---------------------------------------------------------------------------

    private fun fetchIdentity(): PrinterIdentity {
        val rsp = sendAndReceive(
            byteArrayOf(
                Cmd.RD_SYS_ATT_REQ,
                AttrTag.SOFTWARE_VERSION, AttrTag.SERIAL_NUMBER,
                AttrTag.BLUETOOTH_MAC,    AttrTag.HARDWARE_VERSION,
                AttrTag.CUSTOM_NAME,      AttrTag.IMMUTABLE_NAME,
                AttrTag.FIRST_PRINT,     AttrTag.DEVICE_SUPER_MODEL,
                AttrTag.DEVICE_SUB_MODEL,
            ), "RD_SYS_ATT_REQ"
        )
        check(rsp.isNotEmpty() && rsp[0] == Cmd.RD_SYS_ATT_RSP) {
            "RD_SYS_ATT failed: cmd=0x%02X".format(rsp.firstOrNull() ?: 0)
        }
        return parseIdentity(rsp).also { _identity.value = it }
    }

    private fun fetchConfig(): PrinterConfig {
        val rsp = sendAndReceive(
            byteArrayOf(
                Cmd.RD_SYS_CFG_REQ,
                CfgTag.SLEEP_TIMER, CfgTag.OFF_TIMER, CfgTag.USER_COLOR,
                CfgTag.PAUSE_PRINTING, CfgTag.HOSTS_THRESHOLD,
            ), "RD_SYS_CFG_REQ"
        )
        check(rsp.isNotEmpty() && rsp[0] == Cmd.RD_SYS_CFG_RSP) {
            "RD_SYS_CFG failed: cmd=0x%02X".format(rsp.firstOrNull() ?: 0)
        }
        return parseConfig(rsp).also { _config.value = it }
    }

    // ---------------------------------------------------------------------------
    // Print pipeline — called with socketMutex already held
    // ---------------------------------------------------------------------------

    private fun startJob(): Short {
        val rsp = sendAndReceive(byteArrayOf(Cmd.LIST_JOBS_REQ), "LIST_JOBS_REQ")
        check(rsp.isNotEmpty() && rsp[0] == Cmd.LIST_JOBS_RSP) { "LIST_JOBS failed" }
        val jobId = if (rsp.size >= 3)
            ByteBuffer.wrap(rsp, 1, 2).order(ByteOrder.LITTLE_ENDIAN).short
        else 0.toShort()
        LogManager.d(TAG, "Job ID: 0x%04X".format(jobId.toInt() and 0xFFFF))
        // Read current job properties before starting.
        sendAndReceive(command(Cmd.RD_JOB_PROP_REQ) {
            putShortLE(jobId.toInt()); put(0x02); put(0x04)
        }, "RD_JOB_PROP_REQ")
        return jobId
    }

    private fun startPrint(jobId: Short, fileSize: Int): Byte {
        val rsp = sendAndReceive(command(Cmd.PRINT_START_REQ) {
            put(0x01)          // file type: JPEG
            putIntLE(fileSize)
        }, "PRINT_START_REQ")
        check(rsp.isNotEmpty() && rsp[0] == Cmd.PRINT_START_RSP) {
            "PRINT_START failed: cmd=0x%02X".format(rsp.firstOrNull() ?: 0)
        }
        val handle   = rsp[1]
        val rspJobId = if (rsp.size >= 4)
            ByteBuffer.wrap(rsp, 2, 2).order(ByteOrder.LITTLE_ENDIAN).short else jobId
        LogManager.d(TAG, "Print started: handle=0x%02X jobId=0x%04X".format(
            handle, rspJobId.toInt() and 0xFFFF))
        return handle
    }

    private fun writeJobProperties(jobId: Short, color: UserColor) {
        // Format: [jobId:2LE][tag=03][01][02][R][G][B][tag=04][timestamp:4LE]
        val timestamp = (System.currentTimeMillis() / 1000L).toInt()
        val rsp = sendAndReceive(command(Cmd.WR_JOB_PROP_REQ) {
            putShortLE(jobId.toInt())
            put(0x03); put(0x01); put(0x02)
            put(color.r.toByte()); put(color.g.toByte()); put(color.b.toByte())
            put(0x04); putIntLE(timestamp)
        }, "WR_JOB_PROP_REQ")
        if (rsp.firstOrNull() == Cmd.ERROR)
            LogManager.w(TAG, "WR_JOB_PROP returned ERROR — continuing")
    }

    private fun transferFile(handle: Byte, jpeg: ByteArray, onProgress: (Int) -> Unit) {
        val startMs = System.currentTimeMillis()
        val total   = (jpeg.size + WRITE_CHUNK_SIZE - 1) / WRITE_CHUNK_SIZE
        var offset  = 0
        var chunk   = 0

        LogManager.d(TAG, "Transfer start: ${jpeg.size} bytes, $total chunks")

        while (offset < jpeg.size) {
            val end = minOf(offset + WRITE_CHUNK_SIZE, jpeg.size)
            chunk++

            val req = ByteArray(2 + (end - offset)).also { buf ->
                buf[0] = Cmd.FILE_WRITE_REQ
                buf[1] = handle
                System.arraycopy(jpeg, offset, buf, 2, end - offset)
            }

            // silent=true: suppress per-chunk TX/RX logging to avoid
            // log I/O overhead reducing transfer throughput.
            val rsp = sendAndReceive(req, "FILE_WRITE #$chunk/$total", silent = true)
            check(rsp.isNotEmpty() && rsp[0] == Cmd.FILE_WRITE_RSP) {
                "FILE_WRITE failed: cmd=0x%02X".format(rsp.firstOrNull() ?: 0)
            }

            val status  = rsp[2]
            val recvLen = if (rsp.size >= 7)  ByteBuffer.wrap(rsp, 3, 4).order(ByteOrder.LITTLE_ENDIAN).int else 0
            val totLen  = if (rsp.size >= 11) ByteBuffer.wrap(rsp, 7, 4).order(ByteOrder.LITTLE_ENDIAN).int else jpeg.size

            when (status) {
                WriteStatus.OK -> {
                    offset = recvLen
                    onProgress(if (totLen > 0) recvLen * 100 / totLen else 0)
                }
                WriteStatus.COMPLETE -> {
                    val ms = System.currentTimeMillis() - startMs
                    val seconds = ms / 1000.0
                    val kbPerSec = (jpeg.size / 1024.0) / maxOf(seconds, 0.001)

                    LogManager.d(TAG,
                        "Transfer complete: $recvLen bytes in %.2fs (%.2f kB/s) over $chunk chunks"
                            .format(seconds, kbPerSec)
                    )
                    onProgress(100)
                    return
                }
                WriteStatus.CANCELLED -> error("Print cancelled by printer")
                WriteStatus.FAILED    -> error("Print failed")
                else -> error("Unknown write status: 0x%02X".format(status))
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Frame layer
    // ---------------------------------------------------------------------------

    /**
     * Sends [payload] as an encoded frame and blocks until the printer responds.
     * [payload] must start with the command byte.
     * Returns the response payload including the response command byte.
     */
    /**
     * [silent] suppresses per-packet logging — used during file transfer
     * to avoid log I/O overhead affecting throughput.
     */
    private fun sendAndReceive(payload: ByteArray, label: String, silent: Boolean = false): ByteArray {
        val out = output ?: error("Not connected")
        val inp = input  ?: error("Not connected")
        if (!silent) {
            LogManager.d(TAG, "TX [$label] cmd=0x%02X payload=${payload.toHex()}".format(
                payload.firstOrNull() ?: 0))
        }
        out.write(encodeFrame(payload))
        out.flush()
        return decodeFrame(inp, label, silent)
    }

    /**
     * Wraps [payload] in the protocol frame header.
     * Uses a 1-byte length field when payload fits in 255 bytes, 2-byte LE otherwise.
     */
    private fun encodeFrame(payload: ByteArray): ByteArray {
        val len = payload.size
        return if (len <= 255) {
            ByteArray(5 + len).also { f ->
                f[0] = FRAME_MAGIC[0]; f[1] = FRAME_MAGIC[1]; f[2] = FRAME_MAGIC[2]
                f[3] = FLAG_SMALL; f[4] = len.toByte()
                System.arraycopy(payload, 0, f, 5, len)
            }
        } else {
            ByteArray(6 + len).also { f ->
                f[0] = FRAME_MAGIC[0]; f[1] = FRAME_MAGIC[1]; f[2] = FRAME_MAGIC[2]
                f[3] = FLAG_LARGE
                f[4] = (len and 0xFF).toByte(); f[5] = ((len shr 8) and 0xFF).toByte()
                System.arraycopy(payload, 0, f, 6, len)
            }
        }
    }

    /**
     * Reads one response frame from [inp].
     *
     * The printer sends 0x48 as a 1-byte sentinel first, then the rest.
     * We read 1 byte, then drain available() bytes to collect the full frame.
     * Returns [cmd][payload] with the frame header stripped.
     */
    private fun decodeFrame(inp: InputStream, label: String, silent: Boolean = false): ByteArray {
        val first = inp.read()
        check(first != -1) { "Connection closed reading response for [$label]" }

        val available = inp.available()
        val buf = ByteArray(available + 1).also { b ->
            b[0] = first.toByte()
            if (available > 0) inp.read(b, 1, available)
        }

        if (buf.size < 5) {
            LogManager.w(TAG, "RX [$label] frame too short: ${buf.toHex()}")
            return buf
        }

        val payloadStart = if ((buf[3].toInt() and 0xFF) == 2) 6 else 5
        val result = if (payloadStart < buf.size) buf.copyOfRange(payloadStart, buf.size)
        else ByteArray(0)

        if (!silent) {
            LogManager.d(TAG, "RX [$label] cmd=0x%02X payload=${result.toHex()}".format(
                result.firstOrNull() ?: 0))
        }
        return result
    }

    // ---------------------------------------------------------------------------
    // Command builder DSL
    // ---------------------------------------------------------------------------

    /**
     * Builds a command payload starting with [cmd], with additional bytes
     * written via [block] into a Little-Endian ByteBuffer.
     *
     * Example: `command(Cmd.CONN_SETUP_REQ) { putShortLE(4096) }`
     */
    private fun command(cmd: Byte, block: ByteBuffer.() -> Unit = {}): ByteArray {
        val buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(cmd)
        buf.block()
        return buf.array().copyOfRange(0, buf.position())
    }

    private fun ByteBuffer.putShortLE(value: Int) { putShort(value.toShort()) }
    private fun ByteBuffer.putIntLE(value: Int)    { putInt(value) }
    private fun ByteBuffer.put(value: Int)          { put(value.toByte()) }

    private operator fun ByteArray.plus(other: ByteArray) =
        copyOf(size + other.size).also { System.arraycopy(other, 0, it, size, other.size) }

    // ---------------------------------------------------------------------------
    // Config payload builder
    // ---------------------------------------------------------------------------

    private fun buildWriteConfigPayload(config: PrinterConfig): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(Cmd.WR_SYS_CFG_REQ.toInt())
        config.sleepTimer?.let {
            out.write(CfgTag.SLEEP_TIMER.toInt()); out.writeShortLE(it.minutes)
        }
        config.autoOff?.let {
            out.write(CfgTag.OFF_TIMER.toInt()); out.writeShortLE(it.minutes)
        }
        config.userColor?.let {
            out.write(CfgTag.USER_COLOR.toInt())
            out.write(it.r); out.write(it.g); out.write(it.b)
        }
        config.pausePrinting?.let {
            out.write(CfgTag.PAUSE_PRINTING.toInt())
            out.write(if (it) 1 else 0)
        }
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeShortLE(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }

    // ---------------------------------------------------------------------------
    // Response parsers
    // ---------------------------------------------------------------------------

    private fun parseIdentity(msg: ByteArray): PrinterIdentity {
        val bb = ByteBuffer.wrap(msg, 1, msg.size - 1).order(ByteOrder.LITTLE_ENDIAN)
        var sw: String? = null; var sn: String? = null; var mac: String? = null
        var hw: String? = null; var cn: String? = null; var inn: String? = null
        var born: Date? = null; var sup: Int? = null; var sub: Int? = null

        while (bb.remaining() > 0) {
            val tag = bb.get()
            try {
                when (tag) {
                    AttrTag.SOFTWARE_VERSION    -> sw  = bb.readPrefixedString()
                    AttrTag.SERIAL_NUMBER       -> sn  = bb.readPrefixedString()
                    AttrTag.HARDWARE_VERSION    -> hw  = bb.readPrefixedString()
                    AttrTag.CUSTOM_NAME         -> cn  = bb.readPrefixedString()
                    AttrTag.IMMUTABLE_NAME      -> inn = bb.readPrefixedString()
                    AttrTag.DEVICE_ID           -> bb.readPrefixedString()   // read + discard
                    AttrTag.FEATURE_SET_VERSION -> bb.readPrefixedString()   // read + discard
                    AttrTag.DEVICE_SUPER_MODEL  -> sup = bb.short.toInt() and 0xFFFF
                    AttrTag.DEVICE_SUB_MODEL    -> sub = bb.short.toInt() and 0xFFFF
                    AttrTag.BLUETOOTH_MAC       -> {
                        // 6 bytes stored little-endian → reverse for display
                        mac = ByteArray(6).also { bb.get(it) }
                            .reversed().joinToString(":") { "%02X".format(it) }
                    }
                    AttrTag.FIRST_PRINT -> {
                        born = Date((bb.int.toLong() and 0xFFFFFFFFL) * 1000)
                    }
                    else -> { LogManager.w(TAG, "Unknown attr tag 0x%02X".format(tag)); break }
                }
            } catch (e: Exception) {
                LogManager.w(TAG, "Parse error at attr tag 0x%02X: ${e.message}".format(tag)); break
            }
        }
        return PrinterIdentity(sw, sn, mac, hw, cn, inn, born, sup, sub)
    }

    private fun parseConfig(msg: ByteArray): PrinterConfig {
        val bb = ByteBuffer.wrap(msg, 1, msg.size - 1).order(ByteOrder.LITTLE_ENDIAN)
        var sleepTimer: SleepTimer? = null
        var autoOff: AutoOff? = null
        var userColor: UserColor? = null
        var pausePrinting: Boolean? = null
        var hostsThreshold: Int? = null

        while (bb.remaining() > 0) {
            val tag = bb.get()
            try {
                when (tag) {
                    CfgTag.SLEEP_TIMER     -> sleepTimer = SleepTimer.fromMinutes(bb.short.toInt() and 0xFFFF)
                    CfgTag.OFF_TIMER       -> autoOff    = AutoOff.fromMinutes(bb.short.toInt() and 0xFFFF)
                    CfgTag.USER_COLOR      -> userColor  = UserColor.closest(
                        bb.get().toInt() and 0xFF,
                        bb.get().toInt() and 0xFF,
                        bb.get().toInt() and 0xFF,
                    )
                    CfgTag.PAUSE_PRINTING  -> pausePrinting  = bb.get().toInt() != 0
                    CfgTag.HOSTS_THRESHOLD -> hostsThreshold = bb.get().toInt() and 0xFF
                    else -> { LogManager.w(TAG, "Unknown cfg tag 0x%02X".format(tag)); break }
                }
            } catch (e: Exception) {
                LogManager.w(TAG, "Parse error at cfg tag 0x%02X: ${e.message}".format(tag)); break
            }
        }
        return PrinterConfig(sleepTimer, autoOff, userColor, pausePrinting, hostsThreshold)
    }

    /**
     * Reads a length-prefixed UTF-8 string.
     * Format: [1-byte unsigned length][UTF-8 bytes]
     */
    private fun ByteBuffer.readPrefixedString(): String {
        val len = get().toInt() and 0xFF
        if (len <= 0 || len > remaining()) return ""
        return String(ByteArray(len).also { get(it) }, Charsets.UTF_8).trimEnd('\u0000')
    }

    internal fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }
}