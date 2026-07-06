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
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanResult
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import com.welie.blessed.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import androidx.core.graphics.scale
import com.photo.openzinkbooth.core.utils.LogManager

// ---------------------------------------------------------------------------
// GATT UUIDs – verified via BLE traffic analysis
// ---------------------------------------------------------------------------
private val SPROCKET_SERVICE_UUID = UUID.fromString("6822d239-7b61-4718-bdc1-189221946209")
private val CHAR_WRITE_UUID       = UUID.fromString("6822d239-7b61-4718-bdc1-de55b3f9051e")  // tx
private val CHAR_NOTIFY_UUID      = UUID.fromString("6822d239-7b61-4718-bdc1-772fa9983658")  // rx
// Pairing control characteristic (...3dd5acdd2eee) is only used for initial pairing
// and is not needed once the printer is bonded with the phone.

// ---------------------------------------------------------------------------
// HPLPP command codes – complete table verified via BLE traffic analysis
// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// FILE_WRITE_RSP status codes
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// RD_STATUS_REQ field tags
// ---------------------------------------------------------------------------
private object StatusField {
    const val SYSTEM_FLAGS              = 0x01.toByte()  // 4 bytes int
    const val PRINT_STATUS              = 0x02.toByte()  // 1 byte
    const val BATTERY_LEVEL             = 0x03.toByte()  // 1 byte
    const val PRINT_PROGRESS            = 0x04.toByte()  // 1 byte
    const val CURRENT_JOB               = 0x05.toByte()  // 2 bytes short
    const val BATTERY_STATUS            = 0x06.toByte()  // 1 byte
    const val QUEUE_STATUS              = 0x07.toByte()  // 1 byte
    const val CURRENT_JOB_COPY_PROGRESS = 0x08.toByte()  // 1 byte
    const val NUMBER_OF_HOSTS           = 0x09.toByte()  // 1 byte (read as int)
}

// ---------------------------------------------------------------------------
// PRINT_STATUS codes – verified via BLE protocol observation
// ---------------------------------------------------------------------------
enum class PrintStatus(val code: Int, val readyToPrint: Boolean) {
    UNKNOWN(-1, false),
    IDLE(1, true),
    PREPARING(2, true),
    OUT_OF_PAPER(3, false),
    PAPER_JAM(4, false),
    CALIBRATING(5, true),
    TRAY_OPEN(6, false),
    PRINTING(7, true),
    OVERHEATING(8, false),
    FEED_PATH_OBSTRUCTED(9, false),
    OUT_OF_SUPPLIES(10, false),
    NO_SUPPLIES_DETECTED(11, false),
    NO_TRAY(12, false),
    TRAY_MISALIGNED(13, false),
    UNRECOVERABLE_ERROR(14, false),
    BATTERY_CRITICAL(15, false),
    PAPER_PICK(16, false),
    MULTI_PAGE_PICK(17, false);

