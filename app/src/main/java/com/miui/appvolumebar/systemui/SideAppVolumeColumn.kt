package com.miui.appvolumebar.systemui

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.miui.appvolumebar.MainHook

/**
 * 贴在原生音量面板旁边的一根独立应用音量柱，高度与面板平齐。
 *
 * 为什么不塞进面板里：面板折叠态只有按钮那么宽（~90px），横向滑条放不下；
 * 展开态又被 4 根柱子占满。所以单独开一条，宽度和位置自己说了算。
 *
 * 定位方式：挂到窗口根布局（DecorView 是 FrameLayout）上，用 margin 绝对定位，
 * 再监听锚点（原生面板）的布局变化实时跟随——不依赖父容器的类型。
 */
internal object SideAppVolumeColumn {

    const val TAG = "tag_app_volume_side_column"

    private const val WIDTH_DP = 40f
    private const val GAP_DP = 14f
    private const val ICON_DP = 22f
    private const val CORNER_DP = 20f

    private var currentPackage: String? = null

    /** 挂载并刷新；返回是否可见。 */
    fun attach(anchor: View, pluginClassLoader: ClassLoader): Boolean {
        return try {
            val root = anchor.rootView as? ViewGroup ?: return false
            var column = root.findViewWithTag<FrameLayout>(TAG)
            if (column == null) {
                column = create(anchor.context)
                root.addView(column)
                anchor.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    position(column, anchor)
                }
            }
            position(column, anchor)
            refresh(column, pluginClassLoader)
        } catch (t: Throwable) {
            MainHook.log("SideAppVolumeColumn.attach failed", t)
            false
        }
    }

    fun detach(anchor: View) {
        try {
            val root = anchor.rootView as? ViewGroup ?: return
            root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }
        } catch (_: Throwable) {
        }
    }

    private fun create(context: Context): FrameLayout {
        val density = context.resources.displayMetrics.density
        val iconSize = (ICON_DP * density).toInt()

        val column = FrameLayout(context).apply {
            tag = TAG
            visibility = View.GONE
        }

        // 竖向滑条铺满整根柱子
        val bar = VerticalVolumeBar(context).apply {
            tag = "tag_side_bar"
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            onValueChanged = { value ->
                val app = currentApp
                if (app != null) AppVolumeController.setAppVolume(app, value)
            }
        }

        val icon = ImageView(context).apply {
            tag = "tag_side_icon"
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (8 * density).toInt()
            }
        }

        column.addView(bar)
        column.addView(icon)
        return column
    }

    private var currentApp: ActiveApp? = null

    private fun refresh(column: FrameLayout, pluginClassLoader: ClassLoader): Boolean {
        val context = column.context
        val apps = AppVolumeController.listActiveApps(context)
        val app = apps.firstOrNull()
        currentApp = app

        if (app == null) {
            column.visibility = View.GONE
            return false
        }

        column.findViewWithTag<ImageView>("tag_side_icon")?.let { icon ->
            icon.setImageDrawable(app.icon)
        }
        column.findViewWithTag<VerticalVolumeBar>("tag_side_bar")?.let { bar ->
            bar.value = AppVolumeController.rememberedVolume(app.packageName)
        }

        val density = context.resources.displayMetrics.density
        val radius = CORNER_DP * density
        column.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radius)
            }
        }
        column.clipToOutline = true

        // 包名变了才重上材质（HyperLight 那边每次调用都会 apply 一遍）
        if (currentPackage != app.packageName) {
            currentPackage = app.packageName
            OfficialRingerBackground.apply(context, column, pluginClassLoader, radius)
        }

        column.visibility = View.VISIBLE
        return true
    }

    /** 贴到锚点左侧；左边放不下就贴右侧。 */
    private fun position(column: View, anchor: View) {
        try {
            val root = column.parent as? ViewGroup ?: return
            if (anchor.width <= 0 || anchor.height <= 0) return
            val density = column.context.resources.displayMetrics.density
            val w = (WIDTH_DP * density).toInt()
            val gap = (GAP_DP * density).toInt()

            val rect = Rect()
            root.offsetDescendantRectToMyCoords(anchor, rect)

            val lp = (column.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(w, rect.height())
            lp.width = w
            lp.height = rect.height()
            lp.leftMargin = if (rect.left - gap - w >= 0) rect.left - gap - w else rect.right + gap
            lp.topMargin = rect.top
            column.layoutParams = lp
        } catch (t: Throwable) {
            MainHook.log("SideAppVolumeColumn.position failed", t)
        }
    }
}
