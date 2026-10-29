package com.miui.appvolumebar.core

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XposedHelpers

/**
 * 音量面板结构探针。
 *
 * 全部走 OBSERVE 模式：只读、只打日志，**不修改任何行为**。
 * 目的是在动手改之前，先把这几件事用真机数据确认清楚：
 *
 *   1. VolumePanelViewController 里 mColumns 到底有几个、是什么类型
 *   2. VolumeColumns 的两个容器是哪种 ViewGroup、各自 childCount 多少
 *   3. 展开态和折叠态下，柱子的实际宽高是多少（对照资源里那堆 _4stream dimen）
 *   4. showVolumePanelH 的调用参数序列
 *
 * 上一轮崩掉就是因为跳过了这一步，直接往容器里塞 View。
 */
internal object VolumeProbe {

    private const val PKG = "com.android.systemui.miui.volume"

    private var dumpedOnce = false

    @Volatile
    private var lastController: Any? = null

    @Volatile
    private var probeRuns = 0

    fun install(cl: ClassLoader) {
        HookKit.hookAll(
            cl, "$PKG.VolumePanelViewController", "initPanelView",
            "probe.initPanelView", HookKit.Mode.OBSERVE
        ) { param ->
            lastController = param.thisObject
            dumpedOnce = true
            HookKit.log("=== initPanelView returned, panel built ===")

            val ctx = runCatching {
                de.robv.android.xposed.XposedHelpers
                    .getObjectField(param.thisObject, "mContext") as? android.content.Context
            }.getOrNull()
            AudioProbe.run(ctx)
            dumpStructure(param.thisObject)
        }

        HookKit.hookAll(
            cl, "$PKG.VolumePanelViewController", "showVolumePanelH",
            "probe.showVolumePanelH", HookKit.Mode.OBSERVE
        ) { param ->
            // 这个方法是每次弹出面板都调用的，比 initPanelView（一辈子一次）可靠得多
            if (probeRuns >= 2) return@hookAll
            HookKit.log("=== showVolumePanelH, 第 ${probeRuns + 1} 次探测 ===")
            HookKit.log("args=${param.args?.joinToString(",")}")

            val ctx = runCatching {
                de.robv.android.xposed.XposedHelpers
                    .getObjectField(param.thisObject, "mContext") as? android.content.Context
            }.getOrNull()
            AudioProbe.run(ctx)
            dumpStructure(param.thisObject)

            probeRuns++
            if (probeRuns == 2) HookKit.log("探测已跑满 2 次，后续不再打印")
        }

        HookKit.hookAll(
            cl, "$PKG.MiuiRingerModeLayout", "updateExpandedH",
            "probe.ringerExpanded", HookKit.Mode.OBSERVE
        ) { param ->
            HookKit.log("MiuiRingerModeLayout.updateExpandedH args=${param.args?.joinToString(",")}")
        }

    }

    private fun dumpStructure(controller: Any?) {
        try {
            val ctrl = controller ?: lastController ?: return

            val columns = field(ctrl, "mColumns") as? List<*>
            HookKit.log("mColumns: size=${columns?.size ?: "null"}")
            columns?.forEachIndexed { i, col ->
                if (col == null) return@forEachIndexed
                val view = runCatching { XposedHelpers.callMethod(col, "getView") as? View }.getOrNull()
                val stream = runCatching { XposedHelpers.callMethod(col, "getStream") }.getOrNull()
                HookKit.log(
                    "  column[$i] class=${col.javaClass.simpleName} stream=$stream " +
                        "view=${view?.javaClass?.simpleName} size=${view?.width}x${view?.height} " +
                        "vis=${view?.visibility} lp=${view?.layoutParams?.width}x${view?.layoutParams?.height}"
                )
            }

            val vc = field(ctrl, "mVolumeColumns")
            HookKit.log("mVolumeColumns: ${vc?.javaClass?.simpleName}")
            listOf("mColumnsCollapsed", "mColumnsExpanded").forEach { name ->
                val group = field(vc, name) as? ViewGroup ?: return@forEach
                HookKit.log(
                    "  $name: ${group.javaClass.name} children=${group.childCount} " +
                        "size=${group.width}x${group.height} vis=${group.visibility}"
                )
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i)
                    HookKit.log(
                        "    child[$i] ${child?.javaClass?.simpleName} " +
                            "size=${child?.width}x${child?.height} " +
                            "lp=${child?.layoutParams?.width}x${child?.layoutParams?.height}"
                    )
                }
            }
        } catch (t: Throwable) {
            HookKit.log("dumpStructure failed: $t")
        }
    }

    private fun field(target: Any?, name: String): Any? {
        if (target == null) return null
        return runCatching {
            XposedHelpers.getObjectField(target, name)
        }.getOrNull()
    }
}
