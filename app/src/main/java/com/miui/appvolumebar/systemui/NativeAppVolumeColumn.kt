package com.miui.appvolumebar.systemui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import com.miui.appvolumebar.MainHook
import java.lang.ref.WeakReference

/**
 * 在原生音量面板里插入一根「应用音量」柱。
 *
 * 做法不是自己拼 UI，而是直接复用官方的柱子布局：
 *
 *   VolumeColumn.initView() 里就是
 *   inflate(R.layout.miui_volume_dialog_column) + 5 个 findViewById
 *   （volume_column_slider / volume_progress_view / volume_column_view /
 *     volume_column_icon / miui_super_volume_expanded）
 *
 * 所以我们也 inflate 同一份 layout，然后塞进官方的 VolumeColumns 容器
 * （ViewGroup volume_dialog_column_collapsed / volume_dialog_columns）。
 * 容器负责布局、展开态 reparent、宽度与间距，我们只管数据。
 *
 * 附带好处：HyperLight 改的 miui_volume_column_width / _height / 圆角
 * 都是资源级覆盖，我们这根柱子引用同一份 dimen，自动跟着变细、跟着走动画。
 */
internal object NativeAppVolumeColumn {

    private const val TAG = "NativeAppVolumeColumn"

    private const val LAYOUT_COLUMN = "miui_volume_dialog_column"
    private const val ID_SLIDER = "volume_column_slider"
    private const val ID_ICON = "volume_column_icon"

    private var attachedRef: WeakReference<View>? = null
    private var currentApp: ActiveApp? = null

    /** controller = VolumePanelViewController 实例。 */
    fun attach(controller: Any): Boolean {
        return try {
            val volumeColumns = XposedHelpersBridge.field(controller, "mVolumeColumns") ?: return false
            val parent = XposedHelpersBridge.call(volumeColumns, "getCurrentParent") as? ViewGroup
                ?: return false

            var columnView = attachedRef?.get()
            if (columnView == null || columnView.parent == null) {
                columnView = inflateColumn(parent) ?: return false
                XposedHelpersBridge.call(volumeColumns, "addView", columnView)
                attachedRef = WeakReference(columnView)
                copySizeFromSibling(parent, columnView)
                MainHook.log("$TAG: inserted app-volume column")
            }
            bind(columnView)
            true
        } catch (t: Throwable) {
            MainHook.log("$TAG.attach failed", t)
            false
        }
    }

    /**
     * 官方是在 initColumn() 里按展开/折叠态动态设宽高与 margin 的，
     * 我们只 inflate 会拿到 XML 里的默认值，所以在插入后对齐容器里已有柱子的尺寸。
     */
    private fun copySizeFromSibling(parent: ViewGroup, mine: View) {
        try {
            val template = (0 until parent.childCount)
                .map { parent.getChildAt(it) }
                .firstOrNull { it !== mine } ?: return
            val ref = template.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val lp = mine.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            if (ref.width > 0) lp.width = ref.width
            if (ref.height > 0) lp.height = ref.height
            lp.marginStart = ref.marginStart
            lp.marginEnd = ref.marginEnd
            lp.topMargin = ref.topMargin
            lp.bottomMargin = ref.bottomMargin
            mine.layoutParams = lp
        } catch (_: Throwable) {
        }
    }

    fun detach() {
        try {
            attachedRef?.get()?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
            }
            attachedRef = null
        } catch (_: Throwable) {
        }
    }

    private fun inflateColumn(parent: ViewGroup): View? {
        val context = parent.context
        val res = context.resources
        val pkg = context.packageName
        val layoutId = res.getIdentifier(LAYOUT_COLUMN, "layout", pkg)
        if (layoutId == 0) {
            MainHook.log("$TAG: layout $LAYOUT_COLUMN not found")
            return null
        }
        val view = LayoutInflater.from(context).inflate(layoutId, parent, false)
        view.tag = "tag_app_volume_native_column"
        return view
    }

    private fun bind(columnView: View) {
        val context = columnView.context
        val app = AppVolumeController.listActiveApps(context).firstOrNull()
        currentApp = app

        if (app == null) {
            columnView.visibility = View.GONE
            return
        }
        columnView.visibility = View.VISIBLE

        val res = context.resources
        val pkg = context.packageName

        val iconId = res.getIdentifier(ID_ICON, "id", pkg)
        if (iconId != 0) {
            columnView.findViewById<ImageView>(iconId)?.setImageDrawable(app.icon)
        }

        val sliderId = res.getIdentifier(ID_SLIDER, "id", pkg)
        if (sliderId != 0) {
            columnView.findViewById<SeekBar>(sliderId)?.let { slider ->
                slider.max = 100
                slider.progress = (AppVolumeController.rememberedVolume(app.packageName) * 100).toInt()
                slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val target = currentApp ?: return
                        AppVolumeController.setAppVolume(target, value / 100f)
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {}

                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
        }
    }
}

/** 反射薄封装，避免这里到处散落 try-catch。 */
internal object XposedHelpersBridge {

    fun field(target: Any, name: String): Any? = try {
        val f = (target as Any)::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.get(target)
    } catch (_: Throwable) {
        null
    }

    fun call(target: Any, name: String, vararg args: Any?): Any? = try {
        val method = (target as Any)::class.java.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == args.size
        } ?: return null
        method.isAccessible = true
        method.invoke(target, *args)
    } catch (_: Throwable) {
        null
    }
}
