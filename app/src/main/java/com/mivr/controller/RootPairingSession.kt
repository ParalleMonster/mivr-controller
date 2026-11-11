package com.mivr.controller

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

internal class RootPairingSession(
    context: Context,
    private val staticAddress: Boolean,
    private val onReady: (Boolean) -> Unit,
    private val onConnected: () -> Unit,
    private val onRestoring: () -> Unit,
    private val onFinished: (String) -> Unit,
) {
    private val assets = context.assets
    private val apk = context.applicationInfo.sourceDir
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var process: Process? = null
    @Volatile private var stopRequested = false
    private var keepRequested = false

    fun start() {
        Thread({
            var message = "无法启动免六位码，请确认本机已 root，并在 Root 管理器中授权。"
            try {
                val script = assets.open("redmi-headless-pairing.sh").bufferedReader().use { it.readText() }
                if (!stopRequested) {
                    val mode = if (staticAddress) "--app-static" else "--app"
                    val command = ProcessBuilder("su", "-c", "/system/bin/sh -c ${quote(script)} mivr $mode ${quote(apk)}")
                        .redirectErrorStream(true).start()
                    process = command
                    if (stopRequested) closeInput(command)
                    var restoreFailed = false
                    var restored = false
                    var reusingConfig = false
                    var staticAddressFailed = false
                    command.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            Log.i("MiVR", "Pairing test: $line")
                            when {
                                line == "Reusing previous pairing configuration." -> reusingConfig = true
                                line.startsWith("READY:") -> {
                                    val reuse = reusingConfig
                                    main.post { if (!stopRequested) onReady(reuse) }
                                }
                                line == "CONNECTED" -> main.post { if (!stopRequested) onConnected() }
                                line == "RESTORING" -> main.post {
                                    try {
                                        if (!stopRequested) onRestoring()
                                    } finally {
                                        closeInput(command)
                                    }
                                }
                                line == "RESTORE_FAILED" -> restoreFailed = true
                                line == "STATIC_ADDRESS_FAILED" -> staticAddressFailed = true
                                line == "UNSUPPORTED_SMP" -> message = "这台手机的蓝牙栈暂不支持免六位码，请尝试通用模式。"
                                line == "EXISTING_SMP_OVERRIDE" -> message = "检测到其他蓝牙配置正在生效，请先结束对应会话。"
                                line == "BUSY" -> message = "上一轮蓝牙会话正在结束，请稍候。"
                                line == "BLUETOOTH_OFF" -> message = "请先开启蓝牙。"
                                line == "Original Bluetooth configuration restored." -> restored = true
                            }
                        }
                    }
                    val result = command.waitFor()
                    message = when {
                        restoreFailed -> "无法确认蓝牙配置已恢复，请重启手机。"
                        staticAddressFailed -> "固定地址不可用。请切换到系统地址免六位码或通用模式。"
                        restored && result == 0 -> "免六位码测试已结束 · 蓝牙配置已恢复"
                        restored -> "测试启动失败 · 蓝牙配置已恢复"
                        stopRequested -> "免六位码测试已取消"
                        else -> message
                    }
                } else {
                    message = "免六位码测试已取消"
                }
            } catch (error: Exception) {
                Log.w("MiVR", "Cannot run pairing test", error)
            } finally {
                process?.let(::closeInput)
                process = null
                main.post { onFinished(message) }
            }
        }, "MiVR-pairing-test").start()
    }

    fun stop() {
        stopRequested = true
        process?.let(::closeInput)
    }

    fun keepConnected() {
        val command = process ?: return
        if (keepRequested || stopRequested) return
        keepRequested = true
        try {
            command.outputStream.write("KEEP\n".toByteArray(Charsets.US_ASCII))
            command.outputStream.flush()
        } catch (error: java.io.IOException) {
            Log.w("MiVR", "Pairing window has ended", error)
        }
    }

    private fun closeInput(command: Process) {
        runCatching { command.outputStream.close() }
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
