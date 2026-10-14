package com.watchmouse.watch

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Native Android version for Android-based watch firmware. */
class WatchMouseActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceUuid = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val characteristicUuid = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val permissionsRequest = 40
    private val sensitivityLevels = listOf(2f, 4f, 6f, 8f, 10f)

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var inputCharacteristic: BluetoothGattCharacteristic? = null
    private var scanCallback: ScanCallback? = null
    private var scanning = false
    private var scanResultCount = 0
    private var connected = false
    private var disconnectRequested = false
    private var connectTimeout: Runnable? = null
    private val nearbyBleDevices = linkedMapOf<String, String>()
    private var connectButton: Button? = null
    private var sensitivityButton: Button? = null
    private var statusLabel: TextView? = null
    private var pad: TouchpadView? = null
    private var sensitivityIndex = 3
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f
    private var totalX = 0f
    private var totalY = 0f
    private var gestureStartedAt = 0L
    private var gesturePointers = 0
    private var moved = false
    private var gestureHadMultiplePointers = false
    private var gestureStartedAtEdge = false
    private var lastBackPressedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.rgb(7, 13, 24)
        window.navigationBarColor = Color.BLACK
        bluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val savedSensitivity = getPreferences(MODE_PRIVATE).getFloat("cursor_sensitivity_v2", 8f)
        sensitivityIndex = sensitivityLevels.indices.minByOrNull {
            abs(sensitivityLevels[it] - savedSensitivity)
        } ?: 3
        buildUi()
        enterImmersiveMode()
        ensurePermissions()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(7, 13, 24))
        }

        pad = TouchpadView(this).apply {
            setBackgroundColor(Color.rgb(18, 31, 50))
            setOnTouchListener { _, event -> handleTouch(event) }
        }
        root.addView(pad, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setBackgroundColor(0xCC0B1728.toInt())
        }
        val title = TextView(this).apply {
            text = "WatchMouse"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        header.addView(title, LinearLayout.LayoutParams(-1, dp(26)))
        statusLabel = TextView(this).apply {
            text = "未连接手机"
            textSize = 11f
            setTextColor(Color.rgb(163, 181, 203))
            gravity = Gravity.CENTER
        }
        header.addView(statusLabel, LinearLayout.LayoutParams(-1, dp(22)))
        root.addView(header, FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(3), dp(6), dp(3))
            setBackgroundColor(0xCC0B1728.toInt())
        }
        connectButton = Button(this).apply {
            text = "连接手机"
            textSize = 11f
            setOnClickListener {
                if (connected) disconnect() else startScan()
            }
        }
        buttons.addView(connectButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            setMargins(0, 0, dp(3), 0)
        })
        val homeButton = Button(this).apply {
            text = "主页"
            textSize = 11f
            isEnabled = false
            tag = "home"
            setOnClickListener { sendCommand("home") }
        }
        buttons.addView(homeButton, LinearLayout.LayoutParams(0, dp(40), 0.72f).apply {
            setMargins(dp(3), 0, dp(3), 0)
        })
        sensitivityButton = Button(this).apply {
            textSize = 10f
            updateSensitivityButton()
            setOnClickListener {
                sensitivityIndex = (sensitivityIndex + 1) % sensitivityLevels.size
                getPreferences(MODE_PRIVATE).edit()
                    .putFloat("cursor_sensitivity_v2", sensitivityLevels[sensitivityIndex])
                    .apply()
                updateSensitivityButton()
            }
        }
        buttons.addView(sensitivityButton, LinearLayout.LayoutParams(0, dp(40), 1.28f).apply {
            setMargins(dp(3), 0, 0, 0)
        })
        updateSensitivityButton()
        buttons.tag = homeButton
        root.addView(buttons, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
            bottomMargin = dp(18)
        })
        setContentView(root)
        protectTouchpadEdges()
    }

    /** Keep system edge-back swipes from stealing gestures intended for the touchpad. */
    private fun protectTouchpadEdges() {
        if (Build.VERSION.SDK_INT < 29) return
        pad?.post {
            val touchpad = pad ?: return@post
            val width = touchpad.width
            val height = touchpad.height
            if (width <= 0 || height <= 0) return@post
            val edgeWidth = dp(36).coerceAtMost(width / 2)
            touchpad.systemGestureExclusionRects = listOf(
                Rect(0, 0, edgeWidth, height),
                Rect(width - edgeWidth, 0, width, height)
            )
        }
    }

    private fun updateSensitivityButton() {
        val multiplier = sensitivityLevels[sensitivityIndex]
        val display = if (multiplier % 1f == 0f) multiplier.toInt().toString() else multiplier.toString()
        sensitivityButton?.text = "灵敏度 ${display}×"
    }

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        val legacyFlags = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        window.decorView.systemUiVisibility = legacyFlags
        if (Build.VERSION.SDK_INT >= 30) {
            window.decorView.post {
                window.insetsController?.let { controller ->
                    controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
    }

    private fun ensurePermissions() {
        val needed = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), permissionsRequest)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionsRequest) {
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                showFailure("蓝牙权限未授予", "在系统弹窗中允许 WatchMouse 使用附近设备/蓝牙权限，然后重新点击连接。")
            } else {
                showFailure("权限已允许", "请重新点击“连接手机”开始扫描。")
            }
        }
    }

    private fun startScan() {
        disconnectRequested = false
        ensurePermissions()
        val needed = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            showFailure("缺少蓝牙权限", "允许 WatchMouse 使用附近设备/蓝牙权限后，再点击连接。")
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null) {
            showFailure("手表不支持蓝牙", "当前设备没有可用的 BluetoothAdapter，无法连接手机。")
            return
        }
        if (!adapter.isEnabled) {
            showFailure("手表蓝牙已关闭", "先在手表快捷设置或系统设置中打开蓝牙，再点连接手机。")
            return
        }
        val scanner = try { bluetoothAdapter?.bluetoothLeScanner } catch (_: SecurityException) { null }
        if (scanner == null) {
            showFailure("无法启动 BLE 扫描", "确认手表蓝牙已打开、附近设备权限已授予；也可关闭再打开手表蓝牙后重试。")
            return
        }

        statusLabel?.text = "正在查找手机 WatchMouse…"
        pad?.setDiagnostic("正在扫描手机 BLE 服务…\n\n请确认手机端 WatchMouse 显示“等待手表连接”，并保持手机蓝牙开启。")
        nearbyBleDevices.clear()
        scanResultCount = 0
        connectButton?.isEnabled = false
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                scanResultCount++
                val device = result.device ?: return
                val record = result.scanRecord
                val advertisesService = record?.serviceUuids
                    ?.any { it.uuid == serviceUuid } == true || record?.serviceData
                    ?.keys?.any { it.uuid == serviceUuid } == true
                val deviceName = record?.deviceName
                    ?: try { device.name } catch (_: SecurityException) { null }
                // Some watch BLE stacks don't expose the advertised UUID consistently,
                // so also accept the app name returned in the scan response.
                val isWatchMouse = advertisesService || deviceName.equals("WatchMouse", ignoreCase = true)
                if (!isWatchMouse) {
                    val label = deviceName?.takeIf { it.isNotBlank() }
                        ?: "设备…${device.address.takeLast(5)}"
                    nearbyBleDevices[device.address] = "$label（${result.rssi} dBm）"
                    return
                }
                stopScan()
                connectButton?.text = "正在连接…"
                statusLabel?.text = "已发现手机，正在连接…"
                pad?.setDiagnostic("已发现手机广播，正在建立 GATT 连接…")
                connectButton?.isEnabled = false
                armConnectTimeout()
                try {
                    val gatt = if (Build.VERSION.SDK_INT >= 23) {
                        device.connectGatt(this@WatchMouseActivity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    } else {
                        device.connectGatt(this@WatchMouseActivity, false, gattCallback)
                    }
                    if (gatt == null) {
                        cancelConnectTimeout()
                        showFailure("系统未创建 GATT 连接", "手表蓝牙栈没有返回连接对象。关闭手表和手机蓝牙后重试。")
                    } else {
                        bluetoothGatt = gatt
                    }
                } catch (e: SecurityException) {
                    cancelConnectTimeout()
                    showFailure("缺少蓝牙连接权限", "到手表设置中允许 WatchMouse 使用附近设备/蓝牙权限，然后重新连接。")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                stopScan()
                showFailure("BLE 扫描失败（错误码 $errorCode）", scanErrorHint(errorCode))
            }
        }
        scanCallback = callback
        scanning = true
        try {
            scanner.startScan(
                emptyList(),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback
            )
        } catch (e: SecurityException) {
            stopScan()
            showFailure("缺少蓝牙扫描权限", "到手表系统设置允许 WatchMouse 使用附近设备/蓝牙权限。")
            return
        }
        mainHandler.postDelayed({
            if (scanning) {
                stopScan()
                val devices = nearbyBleDevices.values.take(5)
                val explanation = if (devices.isEmpty()) {
                    "扫描请求已运行 15 秒，但 WatchMouse 应用收到 $scanResultCount 条扫描结果回调。若为 0，通常是手表系统没有把 BLE 扫描结果交给应用：检查附近设备权限，关闭再打开手表蓝牙后重试。手机端还需显示“等待手表连接”。"
                } else {
                    "手表应用已收到 $scanResultCount 条扫描结果，附近能看到：${devices.joinToString("、")}。但没有匹配到 WatchMouse 服务 UUID FFE0 或名称。确认手机端显示“等待手表连接”，然后停止并重新开启手机服务。"
                }
                showFailure("扫描超时：没有发现 WatchMouse 手机服务", explanation)
            }
        }, 15_000)
    }

    private fun stopScan() {
        val callback = scanCallback
        try { if (scanning && callback != null) bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: Exception) { }
        scanCallback = null
        scanning = false
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== bluetoothGatt) return
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    try { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: SecurityException) { }
                    try { gatt.requestMtu(185) } catch (_: SecurityException) { }
                    try { gatt.discoverServices() } catch (_: SecurityException) { updateState(false, "无法读取蓝牙服务") }
                }
                newState == BluetoothProfile.STATE_CONNECTING -> runOnUiThread {
                    statusLabel?.text = "正在连接手机…"
                    pad?.setDiagnostic("手机已发现，正在建立蓝牙连接…")
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    gatt.close()
                    if (bluetoothGatt === gatt) bluetoothGatt = null
                    inputCharacteristic = null
                    cancelConnectTimeout()
                    if (disconnectRequested) {
                        disconnectRequested = false
                        updateState(false, "已断开手机")
                    } else {
                        showFailure(
                            if (status == BluetoothGatt.GATT_SUCCESS) "手机端中断了蓝牙连接" else "蓝牙连接失败（状态码 $status）",
                            gattStatusHint(status)
                        )
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== bluetoothGatt) return
            val service: BluetoothGattService? = gatt.getService(serviceUuid)
            val characteristic = service?.getCharacteristic(characteristicUuid)
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                cancelConnectTimeout()
                inputCharacteristic = characteristic
                updateState(true, "已连接手机 · 可以操作")
            } else {
                bluetoothGatt = null
                try { gatt.disconnect() } catch (_: SecurityException) { }
                try { gatt.close() } catch (_: Exception) { }
                cancelConnectTimeout()
                showFailure(
                    if (status != BluetoothGatt.GATT_SUCCESS) "读取手机 BLE 服务失败（状态码 $status）" else "已连接，但手机没有提供 WatchMouse 服务",
                    if (status != BluetoothGatt.GATT_SUCCESS) gattStatusHint(status) else "手表需要服务 FFE0 和特征值 FFE1。确认手机端安装并启动的是新版 WatchMouse，再停止并重新开启手机端服务。"
                )
            }
        }
    }

    private fun disconnect() {
        disconnectRequested = true
        cancelConnectTimeout()
        stopScan()
        try { bluetoothGatt?.disconnect() } catch (_: SecurityException) { }
        try { bluetoothGatt?.close() } catch (_: Exception) { }
        bluetoothGatt = null
        inputCharacteristic = null
        updateState(false, "未连接手机")
    }

    private fun armConnectTimeout() {
        cancelConnectTimeout()
        val timeout = Runnable {
            if (!connected) {
                val gatt = bluetoothGatt
                bluetoothGatt = null
                inputCharacteristic = null
                try { gatt?.disconnect() } catch (_: SecurityException) { }
                try { gatt?.close() } catch (_: Exception) { }
                showFailure("连接手机超时", "手机可能没有接受连接，或 BLE/GATT 服务没有响应。重启手机端服务、关闭其他占用 BLE 的应用后再试。")
            }
        }
        connectTimeout = timeout
        mainHandler.postDelayed(timeout, 15_000)
    }

    private fun cancelConnectTimeout() {
        connectTimeout?.let(mainHandler::removeCallbacks)
        connectTimeout = null
    }

    private fun showFailure(title: String, explanation: String) {
        updateState(false, title)
        runOnUiThread {
            pad?.setDiagnostic("$title\n\n$explanation")
            Toast.makeText(this, title, Toast.LENGTH_LONG).show()
        }
    }

    private fun scanErrorHint(code: Int): String = when (code) {
        1 -> "扫描已在运行。返回页面后重新点击连接；必要时重启手表蓝牙。"
        2 -> "系统无法为应用注册扫描。检查附近设备权限，重启 WatchMouse 后再试。"
        3 -> "手表蓝牙系统服务内部错误。切换一次蓝牙开关后重试。"
        4 -> "当前手表蓝牙不支持 BLE 扫描，或固件限制了扫描功能。"
        5 -> "手表蓝牙扫描资源不足。关闭其他蓝牙扫描应用后重试。"
        6 -> "扫描过于频繁。等待几秒后再试。"
        else -> "系统返回扫描错误码 $code。检查蓝牙和附近设备权限，再重启手表蓝牙。"
    }

    private fun gattStatusHint(code: Int): String = when (code) {
        BluetoothGatt.GATT_SUCCESS -> "连接在完成服务初始化前结束。确认手机端服务仍在运行，然后重启双方蓝牙再连接。"
        8 -> "GATT 操作超时。手机端重启服务，手表靠近手机后重试。"
        19 -> "对端主动断开连接。确认手机 WatchMouse 服务仍在运行，并关闭手机省电限制。"
        22 -> "本机蓝牙栈主动断开。关闭再打开手表蓝牙后重试。"
        133 -> "Android 通用 GATT 错误（133），通常表示连接握手失败或旧连接未释放。停止手机服务，双方蓝牙各关闭再打开，然后先启动手机服务再连接。"
        else -> "系统返回 GATT 状态码 $code。重启手机端服务及手表蓝牙后重试。"
    }

    private fun updateState(isConnected: Boolean, message: String) {
        runOnUiThread {
            connected = isConnected
            statusLabel?.text = message
            connectButton?.apply {
                isEnabled = true
                text = if (isConnected) "断开连接" else "连接手机"
            }
            val home = (connectButton?.parent as? LinearLayout)?.tag as? Button
            home?.isEnabled = isConnected
            pad?.setConnected(isConnected)
        }
    }

    private fun sendCommand(type: String, dx: Float = 0f, dy: Float = 0f) {
        val gatt = bluetoothGatt ?: return
        val characteristic = inputCharacteristic ?: return
        val bytes = if (type == "move") {
            // Compact, MTU-23-safe packet: marker + signed dx/dy in tenths of a pixel.
            ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                .put(0x7F.toByte())
                .putShort((dx * 10f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                .putShort((dy * 10f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                .array()
        } else {
            JSONObject().apply {
                put("type", type)
                if (type == "scroll") {
                    put("dx", dx.toDouble())
                    put("dy", dy.toDouble())
                }
                if (type == "click") put("button", "left")
            }.toString().toByteArray(Charsets.UTF_8)
        }
        val writeType = if (type == "move" || type == "scroll") {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            // Control actions are infrequent and must not be dropped behind move traffic.
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(characteristic, bytes, writeType)
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                characteristic.value = bytes
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (_: SecurityException) {
            updateState(false, "蓝牙连接权限已关闭")
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (!connected) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginGesture(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                gesturePointers = event.pointerCount
                gestureHadMultiplePointers = true
                lastX = event.getX(0)
                lastY = event.getY(0)
            }
            MotionEvent.ACTION_MOVE -> {
                val count = event.pointerCount
                if (count != gesturePointers) {
                    gesturePointers = count
                    lastX = event.getX(0)
                    lastY = event.getY(0)
                    return true
                }
                val x = event.getX(0)
                val y = event.getY(0)
                val dx = x - lastX
                val dy = y - lastY
                totalX += dx
                totalY += dy
                // Keep sub-dp movement; the phone cursor preserves fractional pixels.
                if (dx != 0f || dy != 0f) {
                    // Separate precision tracking from tap-vs-drag classification:
                    // tiny finger jitter must not cancel a tap.
                    if (hypot(totalX, totalY) > dp(14)) moved = true
                    val sensitivity = if (count >= 2) 4.0f else sensitivityLevels[sensitivityIndex]
                    sendCommand(if (count >= 2) "scroll" else "move", dx * sensitivity, dy * sensitivity)
                }
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                gesturePointers = (event.pointerCount - 1).coerceAtLeast(1)
                val remainingIndex = if (event.actionIndex == 0 && event.pointerCount > 1) 1 else 0
                lastX = event.getX(remainingIndex)
                lastY = event.getY(remainingIndex)
            }
            MotionEvent.ACTION_UP -> {
                val duration = System.currentTimeMillis() - gestureStartedAt
                if (gesturePointers == 1 && !gestureHadMultiplePointers && !moved && duration < 800 && hypot(totalX, totalY) < dp(16)) {
                    sendCommand("click")
                } else if (gesturePointers == 1 && !gestureStartedAtEdge && duration < 300 && abs(totalX) > dp(50) && abs(totalX) > abs(totalY) * 1.5f) {
                    sendCommand("back")
                }
                gesturePointers = 0
                moved = false
                gestureHadMultiplePointers = false
                gestureStartedAtEdge = false
            }
            MotionEvent.ACTION_CANCEL -> {
                gesturePointers = 0
                moved = false
                gestureHadMultiplePointers = false
                gestureStartedAtEdge = false
            }
        }
        return true
    }

    private fun beginGesture(event: MotionEvent) {
        gestureStartedAt = System.currentTimeMillis()
        gesturePointers = event.pointerCount
        startX = event.getX(0)
        startY = event.getY(0)
        val edgeWidth = dp(36).toFloat()
        val touchpadWidth = (pad?.width ?: 0).toFloat()
        gestureStartedAtEdge = startX <= edgeWidth || startX >= touchpadWidth - edgeWidth
        lastX = startX
        lastY = startY
        totalX = 0f
        totalY = 0f
        moved = false
        gestureHadMultiplePointers = false
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val now = System.currentTimeMillis()
        if (now - lastBackPressedAt <= 2_000L) {
            disconnect()
            super.onBackPressed()
        } else {
            lastBackPressedAt = now
            Toast.makeText(this, "再按一次返回键退出 WatchMouse", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private inner class TouchpadView(context: android.content.Context) : TextView(context) {
        private var isReady = false
        init {
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.rgb(148, 170, 195))
            text = "全屏触控板\n\n连接手机后，单指滑动移动光标\n轻点点击 · 双指滚动 · 左右快滑返回\n下方按钮可切换光标灵敏度"
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        fun setConnected(value: Boolean) {
            isReady = value
            text = if (isReady) "触控板已就绪\n\n单指滑动：移动光标\n轻点：点击 · 双指滑动：滚动\n左右快滑：返回\n下方可调整光标灵敏度" else "全屏触控板\n\n连接手机后，单指滑动移动光标\n轻点点击 · 双指滚动 · 左右快滑返回\n下方按钮可切换光标灵敏度"
            setTextColor(if (isReady) Color.rgb(88, 224, 241) else Color.rgb(148, 170, 195))
        }

        fun setDiagnostic(message: String) {
            if (isReady) return
            text = message
            textSize = 13f
            setTextColor(Color.rgb(255, 170, 120))
        }
    }
}
