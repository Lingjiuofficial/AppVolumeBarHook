package com.miui.appvolumebar.systemui

import android.view.MotionEvent
import android.view.View
import android.view.animation.Interpolator

/**
 * 复刻 HyperLight 的 nudge 弹动：按下时横向挤出，松手（或静置 100ms 后）阻尼回弹。
 *
 * 参数对齐 HyperLight 的 volume_bar_nudge_* 默认值：
 * shift 10dp / 去程 response 0.8 damping 0.2 / 回程 response 1.0 damping 0.35 / 回弹延迟 100ms。
 *
 * 注意：只动 translationX（偏移），不碰 x（位置）与 translationY，
 * 因此不会和 SystemUiHooker 每帧的入场/拉伸同步打架。
 *
 * 安全：本类运行在 SystemUI/plugin 进程，触摸回调抛出未捕获异常会直接把界面打崩，
 * 所以所有入口一律 try-catch 兜底，失败时退化成"没有动画"，绝不冒泡出去。
 */
internal object VolumeNudgeEffect {

    private const val SHIFT_DP = 10f
    private const val SHIFT_RESPONSE_SEC = 0.8f
    private const val SHIFT_DAMPING = 0.2f
    private const val RETURN_RESPONSE_SEC = 1.0f
    private const val RETURN_DAMPING = 0.35f
    private const val RETURN_DELAY_MS = 100L

    fun attach(view: View) {
        try {
            val shift = SHIFT_DP * view.context.resources.displayMetrics.density
            var pendingReturn: Runnable? = null

            // 官方 RingerButtonHelper / Folme 的按压反馈同样挂在 OnTouchListener 上。
            // 直接 setOnTouchListener 会把它顶掉，所以先取出旧监听器做链式调用。
            val previous = previousTouchListener(view)

            fun springTo(target: Float, responseSec: Float, damping: Float) {
                try {
                    view.animate().cancel()
                    view.animate()
                        .translationX(target)
                        .setDuration((responseSec * 1000L).toLong())
                        .setInterpolator(SpringInterpolator(damping, responseSec))
                        .start()
                } catch (_: Throwable) {
                    // 动画起不来不影响点击
                }
            }

            view.setOnTouchListener { v, event ->
                try {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            pendingReturn?.let(v::removeCallbacks)
                            springTo(-shift, SHIFT_RESPONSE_SEC, SHIFT_DAMPING)
                            val ret = Runnable { springTo(0f, RETURN_RESPONSE_SEC, RETURN_DAMPING) }
                            pendingReturn = ret
                            v.postDelayed(ret, RETURN_DELAY_MS)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            pendingReturn?.let(v::removeCallbacks)
                            pendingReturn = null
                            springTo(0f, RETURN_RESPONSE_SEC, RETURN_DAMPING)
                        }
                    }
                } catch (_: Throwable) {
                    // 吞掉：触摸回调抛异常会直接崩掉 SystemUI
                }
                // 交还给官方监听器（Folme 按压反馈等），返回值以它为准
                previous?.onTouch(v, event) ?: false
            }
        } catch (_: Throwable) {
            // 挂载失败就退化成无 nudge，绝不影响按钮本身
        }
    }

    /**
     * 反射读取 View 上已有的 OnTouchListener（Android 没有公开的 getter）。
     * 字段名自 AOSP 起长期稳定；取不到就当作没有，退化成普通的独占监听。
     */
    private fun previousTouchListener(view: View): View.OnTouchListener? = try {
        val info = View::class.java.getDeclaredField("mListenerInfo")
            .apply { isAccessible = true }
            .get(view)
        if (info == null) {
            null
        } else {
            info.javaClass.getDeclaredField("mOnTouchListener")
                .apply { isAccessible = true }
                .get(info) as? View.OnTouchListener
        }
    } catch (_: Throwable) {
        null
    }

    /** 阻尼正弦近似 Spring：damping 越小过冲越明显，与 Folme 的 response/damping 同源手感。 */
    private class SpringInterpolator(
        private val dampingRatio: Float,
        responseSec: Float
    ) : Interpolator {
        private val response = responseSec
        private val omega0 = (2 * Math.PI / responseSec).toFloat()
        private val omegaD = omega0 * kotlin.math.sqrt(1 - dampingRatio * dampingRatio)
        private val decay = dampingRatio * omega0

        override fun getInterpolation(t: Float): Float {
            // 过阻尼/零响应时退化成线性，避免除零出 NaN 把动画打崩
            if (response <= 0f || omegaD <= 0f) return t
            val v = 1f - kotlin.math.exp(-decay * t) *
                (kotlin.math.cos(omegaD * t) + (decay / omegaD) * kotlin.math.sin(omegaD * t))
            return if (v.isFinite()) v else t
        }
    }
}
