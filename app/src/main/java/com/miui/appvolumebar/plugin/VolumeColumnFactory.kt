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
    private const val LAYOUT_COLUMN = "miui_volume_dialog_column"

    /** 缓存反射句柄。 */
    private var ctor: java.lang.reflect.Constructor<*>? = null
    private var initColumn: Method? = null
    private var getView: Method? = null
    private var getSlider: Method? = null
    private var getIcon: Method? = null

    // 手动最小初始化用（绕开 initColumn 里的可疑调用）
    private var setView: Method? = null
    private var setStream: Method? = null
    private var setSlider: Method? = null
    private var setProgressView: Method? = null
    private var setProgressViewBg: Method? = null
    private var setIcon: Method? = null
    private var setSuperVolume: Method? = null
    private var setExpanded: Method? = null

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

            setView = cls.getMethod("setView", View::class.java)
            setStream = cls.getMethod("setStream", Int::class.javaPrimitiveType)
            setSlider = cls.getMethod("setSlider", android.widget.SeekBar::class.java)
            setExpanded = cls.getMethod("setExpanded", Boolean::class.javaPrimitiveType)
            setProgressView = runCatching {
                cls.getMethod("setProgressView", Class.forName(
                    "com.android.systemui.miui.volume.MiuiVolumeSeekBarProgressView", false, loader))
            }.getOrNull()
            setProgressViewBg = runCatching {
                cls.getMethod("setProgressViewBg", Class.forName(
                    "com.android.systemui.miui.volume.RoundRectFrameLayout", false, loader))
            }.getOrNull()
            setIcon = runCatching {
                cls.getMethod("setIcon", android.widget.ImageView::class.java)
            }.getOrNull()
            setSuperVolume = runCatching {
                cls.getMethod("setSuperVolume", android.widget.TextView::class.java)
            }.getOrNull()
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
    /**
     * 手动最小初始化：只做「让柱子能显示、能被拖动」的必要步骤，
     * 跳过 initColumn 里所有可能抛异常的可选初始化：
     *   getSuperVolume().setText(VolumeUtils.getSuperVolumePercent(...))
     *   setSliderTintColorList(...)
     *   updateIconColor()      ← 内部用 MiBlurCompat
     *   sharedSliderRatio()    ← 启动协程
     */
    private fun manualBuild(
        ctx: Context, loader: ClassLoader, container: ViewGroup,
        stream: Int, expanded: Boolean,
    ): BuiltColumn? {
        return try {
            val column = ctor!!.newInstance()
            val res = ctx.resources
            val pkg = ctx.packageName
            val layoutId = res.getIdentifier(LAYOUT_COLUMN, "layout", pkg)
            if (layoutId == 0) {
                HookKit.log("manualBuild: layout id 找不到")
                return null
            }
            val view = android.view.LayoutInflater.from(ctx)
                .inflate(layoutId, container, false)
                ?: run {
                    HookKit.log("manualBuild: inflate 返回 null（资源 id 与 Context 不匹配）")
                    return null
                }

            // 顺序关键：setExpanded 会触发 observable.afterChange → updateIconColor → getIcon()，
            // 而 icon 是 lateinit。所以必须先把子 view 全部塞进去，最后才设 expanded。
            setView?.invoke(column, view)
            setStream?.invoke(column, stream)
            view.id = stream
            view.tag = column

            val slider = view.findViewById<android.widget.SeekBar>(
                res.getIdentifier("volume_column_slider", "id", pkg)
            )
            val icon = view.findViewById<android.widget.ImageView>(
                res.getIdentifier("volume_column_icon", "id", pkg)
            )
            val progress = view.findViewById<View>(
                res.getIdentifier("volume_progress_view", "id", pkg)
            )
            val progressBg = view.findViewById<View>(
                res.getIdentifier("volume_column_view", "id", pkg)
            )
            val superVolume = view.findViewById<android.widget.TextView>(
                res.getIdentifier("miui_super_volume_expanded", "id", pkg)
            )

            if (slider != null) setSlider?.invoke(column, slider)
            if (icon != null) setIcon?.invoke(column, icon)
            if (progress != null && setProgressView != null) {
                runCatching { setProgressView!!.invoke(column, progress) }
            }
            if (progressBg != null && setProgressViewBg != null) {
                runCatching { setProgressViewBg!!.invoke(column, progressBg) }
            }
            if (superVolume != null) setSuperVolume?.invoke(column, superVolume)

            // 现在 icon 已就绪，才可以动 expanded
            runCatching { setExpanded?.invoke(column, expanded) }
                .onFailure { HookKit.log("manualBuild: setExpanded 失败(可忽略) $it") }

            HookKit.log("manualBuild: 成功 slider=${slider != null} icon=${icon != null}")
            BuiltColumn(column, view, slider, icon)
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            HookKit.log("manualBuild 失败: ${cause.javaClass.name}: ${cause.message}")
            HookKit.log(android.util.Log.getStackTraceString(cause).lineSequence().take(6).joinToString("\n"))
            null
        }
    }

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
            // 传 false：匹配 isExpanded 的初始值，避免 initColumn 内部 setExpanded 触发
            // observable → updateIconColor → getIcon() 的 lateinit 崩溃
            initColumn!!.invoke(
                column, ctx, container, stream,
                false,   // important
                false,   // needShowDialog
                false    // expanded ← 先不展开
            )
            val view = getView!!.invoke(column) as? View ?: return null

            // 此时 icon 已初始化，可以安全切到目标展开态
            if (expanded) {
                runCatching { setExpanded?.invoke(column, true) }
                    .onFailure { HookKit.log("build: 补 setExpanded(true) 失败 ${it.cause ?: it}") }
            }

            val slider = runCatching { getSlider!!.invoke(column) as? android.widget.SeekBar }.getOrNull()
            val icon = runCatching { getIcon!!.invoke(column) as? android.widget.ImageView }.getOrNull()
            HookKit.log("VolumeColumnFactory: 造柱成功 view=${view.javaClass.simpleName} slider=$slider")
            BuiltColumn(column, view, slider, icon)
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            HookKit.log("VolumeColumnFactory.initColumn 失败: ${cause.javaClass.name}: ${cause.message}")
            HookKit.log(android.util.Log.getStackTraceString(cause).lineSequence().take(8).joinToString("\n"))
            HookKit.log("VolumeColumnFactory: 降级到手动最小初始化")
            manualBuild(ctx, loader, container, stream, expanded)
        }
    }
}
