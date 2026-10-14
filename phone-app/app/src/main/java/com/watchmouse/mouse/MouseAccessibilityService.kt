package com.watchmouse.mouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.accessibility.AccessibilityEvent
import com.watchmouse.protocol.MouseCommand

/**
 * 鼠标无障碍服务
 * 通过 AccessibilityService 的 dispatchGesture 模拟鼠标点击、滑动等操作
 */
class MouseAccessibilityService : AccessibilityService() {

    private val tag = "MouseAccessibility"

    // 悬浮光标
    private var floatingCursor: FloatingCursor? = null

    // 滚动累积量（用于节流）
    private var scrollAccumX = 0f
    private var scrollAccumY = 0f
    private var lastScrollTime = 0L

    // Coalesce high-frequency BLE move packets before touching the overlay window.
    // The watch can report more samples than Android can render per frame.
    private val moveLock = Any()
    private var pendingMoveX = 0f
    private var pendingMoveY = 0f
    private var moveDispatchPending = false
    private var moveFramePending = false
    private val moveFrameCallback = Choreographer.FrameCallback {
        moveFramePending = false
        flushPendingMove()
    }

    // 主 Handler
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        // 服务实例单例（供外部调用）
        private var instance: MouseAccessibilityService? = null

        fun getInstance(): MouseAccessibilityService? = instance

