package com.watchmouse.mouse

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.watchmouse.R

/**
 * 悬浮鼠标指针
 * 通过 WindowManager 在屏幕上显示一个可移动的鼠标指针图标
 */
class FloatingCursor(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var cursorView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // 鼠标当前位置
    var cursorX: Float = 0f
        private set
    var cursorY: Float = 0f
        private set

    // 屏幕尺寸
    private var screenWidth = 0
    private var screenHeight = 0

    // 是否可见
    var isVisible = false
        private set

    /**
     * 初始化悬浮光标
     */
    fun init() {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 获取屏幕尺寸
        val metrics = context.resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        // 创建光标视图
        cursorView = ImageView(context).apply {
            setImageResource(R.drawable.ic_cursor)
            setBackgroundColor(0x00000000)
        }

        // 初始位置（屏幕中心）
        cursorX = screenWidth / 2f
        cursorY = screenHeight / 2f

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cursorX.toInt()
            y = cursorY.toInt()
        }
    }

    /**
     * 显示光标
     */
    fun show() {
        if (isVisible || cursorView == null || windowManager == null) return
        try {
            windowManager?.addView(cursorView, layoutParams)
            isVisible = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 隐藏光标
     */
    fun hide() {
        if (!isVisible || cursorView == null || windowManager == null) return
        try {
            windowManager?.removeView(cursorView)
            isVisible = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 移动光标（相对位移）
     */
    fun moveBy(dx: Float, dy: Float) {
        moveTo(cursorX + dx, cursorY + dy)
    }

    /** Screen coordinate of the visible arrow tip, used as the click target. */
    fun getHotspotPosition(): Pair<Float, Float> {
        val density = context.resources.displayMetrics.density
        val tipX = cursorX + 4f * density
        val tipY = cursorY + 2f * density
        return tipX.coerceIn(0f, (screenWidth - 1).coerceAtLeast(0).toFloat()) to
            tipY.coerceIn(0f, (screenHeight - 1).coerceAtLeast(0).toFloat())
    }

    /**
     * 移动光标到绝对位置
     */
    fun moveTo(x: Float, y: Float) {
        if (!isVisible || cursorView == null || layoutParams == null) return

        // 限制在屏幕范围内
        cursorX = x.coerceIn(0f, screenWidth.toFloat())
        cursorY = y.coerceIn(0f, screenHeight.toFloat())

        val integerX = cursorX.toInt()
        val integerY = cursorY.toInt()
        layoutParams?.x = integerX
        layoutParams?.y = integerY
        // WindowManager positions are integer pixels; translation preserves the
        // fractional remainder so tiny trackpad movements are visible immediately.
        cursorView?.translationX = cursorX - integerX
        cursorView?.translationY = cursorY - integerY

        try {
            windowManager?.updateViewLayout(cursorView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 将光标移动到屏幕中心
     */
    fun moveToCenter() {
        moveTo(screenWidth / 2f, screenHeight / 2f)
    }

    /**
     * 销毁
     */
    fun destroy() {
        hide()
        cursorView = null
        windowManager = null
    }
}