    companion object {
        fun from(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

// ---------------------------------------------------------------------------
// Sprocket model identification via BLE advertisement service data.
// ---------------------------------------------------------------------------
enum class SprocketModel(val superModel: Int, val width: Int, val height: Int, val jpegQuality: Int, val displayName: String) {
    IBIZA(       0x4962, 640, 1002, 90, "HP Sprocket 200"),
    HP200(       0x48D4, 640, 1002, 90, "HP Sprocket 200"),
    HP200D(      0x48D6, 640, 1002, 90, "HP Sprocket 200D"),
    HP400(       0x48D5, 793,  972, 90, "HP Sprocket 400"),
    GRAND_BAHAMA(0x48D3, 768, 1152, 90, "HP Sprocket Select"),
    LUZON(       0x4C75, 640, 1002, 70, "HP Sprocket Studio");

    companion object {
        fun fromSuperModel(superModel: Int): SprocketModel? =
            entries.firstOrNull { it.superModel == superModel }

        fun default() = IBIZA
    }
}

// ---------------------------------------------------------------------------
// Public UI state
// ---------------------------------------------------------------------------
sealed class SprocketState {
    object Disconnected : SprocketState()
    object Scanning : SprocketState()
    object Connecting : SprocketState()
    object NotFound : SprocketState()
    data class Ready(val model: SprocketModel) : SprocketState()
    data class Printing(val progressPercent: Int) : SprocketState()
    data class Error(val message: String) : SprocketState()
}

// ===========================================================================
// === V2 — App-faithful multi-frame transport ===
// ===========================================================================
// Key differences from V1 (single-frame fallback):
//
//   - BLE frame MTU is used as reported by the printer (no cap)
//     IF_CONFIG_RSP says 513 → we send up to 513-byte BLE frames.
//
//   - FILE_WRITE_REQ chunk size = targetMaxMessageSize - 2 (cmd + handle)
//     CONN_SETUP_RSP says 1000 → chunk size = 998 → ~50 chunks per photo
//     instead of 545 with the V1 cap of 200.
//
//   - Outgoing multi-frame messages are paced via the suspending
//     writeCharacteristic() — blessed-android-coroutines waits internally
//     on the GATT onCharacteristicWrite() callback before returning.
//     This is equivalent to BLETransportInterface.writeMessages() being
//     re-triggered from onCharacteristicWrite().
//
//   - Incoming multi-frame messages: when the printer sends a multi-frame
//     reply, we acknowledge every Nth frame with a [0x00] byte, where
//     N = upstreamAckPeriod from IF_CONFIG_RSP. All FILE_WRITE_RSP /
//     PRINT_START_RSP we've seen fit in one frame, but RD_STATUS_RSP with
//     many fields or FW updates may span multiple frames.
//
//   - Incoming [0x00] ACKs from the printer are observed for diagnostics
//     but don't drive flow control on our side — our suspend-based pacing
//     already limits concurrency to one in-flight frame.
//
// If anything goes wrong, switch back to the V1 single-frame backup file.
// ===========================================================================

private const val INITIAL_TARGET_MSG_SIZE = 128
private const val MAX_HOST_MESSAGE_SIZE = 4096
private const val REQUESTED_ATT_MTU = 460
private const val BLE_FRAME_MTU_CAP = 244

// Set to true to restore the original dual-channel behaviour (BLE + BT Classic
// RFCOMM). Left false because on some units/phones, opening the RFCOMM socket
// causes the phone's Bluetooth stack to drop the active BLE connection.
private const val BLE_CLASSIC_ENABLED = false

private const val NOTIFY_TIMEOUT_MS = 10_000L
private const val AUTO_SCAN_TIMEOUT_MS = 15_000L

// Verbose per-frame logging adds ~2-3ms per chunk on the main thread.
// Set to false for production / performance benchmarking.
private const val VERBOSE_FRAME_LOGGING = false

// ---------------------------------------------------------------------------
// Test bitmap
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// SprocketPrinter
// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// Protocol command codes — shared with SprocketRfcomm via internal visibility.
// ---------------------------------------------------------------------------
internal object Cmd {
    const val ERROR           = 0x01.toByte()
    const val RD_SYS_ATT_REQ  = 0x04.toByte()
    const val RD_SYS_ATT_RSP  = 0x05.toByte()
    const val RD_STATUS_REQ   = 0x08.toByte()
    const val RD_STATUS_RSP   = 0x09.toByte()
    const val IF_CONFIG_REQ   = 0x0A.toByte()
    const val IF_CONFIG_RSP   = 0x0B.toByte()
    const val PRINT_START_REQ = 0x0C.toByte()
    const val PRINT_START_RSP = 0x0D.toByte()
    const val FILE_WRITE_REQ  = 0x0E.toByte()
    const val FILE_WRITE_RSP  = 0x0F.toByte()
    const val SET_TIME_REQ    = 0x18.toByte()
    const val RD_SYS_CFG_REQ  = 0x1A.toByte()
    const val RD_SYS_CFG_RSP  = 0x1B.toByte()
    const val WR_SYS_CFG_REQ  = 0x1C.toByte()
    const val WR_SYS_CFG_RSP  = 0x1D.toByte()
    const val CONN_SETUP_REQ  = 0x24.toByte()
    const val CONN_SETUP_RSP  = 0x25.toByte()
    const val LIST_JOBS_REQ   = 0x2C.toByte()
    const val LIST_JOBS_RSP   = 0x2D.toByte()
    const val RD_JOB_PROP_REQ = 0x2E.toByte()
    const val WR_JOB_PROP_REQ = 0x30.toByte()
    const val WR_JOB_PROP_RSP = 0x31.toByte()
    const val RES_ALLOC_REQ   = 0x36.toByte()
    const val RD_FEATURE_REQ  = 0x3A.toByte()
}

@SuppressLint("MissingPermission")
class SprocketPrinter(private val context: Context) {
    private val TAG = "SprocketPrinter"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val centralManager = BluetoothCentralManager(context)

    // Tracks the last peripheral for auto-reconnect after unexpected disconnect.
    private var lastPeripheral: BluetoothPeripheral? = null
    private var connectionMonitorJob: Job? = null

    private val _state = MutableStateFlow<SprocketState>(SprocketState.Disconnected)
    val state: StateFlow<SprocketState> = _state

    private val _rxLog = MutableStateFlow<List<String>>(emptyList())
    val rxLog: StateFlow<List<String>> = _rxLog

    // Last bitmap sent to the printer – for debug preview in DEBUG builds only.
    private val _lastPrintBitmap = MutableStateFlow<Bitmap?>(null)
    val lastPrintBitmap: StateFlow<Bitmap?> = _lastPrintBitmap

    // Bluetooth Classic RFCOMM socket. Only used when BLE_CLASSIC_ENABLED = true
    // (see top of file); left disabled by default because on some units,
    // opening it drops the concurrent BLE GATT connection. Identity and config
    // are read over BLE instead — see refreshIdentity()/refreshConfig() below.
    val control = SprocketRfcomm(scope)
    private val _identity = MutableStateFlow<PrinterIdentity?>(null)
    val identity: StateFlow<PrinterIdentity?> = _identity
    private val _config = MutableStateFlow<PrinterConfig?>(null)
    val config: StateFlow<PrinterConfig?> = _config

    private var activePeripheral: BluetoothPeripheral? = null
    private var _detectedModel: SprocketModel = SprocketModel.default()
    val detectedModel: SprocketModel get() = _detectedModel

    private var writeChar: BluetoothGattCharacteristic? = null

    private var pendingNotify: CompletableDeferred<ByteArray>? = null

    // ---------- BLE frame layer state ----------
    // RX reassembly: accumulates fragments of an inbound HPLPP message until
    // we see the one with the last_flag bit set.
    private val rxBuffer = ByteArrayOutputStream()
    private var rxPreviousSeq = 0

    // Diagnostic counters.
    private var txFrameCounter = 0
    private var rxFrameCounter = 0
    private var rxAckCounter = 0
    private var txAckCounter = 0

    // ---------- HPLPP layer state ----------
    private var bleFrameMtu = 20
    private var targetMaxMessageSize = INITIAL_TARGET_MSG_SIZE
    private var upstreamAckPeriod = 0  // we ACK incoming frames every Nth

    val connectedAddress: String?
        get() = activePeripheral?.address

    // ---------------------------------------------------------------------------
    // Scan
    // ---------------------------------------------------------------------------

    fun autoScan() {
        if (_state.value is SprocketState.Scanning) return
        _state.value = SprocketState.Scanning

        val timeoutJob = scope.launch {
            delay(AUTO_SCAN_TIMEOUT_MS)
            if (_state.value is SprocketState.Scanning) {
                LogManager.d(TAG, "Auto-scan timed out — no printer found")
                centralManager.stopScan()
                _state.value = SprocketState.NotFound
            }
        }

        centralManager.scanForPeripherals(
            resultCallback = { peripheral, scanResult ->
                if (!isSprocketDevice(scanResult)) return@scanForPeripherals
                timeoutJob.cancel()
                LogManager.d(TAG, "Auto-scan found: ${scanResult.device.name}")
                applyDetectedModel(scanResult)
                centralManager.stopScan()
                connect(peripheral)
            },
            scanError = { failure ->
                timeoutJob.cancel()
                LogManager.e(TAG, "Auto-scan error: $failure")
                _state.value = SprocketState.Error("Scan failed: $failure")
            }
        )
    }

    fun startManualScan(onDeviceFound: (peripheral: BluetoothPeripheral, model: SprocketModel, name: String) -> Unit) {
        if (_state.value is SprocketState.Scanning) return
        _state.value = SprocketState.Scanning

        val seen = mutableSetOf<String>()

        centralManager.scanForPeripherals(
            resultCallback = { peripheral, scanResult ->
                if (!isSprocketDevice(scanResult)) return@scanForPeripherals
                val address = peripheral.address
                if (seen.add(address)) {
                    val model = run {
                        val advData = scanResult.scanRecord?.getServiceData(
                            android.os.ParcelUuid(SPROCKET_SERVICE_UUID)
                        )
                        advData?.let { parseModelFromAdvertisement(it) } ?: SprocketModel.default()
                    }
                    val deviceName = scanResult.device.name ?: model.displayName
                    LogManager.d(TAG, "Manual scan found: $deviceName ($address) model=${model.name}")
                    onDeviceFound(peripheral, model, deviceName)
                }
            },
            scanError = { failure ->
                LogManager.e(TAG, "Manual scan error: $failure")
                _state.value = SprocketState.Error("Scan failed: $failure")
            }
        )
    }

    fun connectToDevice(peripheral: BluetoothPeripheral, model: SprocketModel) {
        centralManager.stopScan()
        _detectedModel = model
        connect(peripheral)
    }

    fun stopScan() {
        centralManager.stopScan()
        if (_state.value is SprocketState.Scanning) _state.value = SprocketState.Disconnected
    }

    private fun isSprocketDevice(scanResult: ScanResult): Boolean {
        val name = scanResult.device.name ?: ""
        return name.contains("Sprocket", ignoreCase = true) ||
                name.startsWith("HPMalta-") ||
                name.startsWith("HPLuzon-")
    }

    private fun applyDetectedModel(scanResult: ScanResult) {
        val advData = scanResult.scanRecord?.getServiceData(
            android.os.ParcelUuid(SPROCKET_SERVICE_UUID)
        )
        val model = advData?.let { parseModelFromAdvertisement(it) }
        if (model != null) {
            _detectedModel = model
            LogManager.d(TAG, "Detected model: ${model.name}")
        } else {
            LogManager.d(TAG, "No service data — assuming ${_detectedModel.name}")
        }
    }

    private fun parseModelFromAdvertisement(data: ByteArray): SprocketModel? {
        if (data.size < 2) return null
        val superModel = ByteBuffer.wrap(data, 0, 2)
            .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        return SprocketModel.fromSuperModel(superModel)
    }

    // ---------------------------------------------------------------------------
    // Connect + full session setup
    //
    //   1. BLE connect, request larger ATT MTU
    //   2. Discover services, subscribe to notify char
    //   3. → IF_CONFIG_REQ   (sets BLE frame layer params)
    //      ← IF_CONFIG_RSP   (printer reports its frame mtu + ack period)
    //   4. → CONN_SETUP_REQ  (announces our max receive size)
    //      ← CONN_SETUP_RSP  (printer reports its max receive size + security level)
    //   At this point the session is ready; status reads + print are allowed.
    // ---------------------------------------------------------------------------
    // ---------------------------------------------------------------------------
    // connect() — public entry point (fire-and-forget via scope.launch).
    // Used by autoScan() and connectToDevice() which run in non-suspend context.
    // ---------------------------------------------------------------------------
    private fun connect(peripheral: BluetoothPeripheral) {
        lastPeripheral = peripheral
        scope.launch {
            try {
                connectSuspending(peripheral)
            } catch (e: Exception) {
                LogManager.e(TAG, "Connection error: ${e.message}")
                _state.value = SprocketState.Error(e.message ?: "Connection failed")
            }
        }
    }

    // ---------------------------------------------------------------------------
    // connectSuspending() — the real session setup logic.
    //
    // Unlike connect(), this is a true suspend function: it only returns once
    // the session is fully Ready (or throws on failure). This makes it safe
    // to call from handleUnexpectedDisconnect() where we need to know whether
    // an attempt actually succeeded before moving on to the next one.
    // ---------------------------------------------------------------------------
    private suspend fun connectSuspending(peripheral: BluetoothPeripheral) {
        _state.value = SprocketState.Connecting
        centralManager.connectPeripheral(peripheral)
        activePeripheral = peripheral

        try {
            val negotiatedMtu = peripheral.requestMtu(REQUESTED_ATT_MTU)
            LogManager.d(TAG, "ATT MTU negotiated: $negotiatedMtu")
        } catch (e: Exception) {
            LogManager.w(TAG, "requestMtu failed; continuing with default: ${e.message}")
        }

        val notifyChar = peripheral.getCharacteristic(SPROCKET_SERVICE_UUID, CHAR_NOTIFY_UUID)
            ?: throw Exception("Notify characteristic not found")

        peripheral.observe(notifyChar) { value ->
            onBleFrameReceived(value)
        }

        writeChar = peripheral.getCharacteristic(SPROCKET_SERVICE_UUID, CHAR_WRITE_UUID)
            ?: throw Exception("Write characteristic not found")

        LogManager.d(TAG, "Write char props=${writeChar!!.properties}")

        // --- Step 3: IF_CONFIG handshake ---
        val ifConfigRsp = writeAndAwaitNotify(
            peripheral, writeChar!!, buildIfConfigReq(), "IF_CONFIG_REQ"
        )
        checkNotError(ifConfigRsp, "IF_CONFIG")
        parseIfConfigRsp(ifConfigRsp)

        // --- Step 4: CONN_SETUP ---
        val connSetupRsp = writeAndAwaitNotify(
            peripheral, writeChar!!, buildConnSetupReq(), "CONN_SETUP_REQ"
        )
        checkNotError(connSetupRsp, "CONN_SETUP")
        parseConnSetupRsp(connSetupRsp)

        // Session is fully established — announce Ready.
        _state.value = SprocketState.Ready(_detectedModel)
        LogManager.d(TAG, "Session ready: model=${_detectedModel.name} " +
                "frameMtu=$bleFrameMtu msgSize=$targetMaxMessageSize ackPeriod=$upstreamAckPeriod")

        // Start the connection monitor now that we are in Ready state.
        startConnectionMonitor(peripheral)

        // --- Step 5: BT Classic RFCOMM is intentionally SKIPPED. ---
        // On this unit (firmware M2L1FS2229AR), attempting to open the RFCOMM
        // socket does not just fail to read (read ret: -1) — the act of
        // bonding/connecting Classic BT alongside an active BLE GATT link
        // causes the phone's Bluetooth stack to drop the LE connection
        // entirely a few seconds later ("Monitor: peripheral absent"),
        // triggering a disconnect/reconnect loop that never settles.
        // Printing runs exclusively over BLE GATT (see printOverBle()).
        // Set BLE_CLASSIC_ENABLED = true below to restore the old dual-channel
        // behaviour on printers/phones where BT Classic works fine.
        if (BLE_CLASSIC_ENABLED) {
            try {
                val btAdapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
                    ?.adapter
                    ?: throw Exception("BluetoothAdapter unavailable")
                val btDevice = btAdapter.getRemoteDevice(peripheral.address)
                control.connect(btDevice)
            } catch (e: Exception) {
                LogManager.w(TAG, "SprocketRfcomm connect failed: ${e.message} " +
                        "(settings + printing via BT Classic unavailable)")
            }
        }
    }

    fun disconnect() {
        // Cancel monitor first so it doesn't trigger reconnect on intentional disconnect.
        connectionMonitorJob?.cancel()
        connectionMonitorJob = null
        lastPeripheral = null

        activePeripheral?.let {
            scope.launch {
                try { centralManager.cancelConnection(it) } catch (e: Exception) { LogManager.e(TAG, "" + e.message) }
            }
        }
        activePeripheral = null
        writeChar = null
        rxBuffer.reset()
        rxPreviousSeq = 0
        txFrameCounter = 0
        rxFrameCounter = 0
        rxAckCounter = 0
        txAckCounter = 0
        control.disconnect()
        _state.value = SprocketState.Disconnected
    }

    // ---------------------------------------------------------------------------
    // Connection monitor + auto-reconnect
    // ---------------------------------------------------------------------------

    /**
     * Polls the peripheral connection state every 2 seconds.
     *
     * A single "not connected" poll is not enough to trigger reconnect —
     * blessed-android's getConnectedPeripherals() can briefly return an empty
     * list even when the connection is still alive (e.g. during an ATT
     * transaction). We therefore require TWO consecutive polls that both
     * report the peripheral as absent before treating it as a real disconnect.
     * This avoids false-positive reconnect storms when the printer is just busy.
     *
     * Cancelled immediately when the user calls [disconnect] so intentional
     * disconnects do not trigger reconnect attempts.
     */
    private fun startConnectionMonitor(peripheral: BluetoothPeripheral) {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = scope.launch {
            var missedPolls = 0  // consecutive polls that reported peripheral absent

            while (isActive) {
                delay(2_000)

                // Skip the check while a print is in progress — the RFCOMM
                // channel keeps the link busy and the BLE peripheral list can
                // transiently show the device as absent.
                if (_state.value is SprocketState.Printing) {
                    missedPolls = 0
                    continue
                }

                val connected = centralManager.getConnectedPeripherals()
                    .any { it.address == peripheral.address }

                if (!connected && _state.value !is SprocketState.Disconnected) {
                    missedPolls++
                    LogManager.w(TAG, "Monitor: peripheral absent (poll $missedPolls/2)")
                    if (missedPolls >= 2) {
                        LogManager.w(TAG, "Unexpected disconnect confirmed — attempting auto-reconnect")
                        handleUnexpectedDisconnect()
                        break
                    }
                } else {
                    // Reset counter as soon as the peripheral shows up again.
                    if (missedPolls > 0) {
                        LogManager.d(TAG, "Monitor: peripheral reappeared — resetting miss counter")
                    }
                    missedPolls = 0
                }
            }
        }
    }

    /**
     * Called by the connection monitor after a confirmed unexpected disconnect.
     *
     * Cleans up the current session and attempts to reconnect up to 3 times
     * using [connectSuspending] — a true suspend function that only returns
     * once the session is fully Ready or throws on failure. This guarantees
     * we actually wait for each attempt to complete before trying the next one,
     * unlike the previous fire-and-forget connect() approach.
     *
     * Backoff: 1s, 2s, 4s between attempts.
     */
    private suspend fun handleUnexpectedDisconnect() {
        val peripheral = lastPeripheral ?: run {
            LogManager.w(TAG, "No last peripheral — cannot reconnect")
            _state.value = SprocketState.Disconnected
            return
        }

        // Cancel the monitor so it does not re-fire while we are reconnecting.
        connectionMonitorJob?.cancel()

        // Clean up current session state without clearing lastPeripheral so
        // subsequent attempts can still use it.
        activePeripheral = null
        writeChar = null
        rxBuffer.reset()
        rxPreviousSeq = 0
        pendingNotify?.cancel()
        pendingNotify = null
        control.disconnect()

        // Attempt reconnect up to 3 times, waiting 1.5s between each attempt.
        // connectSuspending() is a real suspend function — it only returns
        // once Ready or throws, so we correctly wait for each attempt.
        for (attempt in 1..3) {
            LogManager.d(TAG, "Reconnect attempt $attempt/3")
            try {
                connectSuspending(peripheral)
                // connectSuspending() sets state to Ready internally and starts
                // a new monitor, so we simply return here on success.
                LogManager.d(TAG, "Reconnect successful on attempt $attempt")
                return
            } catch (e: Exception) {
                LogManager.w(TAG, "Reconnect attempt $attempt failed: ${e.message}")
                // Clean up any partial state before the next attempt.
                try { centralManager.cancelConnection(peripheral) } catch (_: Exception) {}
                activePeripheral = null
                writeChar = null
                rxBuffer.reset()
                rxPreviousSeq = 0
                if (attempt < 3) {
                    LogManager.d(TAG, "Waiting 1500ms before next attempt")
                    delay(1_500)
                }
            }
        }

        LogManager.e(TAG, "Auto-reconnect failed after 3 attempts")
        lastPeripheral = null
        _state.value = SprocketState.Error("Connection lost — reconnect failed")
    }

    // ---------------------------------------------------------------------------
    // BLE frame reassembly
    //
    // Frame layout (1-byte header per BLE packet):
    //   bit 7    = last_flag
    //   bits 0-6 = sequence number, starting at 1
    // Frame header == 0x00 means an ACK packet (no payload).
    //
    // For incoming multi-frame messages: every upstreamAckPeriod-th frame
    // (counted by sequence number) we send a [0x00] ACK back to the printer
    // to signal flow-control. This mirrors the App's BLETransportInterface
    // behaviour in onData().
    // ---------------------------------------------------------------------------
    private fun onBleFrameReceived(data: ByteArray) {
        if (data.isEmpty()) {
            LogManager.w(TAG, "RX EMPTY frame received — ignoring")
            return
        }
        val header = data[0].toInt() and 0xFF

        // ACK from printer: just a counter bump for diagnostics. blessed's
        // suspending writeCharacteristic already paces our outbound traffic.
        if (header == 0x00) {
            rxAckCounter++
            if (VERBOSE_FRAME_LOGGING) {
                LogManager.d(TAG, "RX ACK from printer (#$rxAckCounter)")
            }
            return
        }

        val seq = header and 0x7F
        val isLast = (header and 0x80) != 0
        rxFrameCounter++

        if (VERBOSE_FRAME_LOGGING) {
            LogManager.v(TAG, "RX frame: seq=$seq last=$isLast payloadLen=${data.size - 1}")
        }

        if (rxPreviousSeq + 1 != seq) {
            LogManager.w(TAG, "Out-of-order frame: expected seq=${rxPreviousSeq + 1}, got $seq — dropping accumulated buffer")
            rxBuffer.reset()
            rxPreviousSeq = 0
            return
        }

        rxBuffer.write(data, 1, data.size - 1)
        rxPreviousSeq = seq

        if (isLast) {
            val message = rxBuffer.toByteArray()
            rxBuffer.reset()
            rxPreviousSeq = 0

            val cmd = message.firstOrNull()?.toInt()?.and(0xFF) ?: 0
            // For settings responses, dump the full hex so we can verify
            // string-encoding assumptions in SprocketSettings.
            if (cmd == 0x05 || cmd == 0x1B) {
                LogManager.d(TAG, "HPLPP RX ← cmd=0x%02X len=${message.size} hex=${message.toHexString()}".format(cmd))
            } else {
                LogManager.d(TAG, "HPLPP RX ← cmd=0x%02X len=${message.size}".format(cmd))
            }

            val cmd2 = cmd  // cmd already computed above for logging
            if (cmd2 == 0x03) {
                // Printer pushes this unsolicited (print progress) at any time,
                // including while we're waiting on the response to an unrelated
                // request. Never let it steal a pending request's deferred —
                // that previously caused "Unexpected RD_STATUS_RSP: 03 ..."
                // when a progress push arrived mid-status-poll.
                LogManager.d(TAG, "Unsolicited push cmd=0x03 (print progress, ignored)")
            } else {
                val deferred = pendingNotify
                pendingNotify = null
                if (deferred != null) {
                    deferred.complete(message)
                } else if (cmd2 == 0x09) {
                    // Status push with no pending request — also informational.
                    LogManager.d(TAG, "Unsolicited push cmd=0x09 (status, ignored)")
                } else {
                    LogManager.w(TAG, "RX message arrived but no pending request — discarded")
                }
            }
        } else {
            // Mid-message: send an ACK every upstreamAckPeriod-th frame.
            // This is what BLETransportInterface.onData() does to keep
            // flow control on multi-frame replies from the printer.
            if (upstreamAckPeriod > 0 && (seq % upstreamAckPeriod) == 0) {
                sendAckFireAndForget()
            }
        }
    }

    /**
     * Send a [0x00] ACK byte back to the printer. Fire-and-forget — we
     * don't await the GATT write callback because this runs in the
     * notification thread and we want to return promptly to receive
     * the next frame.
     */
    private fun sendAckFireAndForget() {
        val p = activePeripheral ?: return
        val w = writeChar ?: return
        scope.launch {
            try {
                p.writeCharacteristic(w, byteArrayOf(0x00), WriteType.WITHOUT_RESPONSE)
                txAckCounter++
                if (VERBOSE_FRAME_LOGGING) {
                    LogManager.d(TAG, "TX ACK to printer (#$txAckCounter)")
                }
            } catch (e: Exception) {
                LogManager.w(TAG, "Failed to send ACK: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Debug probe
    // ---------------------------------------------------------------------------
    suspend fun sendProbe(): StatusSnapshot? {
        val p = activePeripheral ?: return null
        val w = writeChar ?: return null

        return try {
            val statusRsp = writeAndAwaitNotify(p, w, buildStatusReq(), "RD_STATUS_REQ")
            checkNotError(statusRsp, "RD_STATUS")
            val status = parseStatusResponse(statusRsp)
            LogManager.d(TAG, "Probe status: $status")
            status
        } catch (e: Exception) {
            LogManager.e(TAG, "Probe failed: ${e.message}")
            null
        }
    }

    // ---------------------------------------------------------------------------
    // Settings — Identity + Config read/write over BLE GATT.
    //
    // These mirror SprocketRfcomm's RD_SYS_ATT / RD_SYS_CFG / WR_SYS_CFG logic
    // exactly, but run over the BLE channel so they work even with
    // BLE_CLASSIC_ENABLED = false. Tag layouts per the HPLPP spec:
    //   RD_SYS_ATT_RSP: [0x05][tag][...]... (strings are [len:1][utf8]) 
    //   RD_SYS_CFG_RSP: [0x1B][tag][...]...
    // ---------------------------------------------------------------------------

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
        const val FIRST_PRINT         = 0x0E.toByte()
    }

    private object CfgTag {
        const val SLEEP_TIMER     = 0x01.toByte()
        const val OFF_TIMER       = 0x02.toByte()
        const val USER_COLOR      = 0x03.toByte()
        const val PAUSE_PRINTING  = 0x04.toByte()
        const val HOSTS_THRESHOLD = 0x05.toByte()
    }

    /** Re-read identity from the printer over BLE and update the StateFlow. */
    suspend fun refreshIdentity(): PrinterIdentity? {
        val p = activePeripheral ?: return null
        val w = writeChar ?: return null
        return try {
            val rsp = writeAndAwaitNotify(p, w, byteArrayOf(
                0x04,
                AttrTag.SOFTWARE_VERSION, AttrTag.SERIAL_NUMBER,
                AttrTag.BLUETOOTH_MAC, AttrTag.HARDWARE_VERSION,
                AttrTag.CUSTOM_NAME, AttrTag.IMMUTABLE_NAME,
                AttrTag.FIRST_PRINT, AttrTag.DEVICE_SUPER_MODEL,
                AttrTag.DEVICE_SUB_MODEL,
            ), "RD_SYS_ATT_REQ (BLE)")
            checkNotError(rsp, "RD_SYS_ATT")
            check(rsp.isNotEmpty() && rsp[0] == 0x05.toByte()) {
                "RD_SYS_ATT failed: cmd=0x%02X".format(rsp.firstOrNull() ?: 0)
            }
            parseIdentityBle(rsp).also { _identity.value = it }
        } catch (e: Exception) {
            LogManager.w(TAG, "refreshIdentity (BLE) failed: ${e.message}")
            null
        }
    }

    /** Re-read config from the printer over BLE and update the StateFlow. */
    suspend fun refreshConfig(): PrinterConfig? {
        val p = activePeripheral ?: return null
        val w = writeChar ?: return null
        return try {
            val rsp = writeAndAwaitNotify(p, w, byteArrayOf(
                0x1A,
                CfgTag.SLEEP_TIMER, CfgTag.OFF_TIMER, CfgTag.USER_COLOR,
                CfgTag.PAUSE_PRINTING, CfgTag.HOSTS_THRESHOLD,
            ), "RD_SYS_CFG_REQ (BLE)")
            checkNotError(rsp, "RD_SYS_CFG")
            check(rsp.isNotEmpty() && rsp[0] == 0x1B.toByte()) {
                "RD_SYS_CFG failed: cmd=0x%02X".format(rsp.firstOrNull() ?: 0)
            }
            parseConfigBle(rsp).also { _config.value = it }
        } catch (e: Exception) {
            LogManager.w(TAG, "refreshConfig (BLE) failed: ${e.message}")
            null
        }
    }

    /** Write a partial config update over BLE and read back the confirmed result. */
    suspend fun applyConfig(newConfig: PrinterConfig): PrinterConfig? {
        val p = activePeripheral ?: return null
        val w = writeChar ?: return null
        val out = ByteArrayOutputStream()
        out.write(0x1C)
        newConfig.sleepTimer?.let { out.write(CfgTag.SLEEP_TIMER.toInt()); out.write(it.minutes and 0xFF); out.write((it.minutes shr 8) and 0xFF) }
        newConfig.autoOff?.let { out.write(CfgTag.OFF_TIMER.toInt()); out.write(it.minutes and 0xFF); out.write((it.minutes shr 8) and 0xFF) }
        newConfig.userColor?.let { out.write(CfgTag.USER_COLOR.toInt()); out.write(it.r); out.write(it.g); out.write(it.b) }
        newConfig.pausePrinting?.let { out.write(CfgTag.PAUSE_PRINTING.toInt()); out.write(if (it) 1 else 0) }

        val rsp = writeAndAwaitNotify(p, w, out.toByteArray(), "WR_SYS_CFG_REQ (BLE)")
        checkNotError(rsp, "WR_SYS_CFG")
        check(rsp.isNotEmpty() && rsp[0] == 0x1D.toByte()) {
            "WR_SYS_CFG failed: cmd=0x%02X".format(rsp.firstOrNull() ?: 0)
        }
        return refreshConfig()
    }

    private fun parseIdentityBle(msg: ByteArray): PrinterIdentity {
        val bb = ByteBuffer.wrap(msg, 1, msg.size - 1).order(ByteOrder.LITTLE_ENDIAN)
        var sw: String? = null; var sn: String? = null; var mac: String? = null
        var hw: String? = null; var cn: String? = null; var inn: String? = null
        var born: java.util.Date? = null; var sup: Int? = null; var sub: Int? = null

        fun ByteBuffer.readPrefixedString(): String {
            val len = get().toInt() and 0xFF
            val bytes = ByteArray(len).also { get(it) }
            return String(bytes, Charsets.UTF_8)
        }

        while (bb.remaining() > 0) {
            val tag = bb.get()
            try {
                when (tag) {
                    AttrTag.SOFTWARE_VERSION    -> sw  = bb.readPrefixedString()
                    AttrTag.SERIAL_NUMBER       -> sn  = bb.readPrefixedString()
                    AttrTag.HARDWARE_VERSION    -> hw  = bb.readPrefixedString()
                    AttrTag.CUSTOM_NAME         -> cn  = bb.readPrefixedString()
                    AttrTag.IMMUTABLE_NAME      -> inn = bb.readPrefixedString()
                    AttrTag.DEVICE_ID           -> bb.readPrefixedString()
                    AttrTag.FEATURE_SET_VERSION -> bb.readPrefixedString()
                    AttrTag.DEVICE_SUPER_MODEL  -> sup = bb.short.toInt() and 0xFFFF
                    AttrTag.DEVICE_SUB_MODEL    -> sub = bb.short.toInt() and 0xFFFF
                    AttrTag.BLUETOOTH_MAC       -> {
                        mac = ByteArray(6).also { bb.get(it) }
                            .reversed().joinToString(":") { "%02X".format(it) }
                    }
                    AttrTag.FIRST_PRINT -> born = java.util.Date((bb.int.toLong() and 0xFFFFFFFFL) * 1000)
                    else -> { LogManager.w(TAG, "Unknown sys-att tag 0x%02X (BLE)".format(tag)); break }
                }
            } catch (e: Exception) {
                LogManager.w(TAG, "Parse error at sys-att tag 0x%02X (BLE): ${e.message}".format(tag)); break
            }
        }
        return PrinterIdentity(sw, sn, mac, hw, cn, inn, born, sup, sub)
    }

    private fun parseConfigBle(msg: ByteArray): PrinterConfig {
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
                        bb.get().toInt() and 0xFF, bb.get().toInt() and 0xFF, bb.get().toInt() and 0xFF)
                    CfgTag.PAUSE_PRINTING  -> pausePrinting  = bb.get().toInt() != 0
                    CfgTag.HOSTS_THRESHOLD -> hostsThreshold = bb.get().toInt() and 0xFF
                    else -> { LogManager.w(TAG, "Unknown cfg tag 0x%02X (BLE)".format(tag)); break }
                }
            } catch (e: Exception) {
                LogManager.w(TAG, "Parse error at cfg tag 0x%02X (BLE): ${e.message}".format(tag)); break
            }
        }
        return PrinterConfig(sleepTimer, autoOff, userColor, pausePrinting, hostsThreshold)
    }

    // ---------------------------------------------------------------------------
    // Print — delegates to SprocketRfcomm over Bluetooth Classic.
    //
    // The full print pipeline (PRINT_START / WR_JOB_PROP / FILE_WRITE loop) runs
    // over the RFCOMM channel (SprocketRfcomm).
    // The BLE GATT channel handles status polling.
    // ---------------------------------------------------------------------------
    suspend fun print(bitmap: Bitmap, color: UserColor = UserColor.GREEN) {
        _state.value = SprocketState.Printing(0)
        try {
            // Check printer status via BLE GATT before starting
            val probe = sendProbe()
            if (probe != null && !probe.printStatus.readyToPrint) {
                _state.value = SprocketState.Error("Printer not ready: ${probe.printStatus}")
                return
            }

            // Prepare JPEG using model-specific dimensions
            val jpeg = prepareImage(bitmap, _detectedModel)
            LogManager.d(TAG, "Image prepared: ${jpeg.size} bytes " +
                    "(${_detectedModel.width}x${_detectedModel.height} @ q${_detectedModel.jpegQuality})")

            if (BLE_CLASSIC_ENABLED && control.isConnected) {
                // Preferred path when enabled: RFCOMM (faster, larger chunks)
                control.print(jpeg, color) { progress ->
                    _state.value = SprocketState.Printing(progress)
                }
            } else {
                // BLE GATT print pipeline. Some units (e.g. firmware
                // M2L1FS2229AR) have an unusable/interfering BT Classic side,
                // so this is the default path — see BLE_CLASSIC_ENABLED above.
                LogManager.d(TAG, "Printing over BLE GATT")
                printOverBle(jpeg, color) { progress ->
                    _state.value = SprocketState.Printing(progress)
                }
            }

            LogManager.d(TAG, "Print complete!")
            _state.value = SprocketState.Ready(_detectedModel)

        } catch (e: Exception) {
            LogManager.e(TAG, "Print error: ${e.message}", e)
            _state.value = SprocketState.Error(e.message ?: "Unknown error")
        }
    }

    // ---------------------------------------------------------------------------
    // BLE print fallback — full HPLPP print pipeline over BLE GATT.
    //
    // Same command sequence as SprocketRfcomm.print(), but every FILE_WRITE
    // message is sized to fit a single BLE frame ([header][cmd][handle][data]),
    // so no multi-frame flow control is involved. The next chunk offset always
    // comes from the printer-confirmed bytesReceived field.
    // Verified end-to-end against a Sprocket 200 running firmware M2L1FS2229AR.
    // ---------------------------------------------------------------------------
    private suspend fun printOverBle(
        jpeg: ByteArray,
        color: UserColor,
        onProgress: (Int) -> Unit
    ) {
        val p = activePeripheral ?: error("BLE peripheral not connected")
        val w = writeChar ?: error("BLE write characteristic missing")

        // 1) LIST_JOBS → current job id
        val jobsRsp = writeAndAwaitNotify(p, w, byteArrayOf(0x2C), "LIST_JOBS_REQ (BLE)")
        checkNotError(jobsRsp, "LIST_JOBS")
        var jobId = if (jobsRsp.size >= 3)
            ((jobsRsp[2].toInt() and 0xFF) shl 8) or (jobsRsp[1].toInt() and 0xFF) else 0

        // 2) RD_JOB_PROP — mirrors the captured session; failure is non-fatal
        runCatching {
            writeAndAwaitNotify(p, w, byteArrayOf(
                0x2E, (jobId and 0xFF).toByte(), ((jobId shr 8) and 0xFF).toByte(),
                0x02, 0x04), "RD_JOB_PROP_REQ (BLE)")
        }

        // 3) PRINT_START: [0x0C][fileType=1 JPEG][fileSize:4LE]
        val start = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        start.put(0x0C.toByte()); start.put(0x01.toByte()); start.putInt(jpeg.size)
        val startRsp = writeAndAwaitNotify(p, w, start.array(), "PRINT_START_REQ (BLE)")
        checkNotError(startRsp, "PRINT_START")
        check(startRsp.size >= 2 && startRsp[0] == 0x0D.toByte()) {
            "Unexpected PRINT_START_RSP: ${startRsp.joinToString(" ") { "%02x".format(it) }}"
        }
        val handle = startRsp[1]
        if (startRsp.size >= 4)
            jobId = ((startRsp[3].toInt() and 0xFF) shl 8) or (startRsp[2].toInt() and 0xFF)
        LogManager.d(TAG, "PRINT_START (BLE): handle=$handle jobId=$jobId")

        // 4) WR_JOB_PROP: LED color + timestamp — failure is non-fatal
        val ts = (System.currentTimeMillis() / 1000L).toInt()
        val props = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        props.put(0x30.toByte())
        props.putShort(jobId.toShort())
        props.put(0x03.toByte()); props.put(0x01.toByte()); props.put(0x02.toByte())
        props.put(color.toBytes())
        props.put(0x04.toByte()); props.putInt(ts)
        runCatching {
            writeAndAwaitNotify(p, w,
                props.array().copyOfRange(0, props.position()), "WR_JOB_PROP_REQ (BLE)")
        }

        // 5) FILE_WRITE loop — data per chunk keeps each message single-frame
        val chunkSize = (bleFrameMtu - 3).coerceAtLeast(16)
        var offset = 0
        while (true) {
            val end = minOf(offset + chunkSize, jpeg.size)
            val msg = ByteArray(2 + (end - offset))
            msg[0] = 0x0E; msg[1] = handle
            System.arraycopy(jpeg, offset, msg, 2, end - offset)

            val rsp = writeAndAwaitNotify(p, w, msg, "FILE_WRITE (BLE)", timeoutMs = 30_000)
            checkNotError(rsp, "FILE_WRITE")
            check(rsp.size >= 3 && rsp[0] == 0x0F.toByte()) {
                "Unexpected FILE_WRITE_RSP: ${rsp.joinToString(" ") { "%02x".format(it) }}"
            }
            val status = rsp[2].toInt() and 0xFF
            val received = if (rsp.size >= 7)
                ((rsp[6].toInt() and 0xFF) shl 24) or ((rsp[5].toInt() and 0xFF) shl 16) or
                ((rsp[4].toInt() and 0xFF) shl 8) or (rsp[3].toInt() and 0xFF)
            else end
            onProgress((received * 100 / jpeg.size).coerceIn(0, 100))

            when (status) {
                0x01 -> offset = received   // OK — continue from printer-confirmed offset
                0x02 -> {                   // COMPLETE — printer starts printing
                    LogManager.d(TAG, "BLE transfer complete ($received bytes) — printing")
                    return
                }
                else -> error("FILE_WRITE failed over BLE: status=$status")
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Core: write a command and suspend until printer sends a notify response
    //
    //   1. Split HPLPP message into BLE frames (1 byte header + payload)
    //   2. Send each frame sequentially. blessed-android-coroutines'
    //      writeCharacteristic() is a suspend function that returns only
    //      after the GATT onCharacteristicWrite() callback fires — this
    //      is the same pacing the App relies on, just expressed in
    //      coroutines instead of callbacks.
    //   3. Wait for the printer's reply via the rxBuffer reassembly path.
    // ---------------------------------------------------------------------------

    private suspend fun writeAndAwaitNotify(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothGattCharacteristic,
        hplppMessage: ByteArray,
        label: String,
        timeoutMs: Long = NOTIFY_TIMEOUT_MS
    ): ByteArray {
        if (pendingNotify != null) {
            LogManager.w(TAG, "writeAndAwaitNotify: previous request still pending! Dropping it.")
        }
        val deferred = CompletableDeferred<ByteArray>()
        pendingNotify = deferred

        val frames = buildFrames(hplppMessage)
        if (VERBOSE_FRAME_LOGGING) {
            LogManager.d(TAG, "TX [$label] msgLen=${hplppMessage.size} → ${frames.size} BLE frame(s)")
        }

        for ((idx, frame) in frames.withIndex()) {
            txFrameCounter++
            try {
                // Suspending call: returns only after onCharacteristicWrite()
                // fires inside blessed. Equivalent to the App's
                // BLETransportInterface waiting for the GATT-write callback
                // before issuing writeMessages() for the next frame.
                peripheral.writeCharacteristic(characteristic, frame, WriteType.WITHOUT_RESPONSE)
            } catch (e: Exception) {
                LogManager.e(TAG, "writeCharacteristic FAILED on frame ${idx + 1}/${frames.size} of $label: ${e.message}", e)
                pendingNotify = null
                throw e
            }
        }

        // Always clear pendingNotify regardless of how the await ends so a
        // stale deferred can never block the next command on any code path.
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            LogManager.e(TAG, "TIMEOUT on $label " +
                    "(txFrames=$txFrameCounter rxFrames=$rxFrameCounter " +
                    "rxAcks=$rxAckCounter rxBufferLen=${rxBuffer.size()})")
            throw Exception("Timeout awaiting response for $label")
        } finally {
            // Runs on every exit path: normal return, timeout, and cancellation.
            if (pendingNotify === deferred) pendingNotify = null
        }
    }

    private fun checkNotError(notify: ByteArray, context: String) {
        if (notify.isNotEmpty() && notify[0] == Cmd.ERROR) {
            val payload = if (notify.size > 1)
                notify.copyOfRange(1, notify.size).toHexString()
            else "(empty)"
            val msg = "Printer returned ERROR for $context: payload=$payload"
            LogManager.e(TAG, msg)
            throw Exception(msg)
        }
    }

    // ---------------------------------------------------------------------------
    // BLE frame splitter
    //
    // Splits an HPLPP message into BLE packets of bleFrameMtu bytes each.
    // Frame header byte: [last_flag:1][sequence:7], sequence starts at 1.
    // Sequence resets per HPLPP message. Uses the full negotiated frame MTU
    // (typically 513 for Sprocket 200) without artificial caps.
    // ---------------------------------------------------------------------------
    private fun buildFrames(hplppMessage: ByteArray): List<ByteArray> {
        val payloadPerFrame = bleFrameMtu - 1
        require(payloadPerFrame > 0) { "bleFrameMtu too small: $bleFrameMtu" }

        val frames = mutableListOf<ByteArray>()
        var pos = 0
        var seq = 1
        while (pos < hplppMessage.size) {
            val end = minOf(pos + payloadPerFrame, hplppMessage.size)
            val isLast = (end == hplppMessage.size)
            val header = (if (isLast) 0x80 or seq else seq).toByte()
            val frame = ByteArray(end - pos + 1)
            frame[0] = header
            System.arraycopy(hplppMessage, pos, frame, 1, end - pos)
            frames.add(frame)
            pos = end
            seq = (seq + 1) and 0x7F
            if (seq == 0) seq = 1  // 0 reserved for ACK
        }
        return frames
    }

    // ---------------------------------------------------------------------------
    // HPLPP message builders. All multi-byte integers are Little-Endian.
    // ---------------------------------------------------------------------------

    private fun buildIfConfigReq() = byteArrayOf(Cmd.IF_CONFIG_REQ, 0x01, 0x00)

    private fun buildConnSetupReq(): ByteArray {
        val bb = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(Cmd.CONN_SETUP_REQ)
        bb.putShort(MAX_HOST_MESSAGE_SIZE.toShort())
        return bb.array()
    }

    private fun buildStatusReq() = byteArrayOf(
        Cmd.RD_STATUS_REQ,
        StatusField.SYSTEM_FLAGS,
        StatusField.PRINT_STATUS,
        StatusField.BATTERY_LEVEL,
        StatusField.BATTERY_STATUS,
        StatusField.NUMBER_OF_HOSTS
    )

    // ---------------------------------------------------------------------------
    // Response parsers
    // ---------------------------------------------------------------------------

    /**
     * IF_CONFIG_RSP: [0x0B][mtu:2 LE][upstreamAckPeriod:1]
     *
     * - mtu is the BLE-frame size the printer wants us to use for outgoing
     *   frames. We use the value as-is (no cap).
     * - upstreamAckPeriod is how often we should ACK incoming multi-frame
     *   messages from the printer (every Nth received frame).
     */
    private fun parseIfConfigRsp(message: ByteArray) {
        if (message.size < 4 || message[0] != Cmd.IF_CONFIG_RSP) {
            LogManager.w(TAG, "Unexpected IF_CONFIG_RSP: ${message.toHexString()}")
            return
        }
        val mtu = ByteBuffer.wrap(message, 1, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        val ackPeriod = message[3].toInt() and 0xFF

        // Apply BLE_FRAME_MTU_CAP. Printer typically reports 513 but our
        // BLE stack (Samsung S921B / Android 16) silently drops packets at
        // that size — see the cap's docstring for context.
        if (mtu > 1) bleFrameMtu = minOf(mtu, BLE_FRAME_MTU_CAP)
        upstreamAckPeriod = ackPeriod

        if (mtu > BLE_FRAME_MTU_CAP) {
            LogManager.d(TAG, "IF_CONFIG_RSP: bleFrameMtu=$bleFrameMtu (printer offered $mtu, capped) upstreamAckPeriod=$ackPeriod")
        } else {
            LogManager.d(TAG, "IF_CONFIG_RSP: bleFrameMtu=$bleFrameMtu upstreamAckPeriod=$ackPeriod")
        }
    }

    /**
     * CONN_SETUP_RSP layout:
     *   [0x25][maximumTargetMessageSize:2 LE][currentSecurityLevel:1]
     */
    private fun parseConnSetupRsp(message: ByteArray) {
        if (message.size < 4 || message[0] != Cmd.CONN_SETUP_RSP) {
            LogManager.w(TAG, "Unexpected CONN_SETUP_RSP: ${message.toHexString()}")
            return
        }
        val maxMsg = ByteBuffer.wrap(message, 1, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        val securityLevel = message[3].toInt() and 0xFF

        if (maxMsg > 2) targetMaxMessageSize = maxMsg

        LogManager.d(TAG, "CONN_SETUP_RSP: targetMaxMsg=$targetMaxMessageSize securityLevel=0x%02X".format(securityLevel))
    }

    data class StatusSnapshot(
        val systemFlags: Int? = null,
        val printStatus: PrintStatus = PrintStatus.UNKNOWN,
        val batteryLevel: Int? = null,
        val batteryStatus: Int? = null,
        val numberOfHosts: Int? = null,
        val printProgress: Int? = null,
        val currentJob: Int? = null,
        val queueStatus: Int? = null
    )

    private fun parseStatusResponse(message: ByteArray): StatusSnapshot {
        if (message.isEmpty() || message[0] != Cmd.RD_STATUS_RSP) {
            LogManager.w(TAG, "Unexpected RD_STATUS_RSP: ${message.toHexString()}")
            return StatusSnapshot()
        }
        val bb = ByteBuffer.wrap(message, 1, message.size - 1).order(ByteOrder.LITTLE_ENDIAN)

        var systemFlags: Int? = null
        var printStatus = PrintStatus.UNKNOWN
        var batteryLevel: Int? = null
        var batteryStatus: Int? = null
        var numberOfHosts: Int? = null
        var printProgress: Int? = null
        var currentJob: Int? = null
        var queueStatus: Int? = null

        while (bb.remaining() > 0) {
            val tag = bb.get()
            when (tag) {
                StatusField.SYSTEM_FLAGS              -> systemFlags = bb.int
                StatusField.PRINT_STATUS              -> printStatus = PrintStatus.from(bb.get().toInt() and 0xFF)
                StatusField.BATTERY_LEVEL             -> batteryLevel = bb.get().toInt() and 0xFF
                StatusField.PRINT_PROGRESS            -> printProgress = bb.get().toInt() and 0xFF
                StatusField.CURRENT_JOB               -> currentJob = bb.short.toInt() and 0xFFFF
                StatusField.BATTERY_STATUS            -> batteryStatus = bb.get().toInt() and 0xFF
                StatusField.QUEUE_STATUS              -> queueStatus = bb.get().toInt() and 0xFF
                StatusField.CURRENT_JOB_COPY_PROGRESS -> bb.get()
                StatusField.NUMBER_OF_HOSTS           -> numberOfHosts = bb.get().toInt() and 0xFF
                else -> {
                    LogManager.w(TAG, "Unknown StatusField tag 0x%02X — aborting parse".format(tag))
                    break
                }
            }
        }

        return StatusSnapshot(
            systemFlags = systemFlags,
            printStatus = printStatus,
            batteryLevel = batteryLevel,
            batteryStatus = batteryStatus,
            numberOfHosts = numberOfHosts,
            printProgress = printProgress,
            currentJob = currentJob,
            queueStatus = queueStatus
        )
    }

    /**
     * Renders [bitmap] through the same prepareImage pipeline used for
     * printing, populating [lastPrintBitmap] with the result. Used by the
     * dry-run mode in the debug About screen so the user can inspect what
     * the printer would have received without consuming Zink paper.
     */
    fun prepareImageForPreview(bitmap: Bitmap) {
        prepareImage(bitmap, _detectedModel)
    }

    // ---------------------------------------------------------------------------
    // Printer calibration – configurable at runtime via setCalibration().
    //
    // Physical measurements show the Sprocket 200 stretches the image ~5%
    // vertically and shifts it down ~3.5mm. These values pre-correct the
    // bitmap so the physical output matches the on-screen preview.
    //
    // calibrationVScale  = 1/1.05 ≈ 0.9524 – vertical compression factor
    // calibrationVOffset = 46px   = 3.5mm @ 13.18px/mm – top white padding
    //
    // Values are persisted in DataStore and pushed here via setCalibration()
    // every time the user changes them in PrinterConfigScreen — changes take
    // effect immediately on the next print.
    // ---------------------------------------------------------------------------
    @Volatile private var calibrationEnabled = true
    @Volatile private var calibrationVScale  = 0.9524f
    @Volatile private var calibrationVOffset = 46

    fun setCalibration(enabled: Boolean, vScale: Float, vOffset: Int) {
        calibrationEnabled = enabled
        calibrationVScale  = vScale
        calibrationVOffset = vOffset
    }

    // ---------------------------------------------------------------------------
    // Image preparation: scale-to-fill + center-crop to model dimensions, JPEG.
    // ---------------------------------------------------------------------------
    private fun prepareImage(bitmap: Bitmap, model: SprocketModel): ByteArray {
        // Auto-rotate landscape → portrait
        val src = if (bitmap.width > bitmap.height) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(90f) }, true)
        } else bitmap

        val targetW = model.width
        val targetH = model.height

        // If the bitmap already matches the printer dimensions exactly (i.e. it
        // has been prepared by applyFrameForPrint), skip the scale+crop step
        // entirely — re-cropping would destroy frame borders (e.g. the top white
        // border of the Classic/Polaroid frame).
        val fitted = if (src.width == targetW && src.height == targetH) {
            src
        } else {
            // Scale-to-fill (cover): the smaller axis fits exactly, the larger
            // axis overshoots and gets center-cropped.
            val scale = maxOf(targetW.toFloat() / src.width, targetH.toFloat() / src.height)

            // Use ceil() to guarantee the scaled bitmap is at least as big as
            // the target in both dimensions. Float→Int truncation could otherwise
            // produce a bitmap that is one pixel too small, causing
            // Bitmap.createBitmap() below to throw IllegalArgumentException.
            val scaledW = kotlin.math.ceil(src.width  * scale).toInt().coerceAtLeast(targetW)
            val scaledH = kotlin.math.ceil(src.height * scale).toInt().coerceAtLeast(targetH)
            val scaled  = src.scale(scaledW, scaledH)

            // Defensive: if Android's bitmap scaler produced a bitmap that is
            // still smaller than expected (rare but observed on some devices),
            // composite onto a target-sized canvas instead of cropping.
            if (scaled.width >= targetW && scaled.height >= targetH) {
                val cropX = (scaled.width  - targetW) / 2
                val cropY = (scaled.height - targetH) / 2
                Bitmap.createBitmap(scaled, cropX, cropY, targetW, targetH)
            } else {
                LogManager.w(TAG, "Scaled bitmap (${scaled.width}x${scaled.height}) " +
                        "smaller than target (${targetW}x${targetH}); using composite fallback")
                // Center the (too-small) scaled image on a white target-sized canvas.
                Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888).apply {
                    val canvas = Canvas(this)
                    canvas.drawColor(Color.WHITE)
                    val dx = (targetW - scaled.width) / 2f
                    val dy = (targetH - scaled.height) / 2f
                    canvas.drawBitmap(scaled, dx, dy, null)
                }
            }
        }

        // Flatten any transparent pixels onto white. JPEG has no alpha
        // channel, and on Samsung devices transparent pixels otherwise
        // render as black — which would cause black borders on prints.
        val flattened = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(fitted, 0f, 0f, null)
        }

        // Apply printer calibration correction:
        // The Sprocket 200 stretches the image ~5% vertically and shifts it
        // down ~3.5mm. We pre-correct by compressing the image vertically and
        // adding white padding at the top so the physical output matches the preview.
        val corrected = if (calibrationEnabled) {
            val scaledH = (targetH * calibrationVScale).toInt()
            val topPad  = calibrationVOffset
            val scaled  = Bitmap.createScaledBitmap(flattened, targetW, scaledH, true)
            Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888).apply {
                val c = Canvas(this)
                c.drawColor(Color.WHITE)
                c.drawBitmap(scaled, 0f, topPad.toFloat(), null)
            }
        } else {
            flattened
        }

        // Store for debug preview (DEBUG builds only)
        _lastPrintBitmap.value = corrected

        return ByteArrayOutputStream()
            .also { corrected.compress(Bitmap.CompressFormat.JPEG, model.jpegQuality, it) }
            .toByteArray()
    }

    // ---------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------
    internal fun ByteArray.toHexString() = joinToString(" ") { "%02X".format(it) }
}