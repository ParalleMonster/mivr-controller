package com.mivr.controller

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt as Gatt
import android.bluetooth.BluetoothGattCharacteristic as Characteristic
import android.bluetooth.BluetoothGattDescriptor as Descriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.DeadObjectException
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.UUID

internal class ControllerPeripheral(
    private val context: Context,
    private val manager: BluetoothManager,
    private val encryptMotionSubscriptions: Boolean,
    private val input: ControllerInput,
    private val status: (String) -> Unit,
    private val reuseConnection: Boolean,
    private val staticAddress: Boolean,
    private val onMotionReady: () -> Unit,
    private val failed: (String) -> Unit,
) {
    private val adapter = manager.adapter
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val services = ControllerProfile.services(encryptMotionSubscriptions).iterator()
    private val clients = mutableSetOf<BluetoothDevice>()
    private val subscriptions = mutableMapOf<Pair<BluetoothDevice, UUID>, ByteArray>()
    private val preferences = context.getSharedPreferences("ble", Context.MODE_PRIVATE)
    private val reusedSubscriptions = if (reuseConnection) {
        preferences.getStringSet(MOTION_SUBSCRIPTIONS, emptySet()).orEmpty().toMutableSet()
    } else {
        mutableSetOf()
    }
    private val motionSensors = ControllerSensors(context)
    private val motionStreams = listOf(ControllerProfile.GYRO, ControllerProfile.SENSORS)
    private val pending = ArrayDeque<MotionNotification>()
    private val reportedStreams = mutableSetOf<Pair<BluetoothDevice, UUID>>()
    private var inFlight: MotionNotification? = null
    private var streaming = false
    private var advertising = false
    private var nextFrameMicros = 0L
    private val frame = Runnable { sendMotionFrame() }
    private var advertiser = adapter.bluetoothLeAdvertiser
    private var server: BluetoothGattServer? = null
    private var closed = false
    private val timeout = Runnable { failed("启动超时") }

    val isControllerReady get() = advertising && clients.any { device ->
        motionStreams.all { id -> (device to id) in reportedStreams }
    }

    private val advertisingCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertisingStarted()
        }

        override fun onStartFailure(errorCode: Int) {
            if (!closed) failed(
                if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED) "此手机不支持 BLE 外设广播"
                else "启动广播失败（$errorCode）",
            )
        }
    }

    private fun advertisingStarted() {
        if (closed) return
        advertising = true
        main.removeCallbacks(timeout)
        showAdvertisingStatus()
        Log.i("MiVR", "Advertising started (static address: $staticAddress, encrypted motion subscriptions: $encryptMotionSubscriptions)")
        updateMotionStreaming()
    }

    private fun showAdvertisingStatus() {
        if (!advertising || closed) return
        val address = (if (staticAddress) ControllerAdvertising.fixedAddress else null) ?: "系统地址"
        val connection = when {
            clients.isEmpty() -> "等待头显连接"
            isControllerReady -> "头显已连接"
            else -> "头显已连接 · 正在初始化手柄"
        }
        status("$connection\nOMVR-V190 · $address")
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(result: Int, service: BluetoothGattService) = onMain {
            if (result == Gatt.GATT_SUCCESS) {
                Log.i("MiVR", "Service registered ${service.uuid} (${service.characteristics.size} characteristics)")
                addNextService()
            } else {
                failed("GATT 服务注册失败（$result）")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, result: Int, newState: Int) = onMain {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    clients.add(device)
                    if (reuseConnection && device.bondState == BluetoothDevice.BOND_BONDED) {
                        for (id in motionStreams) {
                            if (subscriptionKey(device, id) in reusedSubscriptions) {
                                subscriptions[device to id] = Descriptor.ENABLE_NOTIFICATION_VALUE
                            }
                        }
                        updateMotionStreaming()
                    }
                    Log.i("MiVR", "Central connected")
                    showAdvertisingStatus()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    clients.remove(device)
                    subscriptions.keys.removeAll { it.first == device }
                    reportedStreams.removeAll { it.first == device }
                    pending.removeAll { it.device == device }
                    if (inFlight?.device == device) inFlight = null
                    updateMotionStreaming()
                    sendNextNotification()
                    Log.i("MiVR", "Central disconnected ($result)")
                    showAdvertisingStatus()
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: Characteristic,
        ) = onMain {
            val value = when (characteristic.uuid) {
                ControllerProfile.STATUS ->
                    ControllerProfile.statusReport(battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
                else -> ControllerProfile.deviceInformationValues[characteristic.uuid]?.toByteArray(Charsets.UTF_8)
            }
            Log.i("MiVR", "Characteristic read ${characteristic.uuid}")
            replyRead(device, requestId, offset, value)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: Characteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) = onMain {
            Log.i("MiVR", "Characteristic write ${characteristic.uuid}, ${value.size} bytes, unsupported")
            if (responseNeeded) server?.sendResponse(device, requestId, Gatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: Descriptor,
        ) = onMain {
            Log.i("MiVR", "Descriptor read ${descriptor.uuid} on ${descriptor.characteristic.uuid}")
            val value = if (descriptor.uuid == ControllerProfile.CLIENT_CONFIGURATION) {
                subscriptions[device to descriptor.characteristic.uuid] ?: Descriptor.DISABLE_NOTIFICATION_VALUE
            } else {
                null
            }
            replyRead(device, requestId, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: Descriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) = onMain {
            val result = when {
                descriptor.uuid != ControllerProfile.CLIENT_CONFIGURATION || preparedWrite ->
                    Gatt.GATT_REQUEST_NOT_SUPPORTED
                offset != 0 -> Gatt.GATT_INVALID_OFFSET
                value.size != 2 -> Gatt.GATT_INVALID_ATTRIBUTE_LENGTH
                !validSubscription(descriptor.characteristic.properties, value) -> Gatt.GATT_REQUEST_NOT_SUPPORTED
                else -> {
                    subscriptions[device to descriptor.characteristic.uuid] = value.copyOf()
                    Gatt.GATT_SUCCESS
                }
            }
            Log.i("MiVR", "Descriptor write ${descriptor.uuid} on ${descriptor.characteristic.uuid}, status=$result")
            if (responseNeeded) server?.sendResponse(device, requestId, result, 0, null)
            if (result == Gatt.GATT_SUCCESS && descriptor.characteristic.uuid in motionStreams) {
                val id = descriptor.characteristic.uuid
                if (!isSubscribed(device, id) && reportedStreams.remove(device to id)) {
                    showAdvertisingStatus()
                }
                if (device.bondState == BluetoothDevice.BOND_BONDED) {
                    val key = subscriptionKey(device, id)
                    if (isSubscribed(device, id)) reusedSubscriptions.add(key) else reusedSubscriptions.remove(key)
                    preferences.edit().putStringSet(MOTION_SUBSCRIPTIONS, reusedSubscriptions.toSet()).commit()
                }
                updateMotionStreaming()
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) = onMain {
            server?.sendResponse(device, requestId, Gatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) = onMain {
            val sent = inFlight
            if (sent?.device != device) return@onMain
            inFlight = null
            if (status == Gatt.GATT_SUCCESS && isSubscribed(device, sent.characteristic.uuid) &&
                reportedStreams.add(device to sent.characteristic.uuid)
            ) {
                Log.i("MiVR", "Motion notification sent ${sent.characteristic.uuid}, ${sent.value.size} bytes")
                showAdvertisingStatus()
                onMotionReady()
            }
            sendNextNotification()
        }
    }

    fun hasClient(device: BluetoothDevice): Boolean = device in clients

    fun start() {
        if (!reuseConnection) preferences.edit().remove(MOTION_SUBSCRIPTIONS).apply()
        if (advertiser == null) {
            failed("当前蓝牙状态下无法启动 BLE 广播")
            return
        }
        server = manager.openGattServer(context, gattCallback)
        if (server == null) {
            failed("无法打开 GATT 服务器")
            return
        }
        main.postDelayed(timeout, 10_000)
        addNextService()
    }

    private fun addNextService() {
        if (services.hasNext()) {
            if (server?.addService(services.next()) != true) failed("无法添加 GATT 服务")
            return
        }
        val name = adapter.name
        if (name == null) {
            failed("无法读取手机蓝牙名称")
            return
        }
        if (name != ControllerProfile.NAME) {
            val saved = context.getSharedPreferences("ble", Context.MODE_PRIVATE).edit()
                .putString(PREVIOUS_NAME, name).commit()
            if (!saved || !runCatching { adapter.setName(ControllerProfile.NAME) }.getOrDefault(false)) {
                failed("无法更改蓝牙名称。请在系统蓝牙设置中将手机名称改为 OMVR-V190，再试一次。")
                return
            }
        }
        awaitName()
    }

    private fun awaitName() {
        if (closed) return
        if (adapter.name != ControllerProfile.NAME) {
            main.postDelayed(::awaitName, 150)
            return
        }
        advertiser?.startAdvertising(
            ControllerAdvertising.settings().build(), ControllerAdvertising.data(),
            ControllerAdvertising.scanResponse(), advertisingCallback,
        )
    }

    private fun replyRead(device: BluetoothDevice, requestId: Int, offset: Int, value: ByteArray?) {
        when {
            value == null -> server?.sendResponse(device, requestId, Gatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            offset > value.size -> server?.sendResponse(device, requestId, Gatt.GATT_INVALID_OFFSET, offset, null)
            else -> server?.sendResponse(device, requestId, Gatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
        }
    }

    private fun validSubscription(properties: Int, value: ByteArray): Boolean = when {
        value.contentEquals(Descriptor.DISABLE_NOTIFICATION_VALUE) -> true
        value.contentEquals(Descriptor.ENABLE_NOTIFICATION_VALUE) -> properties and Characteristic.PROPERTY_NOTIFY != 0
        value.contentEquals(Descriptor.ENABLE_INDICATION_VALUE) -> properties and Characteristic.PROPERTY_INDICATE != 0
        else -> false
    }

    private fun isSubscribed(device: BluetoothDevice, id: UUID): Boolean =
        subscriptions[device to id]?.contentEquals(Descriptor.ENABLE_NOTIFICATION_VALUE) == true

    private fun subscriptionKey(device: BluetoothDevice, id: UUID) = "${device.address}/$id"

    private fun updateMotionStreaming() {
        val active = advertising && clients.any { device -> motionStreams.any { isSubscribed(device, it) } }
        if (active == streaming) return
        streaming = active
        if (active) {
            motionSensors.start()
            nextFrameMicros = SystemClock.uptimeMillis() * 1_000
            main.post(frame)
        } else {
            main.removeCallbacks(frame)
            motionSensors.stop()
            pending.clear()
        }
    }

    private fun sendMotionFrame() {
        val service = server?.getService(ControllerProfile.CONTROLLER_SERVICE) ?: return
        // The Bluetooth link can outlive this process. Keep packet time on the phone's uptime clock.
        val counter = ((nextFrameMicros / MOTION_FRAME_MICROS * 10) and 0xffff).toInt()
        val gyro = motionSensors.gyroReport(counter)
        if (pending.isEmpty() && inFlight == null) {
            val sensors = motionSensors.sensorReport(counter, input)
            for (id in motionStreams) {
                val characteristic = service.getCharacteristic(id) ?: continue
                val value = if (id == ControllerProfile.GYRO) gyro else sensors
                for (device in clients) {
                    if (isSubscribed(device, id)) pending.addLast(MotionNotification(device, characteristic, value))
                }
            }
            sendNextNotification()
        }
        val nowMicros = SystemClock.uptimeMillis() * 1_000
        nextFrameMicros = maxOf(nextFrameMicros + MOTION_FRAME_MICROS, nowMicros + 1_000)
        if (streaming) main.postAtTime(frame, (nextFrameMicros + 999) / 1_000)
    }

    @Suppress("DEPRECATION")
    private fun sendNextNotification() {
        val activeServer = server ?: return
        while (inFlight == null) {
            val next = pending.removeFirstOrNull() ?: return
            val (device, characteristic, value) = next
            if (device !in clients || !isSubscribed(device, characteristic.uuid)) continue
            val sent = whileBluetoothAlive {
                if (Build.VERSION.SDK_INT >= 33) {
                    activeServer.notifyCharacteristicChanged(device, characteristic, false, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    characteristic.value = value
                    activeServer.notifyCharacteristicChanged(device, characteristic, false)
                }
            }
            if (sent == null) {
                failed("蓝牙已断开 · 广播已停止")
                return
            }
            if (sent) inFlight = next
        }
    }

    private fun onMain(action: () -> Unit) {
        main.post {
            if (!closed && whileBluetoothAlive(action) == null) {
                failed("蓝牙已断开 · 广播已停止")
            }
        }
    }

    private inline fun <T> whileBluetoothAlive(operation: () -> T): T? {
        return try {
            operation()
        } catch (error: RuntimeException) {
            if (error.cause !is DeadObjectException) throw error
            null
        }
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        motionSensors.stop()
        streaming = false
        pending.clear()
        inFlight = null
        whileBluetoothAlive { advertiser?.stopAdvertising(advertisingCallback) }
        advertiser = null
        val activeServer = server
        server = null
        if (activeServer != null) {
            for (client in clients) whileBluetoothAlive { activeServer.cancelConnection(client) }
            whileBluetoothAlive { activeServer.close() }
        }
        clients.clear()
        subscriptions.clear()
    }

    private data class MotionNotification(
        val device: BluetoothDevice,
        val characteristic: Characteristic,
        val value: ByteArray,
    )

    companion object {
        const val PREVIOUS_NAME = "previous_bluetooth_name"
        private const val MOTION_SUBSCRIPTIONS = "motion_subscriptions"
        private const val MOTION_FRAME_MICROS = 11_500L
    }
}
