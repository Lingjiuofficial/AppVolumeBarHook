package com.miui.appvolumebar.core

import java.io.File

/**
 * 熔断开关。
 *
 * 上一版失败的原因：让 SystemUI（system uid）往 /data/local/tmp 写计数文件，
 * 被 SELinux 拒了，catch 掉之后等于没有保护。这版改成「只读多路，写交给人」：
 *
 *   读（宿主进程，成本几乎为零）：
 *     1. 模块 prefs 的 master_switch == "off"   ← App 里点一下写进去
 *     2. /data/local/tmp/avbh.disable           ← root/shell 可 touch
 *     3. /sdcard/avbh.disable                   ← shell 可 touch
 *     4. /sdcard/Download/avbh.disable          ← 文件管理器也能建
 *
 *   写：只发生在模块 App 进程（自己的 prefs），宿主进程从不写。
 *
 * 只要还能开模块 App 就能一键关；连 App 都开不了，还能 root/shell touch 一个文件。
 * 两条都不依赖宿主进程的写权限——这正是上一版的死穴。
 */
internal object SafeMode {

    const val KEY_MASTER = "master_switch"
    const val VALUE_OFF = "off"
    const val VALUE_ON = "on"

    private val flagPaths = listOf(
        "/data/local/tmp/avbh.disable",
        "/sdcard/avbh.disable",
        "/sdcard/Download/avbh.disable",
        "/storage/emulated/0/avbh.disable",
    )

    /** 返回原因字符串表示已熔断；null 表示正常。 */
    fun check(): String? {
        val prefOff = runCatching {
            val prefs = de.robv.android.xposed.XSharedPreferences(HookKit.PREFS_PKG, HookKit.PREFS_NAME)
            prefs.reload()
            prefs.getString(KEY_MASTER, null) == VALUE_OFF
        }.getOrDefault(false)
        if (prefOff) return "prefs:master_switch=off"

        for (p in flagPaths) {
            val exists = runCatching { File(p).exists() }.getOrDefault(false)
            if (exists) return "flag:$p"
        }
        return null
    }

    fun isDisabled(): Boolean = check() != null
}
