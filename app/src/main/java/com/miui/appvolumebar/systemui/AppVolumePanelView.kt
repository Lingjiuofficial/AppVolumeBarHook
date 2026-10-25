package com.miui.appvolumebar.systemui

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import com.miui.appvolumebar.MainHook

/**
 * 内嵌在原生音量面板里的「应用音量」列表。
 *
 * 关键点：所有 View 都在 SystemUI / plugin 进程里创建，材质走
 * OfficialRingerBackground.apply()（内部即 Util.setMiViewBlurAndBlendColor），
 * 所以 HyperLight 能像对待官方控件一样接管它的玻璃。
 */
internal object AppVolumePanelView {

    const val TAG_PANEL_ROOT = "tag_app_volume_panel_root"

    private const val ROW_HEIGHT_DP = 52f
    private const val ROW_GAP_DP = 8f
    private const val ROW_CORNER_DP = 26f
    private const val ICON_SIZE_DP = 22f
    private const val TRACK_HEIGHT_DP = 6f
    private const val THUMB_SIZE_DP = 14f
    private const val HORIZONTAL_PAD_DP = 14f

    fun create(context: Context): LinearLayout {
        val density = context.resources.displayMetrics.density
        return LinearLayout(context).apply {
            tag = TAG_PANEL_ROOT
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (ROW_GAP_DP / 2 * density).toInt()
                bottomMargin = (ROW_GAP_DP / 2 * density).toInt()
            }
            clipChildren = false
            clipToPadding = false
        }
    }

    /** 按当前活跃应用重建列表；返回是否有内容。 */
    fun refresh(root: LinearLayout, pluginClassLoader: ClassLoader): Boolean {
        try {
            root.removeAllViews()
            val context = root.context
            val density = context.resources.displayMetrics.density
            val apps = AppVolumeController.listActiveApps(context)
            if (apps.isEmpty()) return false

            apps.forEachIndexed { index, app ->
                root.addView(
                    buildRow(context, pluginClassLoader, app, density),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (ROW_HEIGHT_DP * density).toInt()
                    ).apply {
                        topMargin = if (index == 0) 0 else (ROW_GAP_DP * density).toInt()
                    }
                )
            }
            // 重建后高度才真实，补一次材质（官方材质用高度算圆角）
            root.post { reapplyMaterial(root, pluginClassLoader) }
            return true
        } catch (t: Throwable) {
            MainHook.log("AppVolumePanelView.refresh failed", t)
            return false
        }
    }

    private fun buildRow(
        context: Context,
        pluginClassLoader: ClassLoader,
        app: ActiveApp,
        density: Float
    ): FrameLayout {
        val row = FrameLayout(context).apply {
            isClickable = false
            isFocusable = false
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (HORIZONTAL_PAD_DP * density).toInt(),
                0,
                (HORIZONTAL_PAD_DP * density).toInt(),
                0
            )
        }

        val iconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            app.icon?.let { setImageDrawable(it) }
            layoutParams = LinearLayout.LayoutParams(
                (ICON_SIZE_DP * density).toInt(),
                (ICON_SIZE_DP * density).toInt()
            ).apply { marginEnd = (12 * density).toInt() }
        }

        val seekBar = SeekBar(context).apply {
            max = 100
            progress = (AppVolumeController.rememberedVolume(app.packageName) * 100).toInt()
            progressDrawable = thinTrack(density)
            thumb = thinThumb(density)
            thumbOffset = 0
            splitTrack = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    try {
                        AppVolumeController.setAppVolume(app, value / 100f)
                    } catch (t: Throwable) {
                        MainHook.log("setAppVolume failed", t)
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        content.addView(iconView)
        content.addView(seekBar)
        row.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // 胶囊底 + 官方材质（HyperLight 在这里认领 View）
        val radius = ROW_CORNER_DP * density
        row.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radius)
            }
        }
        row.clipToOutline = true
        OfficialRingerBackground.apply(context, row, pluginClassLoader, radius)

        return row
    }

    private fun reapplyMaterial(root: ViewGroup, pluginClassLoader: ClassLoader) {
        try {
            val context = root.context
            val radius = ROW_CORNER_DP * context.resources.displayMetrics.density
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i) ?: continue
                OfficialRingerBackground.apply(context, child, pluginClassLoader, radius)
            }
        } catch (t: Throwable) {
            MainHook.log("AppVolumePanelView.reapplyMaterial failed", t)
        }
    }

    /** 细轨道：6dp 高圆角，未填充白色 22%，已填充白色 92%。 */
    private fun thinTrack(density: Float): LayerDrawable {
        val h = (TRACK_HEIGHT_DP * density).toInt()
        val r = h / 2f
        val bg = GradientDrawable().apply {
            cornerRadius = r
            setColor(0x38FFFFFF)
        }
        val fg = GradientDrawable().apply {
            cornerRadius = r
            setColor(0xEBFFFFFF.toInt())
        }
        return LayerDrawable(arrayOf(bg, fg)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
            setLayerHeight(0, h)
            setLayerHeight(1, h)
        }
    }

    private fun thinThumb(density: Float): GradientDrawable {
        val size = (THUMB_SIZE_DP * density).toInt()
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
            setSize(size, size)
        }
    }
}
