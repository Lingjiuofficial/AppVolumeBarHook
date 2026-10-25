package com.miui.appvolumebar.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * 竖向细音量条，观感对齐 HyperLight 的细音量条：
 * 半透明白轨道 + 纯白填充，上下拖动改值。
 *
 * 不走 SeekBar：SeekBar 没有竖向模式，靠 rotation 转会把布局尺寸搞乱。
 */
internal class VerticalVolumeBar(context: Context) : View(context) {

    /** 0f..1f */
    var value: Float = 1f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    var onValueChanged: ((Float) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38FFFFFF }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEBFFFFFF.toInt() }
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = w / 2f

        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, trackPaint)

        val fillH = h * value
        if (fillH > 0.5f) {
            rect.set(0f, h - fillH, w, h)
            canvas.drawRoundRect(rect, r, r, fillPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (height <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                value = 1f - (event.y / height)
                onValueChanged?.invoke(value)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                value = 1f - (event.y / height)
                onValueChanged?.invoke(value)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
