package com.miui.appvolumebar.plugin

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.miui.appvolumebar.core.HookKit
import java.lang.reflect.Method

/**
 * 造一根「跟官方一模一样」的音量柱。
 *
 * 官方配方（plugin 反编译 VolumeColumn.java:571）：
 *
 *   public final void initColumn(Context ctx, ViewGroup parent, int stream,
 *                                boolean important, boolean needShowDialog, boolean expanded)
 *
 * 内部做的事：
 *   inflate(R.layout.miui_volume_dialog_column, parent, false)
 *   setId(stream)           ← view 的 id 就是 streamType
 *   setTag(this)            ← tag 是 VolumeColumn 实例（动画系统靠这个认人）
 *   找 5 个子 view 并绑定
 *
 * 用它的好处：尺寸、圆角、配色、drawable 全部跟旁边 4 根一致，
 * HyperLight 改的 dimen 也会自动跟随。
 */
internal object VolumeColumnFactory {

    private const val VOLUME_COLUMN = "com.android.systemui.miui.volume.VolumeColumn"

    /** 缓存反射句柄。 */
    private var ctor: java.lang.reflect.Constructor<*>? = null
    private var initColumn: Method? = null
    private var getView: Method? = null
    private var getSlider: Method? = null
    private var getIcon: Method? = null

    private fun ensure(loader: ClassLoader): Boolean {
        if (ctor != null && initColumn != null) return true
        return try {
            val cls = Class.forName(VOLUME_COLUMN, false, loader)
            ctor = cls.getDeclaredConstructor().apply { isAccessible = true }
            initColumn = cls.getDeclaredMethod(
                "initColumn",
                Context::class.java,
                ViewGroup::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }
            getView = cls.getMethod("getView")
            getSlider = cls.getMethod("getSlider")
            getIcon = cls.getMethod("getIcon")
            true
        } catch (t: Throwable) {
            HookKit.log("VolumeColumnFactory: 反射准备失败 $t")
            false
        }
    }

    class BuiltColumn(
        val instance: Any,
        val view: View,
        val slider: android.widget.SeekBar?,
        val icon: android.widget.ImageView?,
    )

    /**
     * @param stream    占位用的 streamType。用 STREAM_MUSIC(3) 可以得到"媒体音量样式"的柱子；
     *                  它不进 mColumns，所以 controller 不会来改它的值。
     * @param expanded  当前是否展开态（影响内部尺寸/圆角选择）
     */
    fun build(
        ctx: Context,
        loader: ClassLoader,
        container: ViewGroup,
        stream: Int = 3,
        expanded: Boolean = true,
    ): BuiltColumn? {
        if (!ensure(loader)) return null
        return try {
            val column = ctor!!.newInstance()
            initColumn!!.invoke(
                column, ctx, container, stream,
                false,   // important
                false,   // needShowDialog
                expanded
            )
            val view = getView!!.invoke(column) as? View ?: return null
            val slider = runCatching { getSlider!!.invoke(column) as? android.widget.SeekBar }.getOrNull()
            val icon = runCatching { getIcon!!.invoke(column) as? android.widget.ImageView }.getOrNull()
            HookKit.log("VolumeColumnFactory: 造柱成功 view=${view.javaClass.simpleName} slider=$slider")
            BuiltColumn(column, view, slider, icon)
        } catch (t: Throwable) {
            HookKit.log("VolumeColumnFactory.build 失败: $t")
            null
        }
    }
}
