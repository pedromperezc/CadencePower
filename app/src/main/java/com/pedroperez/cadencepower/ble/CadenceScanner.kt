package com.pedroperez.cadencepower.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Connects to a CSC (Cycling Speed and Cadence) sensor like the iGPSport CAD 70
 * and exposes the live cadence in RPM as a StateFlow.
 *
 * CSC Measurement (0x2A5B) layout (bit 1 = "Crank Revolution Data Present"):
 *   flags(1) | [wheel rev(4) + last wheel evt(2) if bit0] | [crank rev(2) + last crank evt(2) if bit1]
 *
 * Cadence is computed from the deltas between two consecutive notifications.
 */
@SuppressLint("MissingPermission")
class CadenceScanner(private val context: Context) {

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED, DISCONNECTED, ERROR }

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = btManager.adapter

    private var gatt: BluetoothGatt? = null

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val _cadenceRpm = MutableStateFlow(0.0)
    val cadenceRpm: StateFlow<Double> = _cadenceRpm

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName

    // Last sample for delta computation
    private var lastCrankRevs: Int = -1
    private var lastCrankEventTime1024: Int = -1

    /** Wall-clock millis when we last saw new crank revolutions. 0 = never. */
    @Volatile var lastCrankActivityMillis: Long = 0L
        private set

    /** Force the published cadence to 0 (called by the watchdog). */
    fun forceZeroCadence() { _cadenceRpm.value = 0.0 }

    fun start() {
        if (_state.value == State.SCANNING || _state.value == State.CONNECTED) return
        val scanner = adapter?.bluetoothLeScanner ?: run {
            _state.value = State.ERROR; return
        }
        _state.value = State.SCANNING
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleUuids.CSC_SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        Log.i(TAG, "Scanning for CSC sensors...")
    }

    fun stop() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _state.value = State.IDLE
        _cadenceRpm.value = 0.0
        _deviceName.value = null
        lastCrankRevs = -1
        lastCrankEventTime1024 = -1
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device: BluetoothDevice = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: "(unknown)"
            Log.i(TAG, "Found CSC sensor: $name @ ${device.address} rssi=${result.rssi}")
            adapter?.bluetoothLeScanner?.stopScan(this)
            _deviceName.value = name
            _state.value = State.CONNECTING
            gatt = device.connectGatt(context, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            _state.value = State.ERROR
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected, discovering services")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected from CSC sensor")
                _state.value = State.DISCONNECTED
                _cadenceRpm.value = 0.0
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(BleUuids.CSC_SERVICE) ?: run {
                Log.e(TAG, "CSC service not present"); return
            }
            val char = service.getCharacteristic(BleUuids.CSC_MEASUREMENT) ?: return
            g.setCharacteristicNotification(char, true)
            char.getDescriptor(BleUuids.CCCD)?.let { d ->
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            }
            _state.value = State.CONNECTED
        }

        @Deprecated("for legacy API")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != BleUuids.CSC_MEASUREMENT) return
            parseCscMeasurement(characteristic.value)
        }
    }

    private fun parseCscMeasurement(data: ByteArray) {
        if (data.isEmpty()) return
        val flags = data[0].toInt() and 0xFF
        val hasWheel = flags and 0x01 != 0
        val hasCrank = flags and 0x02 != 0

        var offset = 1
        if (hasWheel) offset += 6 // 4 bytes wheel revs + 2 bytes wheel evt time
        if (!hasCrank || data.size < offset + 4) return

        val crankRevs = ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset].toInt() and 0xFF)
        val crankEvt = ((data[offset + 3].toInt() and 0xFF) shl 8) or (data[offset + 2].toInt() and 0xFF)

        if (lastCrankRevs >= 0) {
            val dRevs = (crankRevs - lastCrankRevs + 0x10000) and 0xFFFF
            val dTime1024 = (crankEvt - lastCrankEventTime1024 + 0x10000) and 0xFFFF

            if (dRevs > 0 && dTime1024 > 0) {
                val dtSeconds = dTime1024 / 1024.0
                val rpm = dRevs / dtSeconds * 60.0
                _cadenceRpm.value = rpm
                lastCrankActivityMillis = System.currentTimeMillis()
            }
            // If dRevs == 0 we don't change anything here; the watchdog in
            // MainViewModel will zero out the cadence after a timeout.
        }
        lastCrankRevs = crankRevs
        lastCrankEventTime1024 = crankEvt
    }

    companion object { private const val TAG = "CadenceScanner" }
}
