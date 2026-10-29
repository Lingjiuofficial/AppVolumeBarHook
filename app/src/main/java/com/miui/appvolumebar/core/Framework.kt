package com.miui.appvolumebar.core

import android.content.Context
import android.os.Process
import android.view.View
import de.robv.android.xposed.XposedHelpers

/**
 * 三个系统进程的统一接入点。
 *
 * 关键点（真机自检得出的）：音量面板的类不在 SystemUI 自己的 loader 里，
 * 而在 miui.systemui.plugin 这个插件包的 loader 里。所以这里先「解析 loader」，
 * 解析不到就挂 loadPlugin 等它加载完——这是之前所有失败的总根源。
 */
internal object Framework {

    const val PROCESS_SYSTEMUI = "com.android.systemui"
    const val PROCESS_PLUGIN = "miui.systemui.plugin"
    const val PROCESS_MISOUND = "com.miui.misound"

    private const val PLUGIN_INSTANCE_CLASS = "com.android.systemui.shared.plugins.PluginInstance"

    private val volumeClasses = listOf(
        "com.android.systemui.miui.volume.VolumePanelViewController",
        "com.android.systemui.miui.volume.MiuiVolumeDialogView",
        "com.android.systemui.miui.volume.VolumeColumn",
        "com.android.systemui.miui.volume.MiuiRingerModeLayout",
    )

    @Volatile private var probeInstalled = false
    @Volatile private var deferHooked = false

    /** 全局 View hook 的句柄，拿到 plugin loader 后立刻卸掉。 */
    @Volatile private var viewHook: Set<de.robv.android.xposed.XC_MethodHook.Unhook>? = null

    fun bootstrap(process: String, cl: ClassLoader, context: Context?) {
        HookKit.log("╔══ AVBH Framework @ $process (pid=${Process.myPid()}) ══")
        HookKit.log("║ classLoader = ${cl.javaClass.name}")

        val reason = SafeMode.check()
        if (reason != null) {
            HookKit.log("║ ⛔ 安全模式生效：$reason，本进程不做任何注入")
            HookKit.log("║ 日志: ${HookKit.flushLog(context) ?: "仅 logcat"}")
            HookKit.log("╚══")
            return
        }

        if (process != PROCESS_SYSTEMUI && process != PROCESS_PLUGIN) {
            HookKit.log("║ 本进程暂无探针")
            HookKit.log("║ 日志: ${HookKit.flushLog(context) ?: "仅 logcat"}")
            HookKit.log("╚══")
            return
        }

        // ── 解析 loader ──
        val resolved = ClassLoaderResolver.resolve(cl)
        if (resolved != null) {
            HookKit.log("║ ✓ 目标类位于 [${resolved.source}] loader")
            selfCheck(resolved.loader)
            installProbes(resolved.loader)
        } else {
            HookKit.log("║ ✗ 所有已知 loader 都看不到 ${ClassLoaderResolver.MARKER_CLASS}")
            HookKit.log("║   → 说明插件还没加载，挂 loadPlugin 等它")
            selfCheck(cl)
            deferUntilPluginLoaded(cl)
        }

        HookKit.report(process)
        HookKit.log("║ 日志: ${HookKit.flushLog(context) ?: "仅 logcat"}")
        HookKit.log("╚══")
    }

    private fun selfCheck(loader: ClassLoader) {
        HookKit.log("║ ── 自检（loader=${loader.javaClass.simpleName}）──")
        volumeClasses.forEach { name ->
            val ok = runCatching { Class.forName(name, false, loader) }.isSuccess
            HookKit.log("║   ${if (ok) "✓" else "✗"} $name")
        }
        val prefOk = runCatching {
            de.robv.android.xposed.XSharedPreferences(HookKit.PREFS_PKG, HookKit.PREFS_NAME).reload()
            true
        }.getOrDefault(false)
        HookKit.log("║   ${if (prefOk) "✓" else "✗"} 配置可读")
    }

    private fun installProbes(loader: ClassLoader) {
        if (probeInstalled) {
            HookKit.log("║ 探针已装过，跳过")
            return
        }
        probeInstalled = true
        HookKit.log("║ ── 装载探针（OBSERVE，只读）──")
        VolumeProbe.install(loader)
    }

    /**
     * 插件还没加载时，挂 loadPlugin 等它。
     * 加载完成后重新解析 loader，拿到就装探针、打报告。
     */
    private fun deferUntilPluginLoaded(processLoader: ClassLoader) {
        if (deferHooked) return
        deferHooked = true
        HookKit.hookAll(
            processLoader, PLUGIN_INSTANCE_CLASS, "loadPlugin",
            "framework.loadPlugin", HookKit.Mode.OBSERVE
        ) {
            if (probeInstalled) return@hookAll
            val resolved = ClassLoaderResolver.resolve(processLoader) ?: return@hookAll
            HookKit.log("║ ★ 插件加载完成，目标类出现在 [${resolved.source}] loader")
            selfCheck(resolved.loader)
            installProbes(resolved.loader)
            HookKit.report("plugin(after loadPlugin)")
            HookKit.flushLog(null)
        }
        // 插件不走 loadPlugin 时，靠 View 生命周期兜底
        hookViewLifecycleForLoader(processLoader)
    }

