package com.miui.appvolumebar.plugin

import android.content.Context
import android.media.AudioManager
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import com.miui.appvolumebar.audio.ActivePlayer
import com.miui.appvolumebar.audio.ActivePlayers
import com.miui.appvolumebar.audio.PlayerVolumeApi
import com.miui.appvolumebar.core.HookKit
import java.lang.ref.WeakReference

/**
 * 把「应用音量柱」插进原生音量面板。
 *
 * 面板结构（实测 dump）：
 *   volume_dialog_column_collapsed (FrameLayout)  ← 折叠态，8 根柱子都在这
 *   volume_dialog_columns          (LinearLayout) ← 展开态，展开时整体搬过来
 *
 * 8 根柱子 = 8 个 streamType（0/2/3/4/5/6/10/11）预创建，只把活跃那根设成可见。
 *
 * 我们的柱子：
 *   1. 用官方 VolumeColumn().initColumn() 造 → 样式/尺寸/动画识别全部一致
 *   2. 只 addView 到「展开态容器」，不进 mColumns → controller 不会覆盖它的数据
 *   3. 折叠/展开时 VolumeColumns 会 reparentChildren 把两边 children 整体搬移，
 *      我们靠 addOnAttachStateChangeListener 跟着切换可见性（attach 到折叠容器就隐藏）
 */
internal object AppVolumeColumn {

    private const val TAG_MARK = "avbh_app_volume_column"

    @Volatile private var ourColumnRef: WeakReference<View>? = null
    @Volatile private var currentPlayer: ActivePlayer? = null
    @Volatile private var installed = false

    /** 入口：面板 View attach 时调用。 */
    fun install(panelView: View, loader: ClassLoader): Boolean {
        if (installed) return true
        return try {
            val ctx = panelView.context
            val pkg = ctx.packageName

            val expandedId = ctx.resources.getIdentifier("volume_dialog_columns", "id", pkg)
            val collapsedId = ctx.resources.getIdentifier("volume_dialog_column_collapsed", "id", pkg)
            if (expandedId == 0 || collapsedId == 0) {
                HookKit.log("AppVolumeColumn: 容器 id 找不到")
                return false
            }

            val root = panelView.rootView ?: panelView
            val expanded = root.findViewById<ViewGroup>(expandedId)
            if (expanded == null) {
                HookKit.log("AppVolumeColumn: 展开态容器不存在")
                return false
            }

            // 关键：官方用的是 controller 的 mContext —— 那是 SystemUI 传给插件的
            // PluginContextWrapper（资源重定向到 plugin）。panelView.context 就是它。
            // 用 createPackageContext 反而不对：inflate 时 R.layout 解析不到。
            val hostCtx = panelView.context

            val built = VolumeColumnFactory.build(
                ctx = hostCtx,
                loader = loader,
                container = expanded,
                stream = AudioManager.STREAM_MUSIC,   // 拿"媒体音量"那套样式
                expanded = true,
            ) ?: return false

            built.view.setTag(TAG_KEY, TAG_MARK)
            expanded.addView(built.view)
            ourColumnRef = WeakReference(built.view)

            // 折叠/展开时 reparent 会走 removeView + addView，正好触发 attach 回调
            built.view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    val parent = v.parent as? ViewGroup
                    val inExpanded = parent?.id == expandedId
                    v.visibility = if (inExpanded) View.VISIBLE else View.GONE
                    if (inExpanded) bindData(built.view, built.slider, built.icon)
                }

                override fun onViewDetachedFromWindow(v: View) {}
            })

            // 首次加在展开容器里，但当前可能是折叠态 → 先藏着，等展开
            built.view.visibility = View.GONE

            installed = true
            HookKit.log(
                "AppVolumeColumn: 已插入 app-volume 柱 " +
                    "(slider=${built.slider != null}, icon=${built.icon != null})"
            )
            true
        } catch (t: Throwable) {
            HookKit.log("AppVolumeColumn.install 失败: $t")
            false
        }
    }

    /** 绑数据：图标 + 当前音量 + 拖动写回。 */
    private fun bindData(view: View, slider: SeekBar?, icon: ImageView?) {
        try {
            val player = ActivePlayers.list(view.context).firstOrNull()
            currentPlayer = player
            if (player == null) {
                view.visibility = View.GONE
                return
            }
            view.visibility = View.VISIBLE

            icon?.setImageDrawable(player.icon)

            val am = view.context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val current = if (am != null) {
                PlayerVolumeApi.getVolume(am, player.key, player.packageName)
            } else null

            slider?.apply {
                max = 100
                progress = ((current ?: 1f) * 100).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val p = currentPlayer ?: return
                        val manager = view.context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                            ?: return
                        PlayerVolumeApi.setVolume(manager, p.config, value / 100f)
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {}

                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }

            HookKit.log(
                "AppVolumeColumn: 绑定 ${player.packageName} " +
                    "key=${player.key} vol=${current ?: "?"}  (${PlayerVolumeApi.status()})"
            )
        } catch (t: Throwable) {
            HookKit.log("AppVolumeColumn.bindData 失败: $t")
        }
    }

    /**
     * View#setTag(int,Object) 要求 key 是资源 id 形态（高字节 >= 2），这里用一个固定值；
     * 关键是**不能碰 view.tag**——那是 VolumeColumn 实例，动画系统靠它认人。
     */
    private const val TAG_KEY = 0x7f0aff01
}
