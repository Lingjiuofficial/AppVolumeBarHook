package com.miui.appvolumebar.core

import android.content.Context
import android.os.Process
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
    }
}
