package com.miui.appvolumebar

import com.miui.appvolumebar.misound.MiSoundHooker
import com.miui.appvolumebar.systemui.SystemUiHooker
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

class MainHook : IXposedHookLoadPackage {

    companion object {
        const val TAG = "AppVolumeBarHook"
        const val PKG_SELF = "com.miui.appvolumebar"
        const val PKG_SYSTEMUI = "com.android.systemui"
        const val PKG_PLUGIN = "miui.systemui.plugin"
        const val PKG_MISOUND = "com.miui.misound"
        const val ACTION_EXPAND_MEDIA_VOLUME = "com.miui.appvolumebar.ACTION_EXPAND_MEDIA_VOLUME"
        const val ACTION_QUERY_HOOK_STATUS = "com.miui.appvolumebar.ACTION_QUERY_HOOK_STATUS"
        const val ACTION_REPORT_HOOK_STATUS = "com.miui.appvolumebar.ACTION_REPORT_HOOK_STATUS"

        // ---- 安全熔断 ----
        // 万一 hook 把 SystemUI 打崩，只要下面任一文件存在，模块就完全不注入。
        //   su -c "touch /data/local/tmp/avbh.disable"       （推荐，root 一条命令）
        //   也可以在 Termux 里建 /sdcard/avbh.disable
        // 另外 LSPosed 管理器里直接关掉作用域永远是最快的救援手段。
        private const val SAFE_FLAG_LOCAL = "/data/local/tmp/avbh.disable"
        private const val SAFE_FLAG_SDCARD = "/sdcard/avbh.disable"

        /** 崩溃循环检测：同一进程 60 秒内启动 5 次即判定为崩溃循环，自动熔断。 */
        private const val BOOT_STATE_DIR = "/data/local/tmp"
        private const val CRASH_WINDOW_MS = 60_000L
        private const val CRASH_LIMIT = 5

        fun log(msg: String) {
            XposedBridge.log("[$TAG] $msg")
        }

        fun log(msg: String, tr: Throwable?) {
            if (tr != null) {
                XposedBridge.log("[$TAG] $msg\n" + android.util.Log.getStackTraceString(tr))
            } else {
                XposedBridge.log("[$TAG] $msg")
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 顶层兜底：任何注入异常都不允许冒泡回宿主进程
        try {
            // 模块自己的进程不做熔断，否则出问题后连设置页都进不去
            if (lpparam.packageName != PKG_SELF && shouldSkip(lpparam.packageName)) return

            when (lpparam.packageName) {
                PKG_SELF -> hookSelfModuleState(lpparam)
                PKG_MISOUND -> {
                    log("Handling loadPackage for: ${lpparam.packageName}")
                    MiSoundHooker.init(lpparam)
                }
                PKG_SYSTEMUI -> {
                    log("Handling loadPackage for: ${lpparam.packageName}")
                    SystemUiHooker.init(lpparam)
                }
                PKG_PLUGIN -> {
                    log("Handling loadPackage for: ${lpparam.packageName} (Direct Plugin Scope)")
                    SystemUiHooker.initPlugin(lpparam.classLoader)
                }
            }
        } catch (t: Throwable) {
            log("handleLoadPackage failed, injected nothing for ${lpparam.packageName}", t)
        }
    }

    private fun hookSelfModuleState(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val statusClass = de.robv.android.xposed.XposedHelpers.findClassIfExists(
                "com.miui.appvolumebar.status.ModuleStatus",
                lpparam.classLoader
            )
            if (statusClass != null) {
                de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                    statusClass,
                    "isModuleActive",
                    object : de.robv.android.xposed.XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any = true
                    }
                )
                log("Hooked ModuleStatus.isModuleActive successfully")
            }
        } catch (t: Throwable) {
            log("Failed to hook ModuleStatus.isModuleActive", t)
        }
    }

    /** true = 本次跳过注入。 */
    private fun shouldSkip(pkg: String): Boolean {
        if (flagExists(SAFE_FLAG_LOCAL) || flagExists(SAFE_FLAG_SDCARD)) {
            log("SAFE MODE: $pkg skipped (disable flag present)")
            return true
        }
        return crashLoopDetected(pkg)
    }

    private fun flagExists(path: String): Boolean = try {
        File(path).exists()
    } catch (_: Throwable) {
        false
    }

    /**
     * 崩溃循环检测：把每次进程启动的时间戳+计数写到 /data/local/tmp（SystemUI 是 system uid，
     * 通常可写；写不进去就只当没熔断，绝不阻塞注入）。
     */
    private fun crashLoopDetected(pkg: String): Boolean = try {
        val stateFile = File(BOOT_STATE_DIR, "avbh.boot.$pkg")
        val now = System.currentTimeMillis()
        val (last, count) = if (stateFile.exists()) {
            val parts = stateFile.readText().trim().split(",")
            (parts.getOrNull(0)?.toLongOrNull() ?: 0L) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
        } else 0L to 0
        val next = if (now - last <= CRASH_WINDOW_MS) count + 1 else 1
        stateFile.writeText("$now,$next")
        if (next >= CRASH_LIMIT) {
            log("SAFE MODE: $pkg started $next times within 60s, auto disable")
            stateFile.delete()
            try {
                File(SAFE_FLAG_LOCAL).createNewFile()
            } catch (_: Throwable) {
            }
            true
        } else {
            false
        }
    } catch (_: Throwable) {
        false
    }
}
