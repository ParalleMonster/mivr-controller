package com.mivr.controller

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var pages: FrameLayout
    private lateinit var connectionPage: View
    private lateinit var controllerPage: View
    private lateinit var connectionStatus: TextView
    private lateinit var pairingStatus: TextView
    private lateinit var connect: Button
    private lateinit var stop: Button
    private lateinit var modeButton: Button
    private lateinit var layoutButton: Button
    private lateinit var keys: List<ControllerKey>
    private var session: ControllerSessionService? = null
    private var bound = false
    private var viewingConnection = false
    private var volumeDownAt: Long? = null
    private val releaseVolumeTrigger = Runnable { session?.input?.volumeTrigger = false }
    private val uiPreferences get() = getSharedPreferences("ui", MODE_PRIVATE)
    private val modes by lazy {
        when {
            ControllerAdvertising.fixedAddress != null ->
                listOf(ConnectionMode.FIXED, ConnectionMode.ROOT, ConnectionMode.STANDARD)
            Build.VERSION.SDK_INT >= 33 -> listOf(ConnectionMode.STANDARD, ConnectionMode.ROOT)
            else -> listOf(ConnectionMode.STANDARD)
        }
    }
    private var mode = ConnectionMode.STANDARD
    private var controllerLayout = ControllerLayout.BLIND
    private val stateListener = { refreshSession() }
    private val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
    )
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val active = (binder as ControllerSessionService.LocalBinder).session
            session = active
            active.onChanged = stateListener
            refreshSession()
            if (hasPermissions() && ConnectionMode.ROOT in modes) {
                active.savedPairingEncryption?.let { encrypted ->
                    val previousMode = if (active.savedStaticAddress && ConnectionMode.FIXED in modes) {
                        ConnectionMode.FIXED
                    } else {
                        ConnectionMode.ROOT
                    }
                    mode = previousMode
                    refreshSession()
                    startSession(previousMode, encrypted)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            releaseControls()
            session = null
            refreshSession()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = modes.firstOrNull { it.name == uiPreferences.getString("connection_mode", null) } ?: modes.first()
        controllerLayout = ControllerLayout.entries.firstOrNull {
            it.name == uiPreferences.getString("controller_layout", null)
        } ?: ControllerLayout.BLIND
        viewingConnection = savedInstanceState?.getBoolean("viewing_connection") == true
        connectionPage = buildConnectionPage()
        controllerPage = buildControllerPage().apply { visibility = View.GONE }
        pages = FrameLayout(this).apply {
            addView(connectionPage)
            addView(controllerPage)
        }
        setContentView(pages)
        refreshSession()
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, ControllerSessionService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onPause() {
        releaseControls()
        super.onPause()
    }

    override fun onStop() {
        if (session?.onChanged === stateListener) session?.onChanged = null
        session = null
        if (bound) unbindService(connection)
        bound = false
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("viewing_connection", viewingConnection)
        super.onSaveInstanceState(outState)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) {
            val pressedAt = volumeDownAt ?: return super.dispatchKeyEvent(event)
            volumeDownAt = null
            val remaining = 60L - (SystemClock.uptimeMillis() - pressedAt)
            if (event.isCanceled || remaining <= 0) {
                releaseVolumeTrigger.run()
            } else {
                controllerPage.postDelayed(releaseVolumeTrigger, remaining)
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN || !controllerPage.isShown || session?.isControllerReady != true) {
            return super.dispatchKeyEvent(event)
        }
        if (event.repeatCount == 0) {
            controllerPage.removeCallbacks(releaseVolumeTrigger)
            volumeDownAt = SystemClock.uptimeMillis()
            session?.input?.volumeTrigger = true
            controllerPage.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && ::controllerPage.isInitialized) clearVolumeTrigger()
    }

    @Deprecated("Handled here to return from the controller to connection controls")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (controllerPage.visibility == View.VISIBLE) {
            viewingConnection = true
            refreshSession()
        } else {
            super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != NEARBY_DEVICES) return
        if (hasPermissions()) {
            startSession()
        } else {
            connectionStatus.text = "请允许附近设备权限"
        }
    }

    private fun hasPermissions() = Build.VERSION.SDK_INT < 31 ||
        permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun startSession(selectedMode: ConnectionMode = mode, encrypted: Boolean = selectedMode != ConnectionMode.STANDARD) {
        startForegroundService(Intent(this, ControllerSessionService::class.java).apply {
            putExtra(ControllerSessionService.EXTRA_HEADLESS, selectedMode != ConnectionMode.STANDARD)
            putExtra(ControllerSessionService.EXTRA_ENCRYPTED, encrypted)
            putExtra(ControllerSessionService.EXTRA_STATIC_ADDRESS, selectedMode == ConnectionMode.FIXED)
        })
    }

    private fun refreshSession() {
        val active = session
        val ready = active?.isControllerReady == true
        if (active != null && !ready) viewingConnection = false
        val showController = ready && !viewingConnection
        if (!showController && controllerPage.visibility == View.VISIBLE) releaseControls()
        controllerPage.visibility = if (showController) View.VISIBLE else View.GONE
        connectionPage.visibility = if (showController) View.GONE else View.VISIBLE

        connectionStatus.text = active?.status ?: "正在加载"
        pairingStatus.text = active?.pairingStatus.orEmpty()
        pairingStatus.visibility = if (pairingStatus.text.isBlank() || pairingStatus.text == connectionStatus.text) {
            View.GONE
        } else {
            View.VISIBLE
        }
        connect.text = when {
            ready -> "进入手柄"
            active?.isActive == true -> "等待头显连接"
            else -> "连接头显"
        }
        connect.isEnabled = ready || active?.isActive == false
        connect.alpha = if (connect.isEnabled) 1f else 0.48f
        stop.visibility = if (active?.canStop == true) View.VISIBLE else View.GONE
        modeButton.text = "连接方式 · ${mode.title}"
        modeButton.isEnabled = active?.isActive == false
        layoutButton.text = "按键布局 · ${controllerLayout.title}"
        if (active?.isActive == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun releaseControls() {
        clearVolumeTrigger()
        keys.forEach(ControllerKey::reset)
        session?.input?.releaseAll()
    }

    private fun clearVolumeTrigger() {
        controllerPage.removeCallbacks(releaseVolumeTrigger)
        volumeDownAt = null
        releaseVolumeTrigger.run()
    }

    private fun buildConnectionPage(): View {
        val body = column(dp(24), dp(28), Gravity.CENTER_VERTICAL)
        body.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_mivr) }, layout(dp(60), dp(60)))
        body.addView(label("MiVR 手柄", 34f, INK, bold = true), layout(top = 12))
        body.addView(label("用这台手机控制小米 VR", 16f, MUTED), layout(top = 6))
        val card = column(dp(18), dp(18)).apply { background = panel(22f, PANEL, BORDER) }
        card.addView(label("连接状态", 13f, ACCENT, bold = true))
        connectionStatus = label("正在加载", 17f, INK).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        pairingStatus = label("", 14f, MUTED)
        card.addView(connectionStatus, layout(top = 10))
        card.addView(pairingStatus, layout(top = 6))
        body.addView(card, layout(top = 26))
        modeButton = action("", PANEL, INK).apply {
            visibility = if (modes.size > 1) View.VISIBLE else View.GONE
            setOnClickListener { chooseConnectionMode() }
        }
        layoutButton = action("", PANEL, INK).apply {
            setOnClickListener { chooseLayout() }
        }
        body.addView(modeButton, layout(height = dp(50), top = 14))
        body.addView(layoutButton, layout(height = dp(50), top = 8))
        connect = action("连接头显", ACCENT, CANVAS).apply {
            setOnClickListener {
                when {
                    session?.isControllerReady == true -> {
                        viewingConnection = false
                        refreshSession()
                    }
                    hasPermissions() -> startSession()
                    else -> requestPermissions(permissions, NEARBY_DEVICES)
                }
            }
        }
        stop = action("停止广播", PANEL, INK).apply {
            setOnClickListener { session?.stopController() }
        }
        body.addView(connect, layout(height = dp(56), top = 16))
        body.addView(stop, layout(height = dp(52), top = 10))
        body.addView(label("首次连接时，在小米 VR 中选择 OMVR-V190。", 13f, MUTED), layout(top = 14))
        body.addView(label("手柄页可用手机音量减键按扳机。", 13f, MUTED), layout(top = 6))
        return scrolling(body)
    }

    private fun buildControllerPage(): View {
        val width = (resources.displayMetrics.widthPixels - dp(48)).coerceIn(dp(180), dp(340))
        val padSize = (width - dp(24)).coerceAtMost(dp(304))
        val body = column(dp(16), dp(16), Gravity.CENTER)
        val controller = column(dp(12), dp(16), Gravity.CENTER_HORIZONTAL).apply {
            background = panel(32f, PANEL, BORDER)
        }
        val trigger = controllerKey("扳机", ControllerReports.TRIGGER, 18f)
        val touchpad = controllerKey("触控板\n滑动 · 轻点", ControllerReports.TOUCHPAD_CLICK, 18f, circle = true)
        val back = controllerKey("返回", ControllerReports.BACK, 16f)
        val oculus = controllerKey("◯  Oculus", ControllerReports.OCULUS, 16f)
        keys = listOf(trigger, touchpad, back, oculus)
        when (controllerLayout) {
            ControllerLayout.BLIND -> {
                controller.addView(oculus, layout(dp(148), dp(60)))
                controller.addView(touchpad, layout(padSize, padSize, top = 16))
                controller.addView(keyRow(back, trigger, secondWeight = 2f), layout(top = 16))
            }
            ControllerLayout.ORIGINAL -> {
                controller.addView(touchpad, layout(padSize, padSize))
                controller.addView(back, layout(dp(148), dp(52), top = 14))
                controller.addView(oculus, layout(dp(148), dp(52), top = 8))
                controller.addView(trigger, layout(height = dp(60), top = 16))
            }
            ControllerLayout.SKETCH -> {
                controller.addView(trigger, layout(height = dp(68)))
                controller.addView(touchpad, layout(padSize, padSize, top = 18))
                controller.addView(keyRow(back, oculus), layout(top = 18))
            }
        }
        body.addView(controller, layout(width))
        return scrolling(body)
    }

    private fun keyRow(first: Button, second: Button, secondWeight: Float = 1f) = LinearLayout(this).apply {
        addView(first, LinearLayout.LayoutParams(0, dp(66), 1f).apply { marginEnd = dp(6) })
        addView(second, LinearLayout.LayoutParams(0, dp(66), secondWeight).apply { marginStart = dp(6) })
    }

    private fun chooseConnectionMode() {
        val titles = modes.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("连接方式")
            .setSingleChoiceItems(titles, modes.indexOf(mode)) { dialog, index ->
                mode = modes[index]
                uiPreferences.edit().putString("connection_mode", mode.name).apply()
                refreshSession()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun chooseLayout() {
        val layouts = ControllerLayout.entries
        AlertDialog.Builder(this)
            .setTitle("按键布局")
            .setSingleChoiceItems(layouts.map { it.title }.toTypedArray(), controllerLayout.ordinal) { dialog, index ->
                releaseControls()
                pages.removeView(controllerPage)
                controllerLayout = layouts[index]
                uiPreferences.edit().putString("controller_layout", controllerLayout.name).apply()
                controllerPage = buildControllerPage().apply { visibility = View.GONE }
                pages.addView(controllerPage)
                refreshSession()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun controllerKey(title: String, mask: Int, size: Float, circle: Boolean = false) =
        ControllerKey(this, mask) { session?.input }.apply {
            text = title
            textSize = size
            styleButton(if (circle) 200f else 18f, KEY, INK)
            setPadding(dp(6), 0, dp(6), 0)
        }

    private fun scrolling(body: View) = ScrollView(this).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        setBackgroundColor(CANVAS)
        addView(body)
    }

    private fun column(horizontal: Int, vertical: Int, alignment: Int = Gravity.START) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = alignment
        setPadding(horizontal, vertical, horizontal, vertical)
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun action(title: String, color: Int, ink: Int) = Button(this).apply {
        text = title
        textSize = 16f
        styleButton(16f, color, ink)
    }

    private fun Button.styleButton(radius: Float, color: Int, ink: Int) {
        isAllCaps = false
        setTextColor(ink)
        setTypeface(typeface, Typeface.BOLD)
        backgroundTintList = null
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x4484e4d2),
            panel(radius, color, BORDER),
            null,
        )
    }

    private fun panel(radius: Float, color: Int, border: Int) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = radius * resources.displayMetrics.density
        setColor(color)
        setStroke(dp(1), border)
    }

    private fun layout(width: Int = -1, height: Int = -2, top: Int = 0) =
        LinearLayout.LayoutParams(width, height).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private enum class ConnectionMode(val title: String) {
        FIXED("固定地址免码（Root）"),
        ROOT("系统地址免码（Root）"),
        STANDARD("通用 BLE（无需 Root）"),
    }

    private enum class ControllerLayout(val title: String) {
        BLIND("盲操 · Oculus 在上"),
        ORIGINAL("原手柄 · 触控板在上"),
        SKETCH("经典 · 扳机在上"),
    }

    private companion object {
        const val NEARBY_DEVICES = 1
        val CANVAS = 0xff10151f.toInt()
        val PANEL = 0xff181f2c.toInt()
        val KEY = 0xff242e3e.toInt()
        val BORDER = 0xff39485b.toInt()
        val INK = 0xfff1f5f9.toInt()
        val MUTED = 0xffb3becd.toInt()
        val ACCENT = 0xff84e4d2.toInt()
    }
}