    /**
     * 从 View 树里直接找容器，不经过 controller。
     *
     * VolumePanelView（attach 的那个）是整个面板的根，树里有：
     *   MiuiVolumeDialogView（id=volume_dialog）
     *     └─ FrameLayout(volume_dialog_content)
     *         ├─ LinearLayout(gid=volume_dialog_columns)      ← 展开态容器，加柱子用这个
     *         └─ FrameLayout(volume_dialog_column_collapsed)  ← 折叠态容器
     *         └─ ImageButton(volume_expand_button)
     */
    private fun dumpVolumeTree(anchor: View, loader: ClassLoader) {
        try {
            val ctx = anchor.context
            val pkg = ctx.packageName
            fun id(name: String): Int =
                ctx.resources.getIdentifier(name, "id", pkg)

            val root = anchor.rootView ?: anchor
            HookKit.log("║ ── View 树探测（根=${root.javaClass.simpleName}, 包=$pkg）──")

            listOf(
                "volume_dialog" to "MiuiVolumeDialogView",
                "volume_dialog_columns" to "展开态容器",
                "volume_dialog_column_collapsed" to "折叠态容器",
                "volume_dialog_column_temp" to "临时容器",
                "miui_volume_ringer_layout" to "按钮列",
            ).forEach { (idName, label) ->
                val rid = id(idName)
                if (rid == 0) {
                    HookKit.log("║   ✗ $idName : 资源 id 找不到")
                    return@forEach
                }
                val found = root.findViewById<View>(rid)
                if (found == null) {
                    HookKit.log("║   ✗ $idName : 视图不存在")
                } else {
                    val vg = found as? android.view.ViewGroup
                    HookKit.log(
                        "║   ✓ $idName ($label) = ${found.javaClass.simpleName} " +
                            "children=${vg?.childCount ?: "-"} size=${found.width}x${found.height}"
                    )
                    if (vg != null && (idName == "volume_dialog_columns" ||
                                idName == "volume_dialog_column_collapsed")) {
                        for (i in 0 until vg.childCount) {
                            val c = vg.getChildAt(i) ?: continue
                            val tagCls = c.tag?.javaClass?.simpleName ?: "null"
                            val stream = runCatching {
                                c.tag?.let {
                                    it.javaClass.getMethod("getStream").invoke(it) as? Int
                                }
                            }.getOrNull()
                            HookKit.log(
                                "║       child[$i] ${c.javaClass.simpleName} " +
                                    "id=${c.id} tag=$tagCls stream=$stream " +
                                    "vis=${c.visibility} " +
                                    "lp=${c.layoutParams?.width}x${c.layoutParams?.height}"
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            HookKit.log("║ View 树探测失败: $t")
        }
    }

    /**
     * 最终兜底：hook android.view.View#onAttachedToWindow，等 miui.volume 的 View 出现。
     *
     * 为什么可靠：View 的 getClass().getClassLoader() 就是创建它的那个 loader，
     * 也就是 plugin 的 DexClassLoader。完全不依赖插件加载机制叫什么、走哪条路。
     *
     * 性能：只做一次字符串 contains 判断，拿到 loader 后立刻 unhook。
     */
    private fun hookViewLifecycleForLoader(processLoader: ClassLoader) {
        try {
            val viewClass = Class.forName("android.view.View", false, processLoader)
            viewHook = de.robv.android.xposed.XposedBridge.hookAllMethods(
                viewClass, "onAttachedToWindow",
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (probeInstalled) return
                        val v = param.thisObject as? View ?: return
                        val cls = v.javaClass
                        if (!cls.name.contains("miui.volume")) return
                        val loader = cls.classLoader ?: return
                        HookKit.log("║ ★ 从 View 拿到 plugin loader: ${cls.name}")
                        selfCheck(loader)
                        installProbes(loader)

                        // ★ 关键：View 已经 attach，说明面板树已经建好。
                        // 不依赖任何 controller 方法回调，直接跑探测 + dump 树。
                        AudioProbe.run(v.context)
                        dumpVolumeTree(v, loader)

                        HookKit.report("plugin(via View)")
                        HookKit.flushLog(v.context)
                        // 拿到就卸掉，别让全局 View 回调长期挂着
                        runCatching { viewHook?.forEach { it.unhook() } }
                        viewHook = null
                    }
                }
            )
            HookKit.log("║ 已挂 View 生命周期兜底（等 miui.volume 的 View 出现）")
        } catch (t: Throwable) {
            HookKit.log("║ View 生命周期兜底挂载失败: $t")
        }
    }
}
