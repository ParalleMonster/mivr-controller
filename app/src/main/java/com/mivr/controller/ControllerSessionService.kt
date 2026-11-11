package com.mivr.controller

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.util.Log

internal class ControllerSessionService : Service() {
    inner class LocalBinder : Binder() {
        val session: ControllerSessionService get() = this@ControllerSessionService
    }

    val input = ControllerInput()
    var onChanged: (() -> Unit)? = null
    var status = "等待开始"
        private set
    var pairingStatus = ""
        private set
    val isActive get() = peripheral != null || pairingTest != null
    val isControllerReady get() = peripheral?.isControllerReady == true
    val canStartPairing get() = pairingTest == null
    val canStop get() = isActive || preferences.contains(ControllerPeripheral.PREVIOUS_NAME)
    val savedPairingEncryption: Boolean?
        get() = if (!isActive && preferences.contains(RESUME_HEADLESS)) {
            preferences.getBoolean(RESUME_HEADLESS, true)
        } else {
            null
        }
    val savedStaticAddress get() = preferences.getBoolean(RESUME_STATIC_ADDRESS, true)

    private val binder = LocalBinder()
    private val preferences get() = getSharedPreferences("ble", MODE_PRIVATE)
    private val manager get() = getSystemService(BluetoothManager::class.java)
    private var peripheral: ControllerPeripheral? = null
    private var pairingTest: RootPairingSession? = null
    private var pairingPeer: BluetoothDevice? = null
    private var destroyed = false
    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = pairingDevice(intent) ?: return
            if (peripheral?.hasClient(device) != true && pairingPeer != device) return
            when (intent.action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    pairingPeer = device
                    val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
                    val message = when (variant) {
                        BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION -> "手机系统要求六位数字比对"
                        PAIRING_VARIANT_CONSENT -> "请在手机的系统弹窗点「配对」"
                        else -> "请查看手机的蓝牙配对提示"
                    }
                    showPairingStatus(message)
                    Log.i("MiVR", "Pairing request variant=$variant")
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                    val message = when (state) {
                        BluetoothDevice.BOND_BONDING -> "系统正在配对"
                        BluetoothDevice.BOND_BONDED -> "系统绑定已完成"
                        BluetoothDevice.BOND_NONE -> "系统未绑定"
                        else -> return
                    }
                    pairingPeer = if (state == BluetoothDevice.BOND_BONDING) device else null
                    showPairingStatus(message)
                    Log.i("MiVR", "Bond state changed $previous -> $state")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pairingReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(pairingReceiver, filter)
        }
        if (Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        ) {
            restoreNameIfNeeded()
        }
    }

    override fun onBind(intent: Intent): Binder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, sessionNotification())
        val encrypted = intent?.getBooleanExtra(EXTRA_ENCRYPTED, true) ?: true
        if (intent?.getBooleanExtra(EXTRA_HEADLESS, false) == true) {
            if (canStartPairing) {
                startPairingTest(encrypted, intent.getBooleanExtra(EXTRA_STATIC_ADDRESS, false))
            }
        } else if (!isActive && !startPeripheral(encrypted)) {
            finishSession()
        }
        return START_NOT_STICKY
    }

    fun stopController(message: String = "广播已停止") {
        clearSavedPairing()
        val test = pairingTest
        stopPeripheral(if (test == null) message else "正在结束免六位码会话")
        if (test == null) finishSession() else test.stop()
    }

    private fun startPairingTest(encrypted: Boolean, staticAddress: Boolean) {
        stopPeripheral("准备免六位码配对")
        val test = RootPairingSession(
            this,
            staticAddress = staticAddress,
            onReady = { reuse ->
                if (startPeripheral(encrypted, reuse, staticAddress)) {
                    showPairingStatus("请在两分钟内用头显连接")
                } else {
                    pairingTest?.stop()
                }
            },
            onConnected = {
                preferences.edit()
                    .putBoolean(RESUME_HEADLESS, encrypted)
                    .putBoolean(RESUME_STATIC_ADDRESS, staticAddress)
                    .commit()
                showPairingStatus("免六位码模式已开启")
            },
            onRestoring = { stopPeripheral("正在恢复蓝牙配置") },
            onFinished = { message ->
                if (!destroyed) {
                    clearSavedPairing()
                    pairingTest = null
                    stopPeripheral(message)
                    showPairingStatus(message)
                    finishSession()
                }
            },
        )
        pairingTest = test
        showStatus("等待 root 授权 · 蓝牙会短暂断开")
        test.start()
    }

    private fun startPeripheral(encrypted: Boolean, reuse: Boolean = false, staticAddress: Boolean = false): Boolean {
        val bluetooth = manager
        val adapter = bluetooth?.adapter
        if (adapter == null) {
            showStatus("此手机不支持蓝牙外设")
            return false
        }
        if (!adapter.isEnabled) {
            showStatus("请开启蓝牙")
            return false
        }
        if (adapter.bluetoothLeAdvertiser == null) {
            showStatus("此手机未提供 BLE 外设广播")
            return false
        }
        if (!restoreNameIfNeeded()) return false
        pairingPeer = null
        showPairingStatus(if (pairingTest == null) "配对由手机系统和头显协商" else "正在准备免六位码连接")
        peripheral = ControllerPeripheral(
            this, bluetooth, encrypted, input, ::showStatus, reuse,
            staticAddress = staticAddress,
            onMotionReady = { pairingTest?.keepConnected() },
            failed = ::stopController,
        )
        showStatus("正在启动手柄")
        peripheral?.start()
        return peripheral != null
    }

    private fun stopPeripheral(message: String) {
        peripheral?.close()
        peripheral = null
        input.releaseAll()
        pairingPeer = null
        if (restoreNameIfNeeded()) showStatus(message)
    }

    private fun restoreNameIfNeeded(): Boolean {
        val previous = preferences.getString(ControllerPeripheral.PREVIOUS_NAME, null) ?: return true
        val adapter = manager?.adapter
        val restored = runCatching {
            adapter?.isEnabled == true &&
                (adapter.name != ControllerProfile.NAME || adapter.setName(previous))
        }.getOrDefault(false)
        if (restored) {
            preferences.edit().remove(ControllerPeripheral.PREVIOUS_NAME).apply()
        } else {
            showStatus("原蓝牙名称待恢复。请开启蓝牙并再次点「停止广播」。")
        }
        return restored
    }

    private fun showStatus(message: String) {
        status = message
        onChanged?.invoke()
        Log.i("MiVR", message)
    }

    private fun showPairingStatus(message: String) {
        pairingStatus = message
        onChanged?.invoke()
    }

    @Suppress("DEPRECATION")
    private fun pairingDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun sessionNotification(): Notification {
        val channel = NotificationChannel("mivr-session", "MiVR 手柄", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return Notification.Builder(this, channel.id)
            .setContentTitle("MiVR 手柄会话")
            .setContentText("点此返回应用管理连接")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .build()
    }

    private fun finishSession() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun clearSavedPairing() {
        preferences.edit().remove(RESUME_HEADLESS).remove(RESUME_STATIC_ADDRESS).commit()
    }

    override fun onDestroy() {
        destroyed = true
        unregisterReceiver(pairingReceiver)
        stopPeripheral("广播已停止")
        pairingTest?.stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_HEADLESS = "headless"
        const val EXTRA_ENCRYPTED = "encrypted"
        const val EXTRA_STATIC_ADDRESS = "static_address"
        // BluetoothDevice.PAIRING_VARIANT_CONSENT is a hidden SDK constant.
        private const val PAIRING_VARIANT_CONSENT = 3
        private const val RESUME_HEADLESS = "resume_headless"
        private const val RESUME_STATIC_ADDRESS = "resume_static_address"
    }
}
