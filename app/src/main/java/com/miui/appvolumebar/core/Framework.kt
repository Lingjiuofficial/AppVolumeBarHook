package com.miui.appvolumebar.core

import android.content.Context
import android.os.Process

/**
 * 三个系统应用的统一接入点。
 *
 * 每个进程进来只做四件事，全部有日志、全部不抛异常：
 *   1. 安全模式检查（命中就立刻退出，不做任何注入）
 *   2. 环境自检（classLoader 对不对、关键类在不在、配置能不能读）
 *   3. 装探针（OBSERVE，只读）
 *   4. 输出安装报告 + 落盘
 *
 * 这份日志本身就是「框架是否正常工作」的证据：哪个类没找到、哪个 hook 没装上，
 * 一眼就能看出来，不用再靠猜。
 */
internal object Framework {

    const val PROCESS_SYSTEMUI = "com.android.systemui"
    const val PROCESS_PLUGIN = "miui.systemui.plugin"
    const val PROCESS_MISOUND = "com.miui.misound"

    /**
     * 用来验证「classLoader 拿对了」的标志类。
     * 找到 = 这个 loader 能看到目标代码；找不到 = loader 不对，后面所有 hook 都会 MISS。
     */
    private val sanityClasses = mapOf(
        PROCESS_SYSTEMUI to listOf(
            "com.android.systemui.miui.volume.VolumePanelViewController",
            "com.android.systemui.miui.volume.MiuiVolumeDialogView",
            "com.android.systemui.miui.volume.VolumeColumn",
        ),
        PROCESS_PLUGIN to listOf(
            "miui.systemui.controlcenter.panel.main.volume.VolumeSliderController",
            "miui.systemui.controlcenter.panel.secondary.volume.VolumePanelController",
        ),
        PROCESS_MISOUND to listOf(
            "com.miui.misound.MiSoundApplication",
        ),
    )

    fun bootstrap(process: String, cl: ClassLoader, context: Context?) {
        HookKit.log("╔══ AVBH Framework @ $process (pid=${Process.myPid()}) ══")
        HookKit.log("║ classLoader = ${cl.javaClass.name}")

        val reason = SafeMode.check()
        if (reason != null) {
            HookKit.log("║ ⛔ 安全模式生效：$reason")
            HookKit.log("║    本进程不做任何注入")
            val path = HookKit.flushLog(context)
            HookKit.log("║ 日志: ${path ?: "仅 logcat"}")
            HookKit.log("╚══")
            return
        }

        selfCheck(process, cl)

        when (process) {
            PROCESS_SYSTEMUI, PROCESS_PLUGIN -> {
                HookKit.log("║ ── 装载探针（OBSERVE，只读）──")
                VolumeProbe.install(cl)
            }
            else -> HookKit.log("║ 本进程暂无探针")
        }

        HookKit.report(process)
        val path = HookKit.flushLog(context)
        HookKit.log("║ 日志落盘: ${path ?: "失败，只能看 logcat"}")
        HookKit.log("╚══")
    }

    private fun selfCheck(process: String, cl: ClassLoader) {
        HookKit.log("║ ── 环境自检 ──")
        val classes = sanityClasses[process].orEmpty()
        if (classes.isEmpty()) {
            HookKit.log("║   (未登记标志类)")
        }
        classes.forEach { name ->
            val ok = runCatching { Class.forName(name, false, cl) }.isSuccess
            HookKit.log("║   ${if (ok) "✓" else "✗"} $name")
        }

        val prefOk = runCatching {
            val prefs = de.robv.android.xposed.XSharedPreferences(HookKit.PREFS_PKG, HookKit.PREFS_NAME)
            prefs.reload()
            true
        }.getOrDefault(false)
        HookKit.log("║   ${if (prefOk) "✓" else "✗"} 配置可读 (XSharedPreferences)")
    }
}
