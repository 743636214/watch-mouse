package com.watchmouse.protocol

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 鼠标指令协议
 *
 * 指令格式（JSON）：
 *   { "type": "move", "dx": 10, "dy": -5 }     // 鼠标相对移动
 *   { "type": "click", "button": "left" }      // 鼠标点击
 *   { "type": "scroll", "dx": 0, "dy": 20 }    // 滚轮滚动
 *   { "type": "back" }                         // 返回键
 *   { "type": "home" }                         // Home 键
 */
data class MouseCommand(
    val type: Type,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val button: String = "left"
) {
    enum class Type {
        MOVE, CLICK, SCROLL, BACK, HOME, UNKNOWN
    }

    companion object {
        private const val COMPACT_MOVE_MARKER = 0x7F
        private const val COMPACT_MOVE_SCALE = 10f

        /** Accept compact five-byte move packets and legacy JSON commands. */
        fun fromBytes(payload: ByteArray): MouseCommand? {
            if (payload.size == 5 && (payload[0].toInt() and 0xFF) == COMPACT_MOVE_MARKER) {
                val values = ByteBuffer.wrap(payload, 1, 4).order(ByteOrder.LITTLE_ENDIAN)
                return MouseCommand(
                    type = Type.MOVE,
                    dx = values.short / COMPACT_MOVE_SCALE,
                    dy = values.short / COMPACT_MOVE_SCALE
                )
            }
            return fromJson(payload.toString(Charsets.UTF_8))
        }

        fun fromJson(jsonStr: String): MouseCommand? {
            return try {
                val json = JSONObject(jsonStr)
                val typeStr = json.optString("type", "").uppercase()
                val type = when (typeStr) {
                    "MOVE" -> Type.MOVE
                    "CLICK" -> Type.CLICK
                    "SCROLL" -> Type.SCROLL
                    "BACK" -> Type.BACK
                    "HOME" -> Type.HOME
                    else -> Type.UNKNOWN
                }
                MouseCommand(
                    type = type,
                    dx = json.optDouble("dx", 0.0).toFloat(),
                    dy = json.optDouble("dy", 0.0).toFloat(),
                    button = json.optString("button", "left")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