        fun isServiceEnabled(): Boolean = instance != null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "无障碍服务创建")
        instance = this

        // 初始化悬浮光标
        floatingCursor = FloatingCursor(this).apply {
            init()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(tag, "无障碍服务已连接")
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要处理事件
    }

    override fun onInterrupt() {
        Log.d(tag, "无障碍服务中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "无障碍服务销毁")
        instance = null
        floatingCursor?.destroy()
        floatingCursor = null
    }

    /**
     * 执行鼠标指令
     */
    fun executeCommand(command: MouseCommand) {
        // BLE GATT callbacks arrive on a Binder thread. Cursor window updates and
        // accessibility gestures must be dispatched from the service's main thread.
        if (command.type == MouseCommand.Type.MOVE) {
            val shouldSchedule = synchronized(moveLock) {
                pendingMoveX += command.dx
                pendingMoveY += command.dy
                if (moveDispatchPending) false else {
                    moveDispatchPending = true
                    true
                }
            }
            if (shouldSchedule) handler.post {
                if (!moveFramePending) {
                    moveFramePending = true
                    Choreographer.getInstance().postFrameCallback(moveFrameCallback)
                }
            }
            return
        }

        handler.post {
            // Preserve ordering: apply any accumulated movement before click/back/home.
            flushPendingMove()
            Log.d(tag, "执行指令: ${command.type}, dx=${command.dx}, dy=${command.dy}")
            when (command.type) {
                MouseCommand.Type.MOVE -> Unit
                MouseCommand.Type.CLICK -> handleClick(command.button)
                MouseCommand.Type.SCROLL -> handleScroll(command.dx, command.dy)
                MouseCommand.Type.BACK -> handleBack()
                MouseCommand.Type.HOME -> handleHome()
                MouseCommand.Type.UNKNOWN -> {}
            }
        }
    }

    private fun flushPendingMove() {
        val movement = synchronized(moveLock) {
            if (!moveDispatchPending) return
            val dx = pendingMoveX
            val dy = pendingMoveY
            pendingMoveX = 0f
            pendingMoveY = 0f
            moveDispatchPending = false
            dx to dy
        }
        if (movement.first != 0f || movement.second != 0f) {
            handleMove(movement.first, movement.second)
        }
    }

    /**
     * 显示光标
     */
    fun showCursor() {
        floatingCursor?.show()
    }

    /**
     * 隐藏光标
     */
    fun hideCursor() {
        floatingCursor?.hide()
    }

    /**
     * 获取光标位置
     */
    fun getCursorPosition(): Pair<Float, Float> {
        return (floatingCursor?.cursorX ?: 0f) to (floatingCursor?.cursorY ?: 0f)
    }

    /**
     * 处理鼠标移动
     */
    private fun handleMove(dx: Float, dy: Float) {
        floatingCursor?.moveBy(dx, dy)
    }

    /**
     * 处理鼠标点击
     */
    private fun handleClick(button: String) {
        val (x, y) = floatingCursor?.getHotspotPosition() ?: return

        if (button == "left") {
            performClick(x, y)
        }
    }

    /**
     * 处理滚动
     */
    private fun handleScroll(dx: Float, dy: Float) {
        // 累积滚动量，达到阈值后执行一次滚动手势
        scrollAccumX += dx
        scrollAccumY += dy

        val now = System.currentTimeMillis()
        // 每 16ms 执行一次滚动（约60fps），或者累积量足够大时
        val threshold = 30f // 滚动阈值
        if (now - lastScrollTime > 16 &&
            (Math.abs(scrollAccumY) > threshold || Math.abs(scrollAccumX) > threshold)
        ) {
            val x = floatingCursor?.cursorX ?: return
            val y = floatingCursor?.cursorY ?: return

            // Previously every threshold crossing became a fixed 80 px swipe,
            // so the watch-side multiplier was effectively discarded. Use a
            // longer gesture and keep its full length even when the cursor is
            // close to a screen edge.
            val maxX = (resources.displayMetrics.widthPixels - 1).coerceAtLeast(1).toFloat()
            val maxY = (resources.displayMetrics.heightPixels - 1).coerceAtLeast(1).toFloat()
            val scrollDistanceX = minOf(160f, maxX * 0.8f)
            val scrollDistanceY = minOf(160f, maxY * 0.8f)
            val scrollDx = if (Math.abs(scrollAccumX) > threshold) {
                if (scrollAccumX > 0) scrollDistanceX else -scrollDistanceX
            } else 0f
            val scrollDy = if (Math.abs(scrollAccumY) > threshold) {
                if (scrollAccumY > 0) scrollDistanceY else -scrollDistanceY
            } else 0f
            val centerX = x.coerceIn(Math.abs(scrollDx) / 2f, maxX - Math.abs(scrollDx) / 2f)
            val centerY = y.coerceIn(Math.abs(scrollDy) / 2f, maxY - Math.abs(scrollDy) / 2f)

            performSwipe(
                centerX - scrollDx / 2, centerY - scrollDy / 2,
                centerX + scrollDx / 2, centerY + scrollDy / 2,
                60
            )

            scrollAccumX = 0f
            scrollAccumY = 0f
            lastScrollTime = now
        }
    }

    /**
     * 处理返回键
     */
    private fun handleBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * 处理 Home 键
     */
    private fun handleHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * 模拟点击
     */
    private fun performClick(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))

        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(tag, "点击完成: ($x, $y)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.d(tag, "点击取消")
            }
        }, null)
    }

    /**
     * 模拟滑动
     */
    private fun performSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        duration: Long
    ) {
        val maxX = (resources.displayMetrics.widthPixels - 1).coerceAtLeast(1).toFloat()
        val maxY = (resources.displayMetrics.heightPixels - 1).coerceAtLeast(1).toFloat()
        val safeStartX = startX.coerceIn(0f, maxX)
        val safeStartY = startY.coerceIn(0f, maxY)
        val safeEndX = endX.coerceIn(0f, maxX)
        val safeEndY = endY.coerceIn(0f, maxY)

        val path = Path().apply {
            moveTo(safeStartX, safeStartY)
            lineTo(safeEndX, safeEndY)
        }

        val gestureBuilder = GestureDescription.Builder()
        try {
            gestureBuilder.addStroke(
                GestureDescription.StrokeDescription(path, 0, duration)
            )
        } catch (e: IllegalArgumentException) {
            Log.e(tag, "忽略无效的滚动手势坐标", e)
            return
        }

        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                // 滑动完成
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                // 滑动取消
            }
        }, null)
    }
}
