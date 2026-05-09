package com.pedroperez.cadencepower.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Acts as a BLE peripheral exposing the Cycling Power Service (0x1818) so that
 * Zwift (and any standard cycling app) sees this phone as a power meter.
 *
 * Cycling Power Measurement (0x2A63) — minimal payload:
 *   flags (uint16, LE) = 0x0000  -> only mandatory instantaneous power
 *   instantaneous_power (sint16, LE) in watts
 */
@SuppressLint("MissingPermission")
class PowerAdvertiser(private val context: Context) {

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = btManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var measurementChar: BluetoothGattCharacteristic? = null
    private val subscribers = CopyOnWriteArrayList<BluetoothDevice>()

    var onClientCountChanged: ((Int) -> Unit)? = null

    private val _advertising = MutableStateFlow(false)
    val advertising: StateFlow<Boolean> = _advertising

    /** Last advertise error code (see AdvertiseCallback constants). null = no error. */
    private val _lastError = MutableStateFlow<Int?>(null)
    val lastError: StateFlow<Int?> = _lastError

    fun isPeripheralSupported(): Boolean =
        adapter?.isMultipleAdvertisementSupported == true

    fun start(): Boolean {
        if (gattServer != null) return true
        val advertiser = adapter?.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "BLE advertiser not available"); return false
        }

        // 1) GATT server with Cycling Power Service
        gattServer = btManager.openGattServer(context, gattServerCallback)
        val service = BluetoothGattService(
            BleUuids.CYCLING_POWER_SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Measurement (notify)
        val measurement = BluetoothGattCharacteristic(
            BleUuids.CYCLING_POWER_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0
        )
        val cccd = BluetoothGattDescriptor(
            BleUuids.CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        measurement.addDescriptor(cccd)
        service.addCharacteristic(measurement)
        measurementChar = measurement

        // Feature (read) — 32 bits, all zero = no optional features
        val feature = BluetoothGattCharacteristic(
            BleUuids.CYCLING_POWER_FEATURE,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply { value = byteArrayOf(0, 0, 0, 0) }
        service.addCharacteristic(feature)

        // Sensor Location (read) — 0x0D = rear hub
        val sensorLocation = BluetoothGattCharacteristic(
            BleUuids.SENSOR_LOCATION,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply { value = byteArrayOf(0x0D) }
        service.addCharacteristic(sensorLocation)

        gattServer?.addService(service)

        // Force a short Bluetooth name so the full advertise packet fits in 31
        // bytes WITH the device name in the primary packet (some scanners,
        // including Windows BLE Explorer in passive mode, never request the
        // scan response, so the name must be in the main payload).
        try {
            if (adapter?.name != ADV_NAME) adapter?.name = ADV_NAME
        } catch (e: Exception) {
            Log.w(TAG, "Could not set adapter name: ${e.message}")
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        // Primary packet: flags (3B) + 16-bit Cycling Power UUID (4B) +
        // name "CadencePower" (14B) = 21B, fits in 31B.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BleUuids.CYCLING_POWER_SERVICE))
            .build()

        // Scan response: empty (everything is in the primary packet).
        val scanResponse = AdvertiseData.Builder().build()

        _lastError.value = null
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        Log.i(TAG, "Advertising Cycling Power Service")
        return true
    }

    fun stop() {
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
        gattServer = null
        subscribers.clear()
        _advertising.value = false
        onClientCountChanged?.invoke(0)
    }

    /** Push the latest power reading to all subscribed clients (Zwift). */
    fun publishPower(watts: Int) {
        val char = measurementChar ?: return
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0000) // flags: minimal
        buf.putShort(watts.coerceIn(-32768, 32767).toShort())
        char.value = buf.array()
        for (device in subscribers) {
            try {
                gattServer?.notifyCharacteristicChanged(device, char, false)
            } catch (e: Exception) {
                Log.w(TAG, "notify failed for ${device.address}: ${e.message}")
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertise failed: $errorCode (${errorName(errorCode)})")
            _advertising.value = false
            _lastError.value = errorCode
        }
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "Advertise started")
            _advertising.value = true
            _lastError.value = null
        }
    }

    private fun errorName(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
        else -> "UNKNOWN"
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "Client ${device.address} state=$newState")
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribers.remove(device)
                onClientCountChanged?.invoke(subscribers.size)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            gattServer?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.value
            )
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            gattServer?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                descriptor.value ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray
        ) {
            descriptor.value = value
            if (descriptor.uuid == BleUuids.CCCD) {
                val enabled = value.size >= 2 && value[0] == 0x01.toByte()
                if (enabled) {
                    if (!subscribers.contains(device)) subscribers.add(device)
                } else {
                    subscribers.remove(device)
                }
                onClientCountChanged?.invoke(subscribers.size)
                Log.i(TAG, "Subscribers now: ${subscribers.size}")
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    fun subscriberCount(): Int = subscribers.size

    companion object {
        private const val TAG = "PowerAdvertiser"
        private const val ADV_NAME = "CadencePower"
    }
}
