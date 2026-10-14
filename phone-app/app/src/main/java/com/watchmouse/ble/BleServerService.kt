package com.watchmouse.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.watchmouse.MainActivity
import com.watchmouse.R
import com.watchmouse.mouse.MouseAccessibilityService
import com.watchmouse.protocol.MouseCommand

/**
 * BLE 服务端前台服务
 * 保持 BLE 服务在后台持续运行
 */
class BleServerService : Service() {

    private val tag = "BleServerService"
    private val channelId = "WatchMouseBleService"
    private val notificationId = 1001

    private var bleServerManager: BleServerManager? = null

    companion object {
        private const val ACTION_START = "com.watchmouse.action.START_SERVICE"
        private const val ACTION_STOP = "com.watchmouse.action.STOP_SERVICE"

        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, BleServerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BleServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "BLE 服务创建")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startService()
            ACTION_STOP -> stopService()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "BLE 服务销毁")
        bleServerManager?.stop()
        isRunning = false
    }

    /**
     * 启动服务
     */
    private fun startService() {
        if (isRunning) {
            Log.w(tag, "服务已在运行")
            return
        }

        // 显示前台通知
        startForeground(notificationId, createNotification("正在等待手表连接..."))

        // 初始化并启动 BLE 服务端
        bleServerManager = BleServerManager(this).apply {
            onStateChange = { state ->
                Log.d(tag, "BLE 状态变化: $state")
                updateNotificationState(state, bleServerManager?.lastErrorMessage)
                Handler(Looper.getMainLooper()).post {
                    if (state == BleServerManager.State.CONNECTED) {
                        MouseAccessibilityService.getInstance()?.showCursor()
                    } else {
                        MouseAccessibilityService.getInstance()?.hideCursor()
                    }
                }
            }
            onCommandReceived = { command ->
                handleCommand(command)
            }
        }

        val success = bleServerManager?.start() ?: false
        if (success) {
            isRunning = true
            Log.d(tag, "BLE 服务启动成功")
        } else {
            Log.e(tag, "BLE 服务启动失败")
            stopSelf()
        }
    }

    /**
     * 停止服务
     */
    private fun stopService() {
        bleServerManager?.stop()
        bleServerManager = null
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 处理收到的指令
     */
    private fun handleCommand(command: MouseCommand) {
        // 转发给无障碍服务执行
        MouseAccessibilityService.getInstance()?.executeCommand(command)
    }

    /**
     * 更新通知状态
     */
    private fun updateNotificationState(state: BleServerManager.State, errorMessage: String? = null) {
        val text = when (state) {
            BleServerManager.State.ADVERTISING -> "正在等待手表连接..."
            BleServerManager.State.CONNECTED -> "手表已连接，正在工作"
            BleServerManager.State.ERROR -> errorMessage ?: "服务启动失败；请重新打开手机端 WatchMouse"
            BleServerManager.State.IDLE -> "服务未运行"
        }
        val notification = createNotification(text)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId, notification)
    }

    /**
     * 创建通知
     */
    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("WatchMouse")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_cursor)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "WatchMouse BLE 服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "WatchMouse 手表触控板 BLE 连接服务"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
