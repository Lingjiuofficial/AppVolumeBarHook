package com.miui.appvolumebar

import com.miui.appvolumebar.core.Framework
import com.miui.appvolumebar.core.HookKit
import com.miui.appvolumebar.core.SafeMode
import com.miui.appvolumebar.misound.MiSoundHooker
import com.miui.appvolumebar.systemui.SystemUiHooker
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

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

        /**
         * true = 三个系统进程都只装 Framework + 探针，不执行任何业务 hook。
         *
         * 这一版的目标是**验证框架本身**：classLoader 拿得对不对、关键类在不在、
         * 探针能不能 dump 出面板结构。确认无误后再改成 false 进入功能开发。
         */
        const val PROBE_ONLY = true

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
        try {
            if (lpparam.packageName == PKG_SELF) {
                hookSelfModuleState(lpparam)
                return
            }

            // 熔断：三进程统一检查（只读，绝不往磁盘写）
            val reason = SafeMode.check()
            if (reason != null) {
                HookKit.log("(${lpparam.packageName}) 安全模式生效：$reason，跳过注入")
                return
            }

            when (lpparam.packageName) {
                PKG_SYSTEMUI -> {
                    Framework.bootstrap(Framework.PROCESS_SYSTEMUI, lpparam.classLoader, null)
                    if (!PROBE_ONLY) SystemUiHooker.init(lpparam)
                }
                PKG_PLUGIN -> {
                    Framework.bootstrap(Framework.PROCESS_PLUGIN, lpparam.classLoader, null)
                    if (!PROBE_ONLY) SystemUiHooker.initPlugin(lpparam.classLoader)
                }
                PKG_MISOUND -> {
                    Framework.bootstrap(Framework.PROCESS_MISOUND, lpparam.classLoader, null)
                    if (!PROBE_ONLY) MiSoundHooker.init(lpparam)
                }
            }
        } catch (t: Throwable) {
            log("handleLoadPackage failed for ${lpparam.packageName}", t)
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
}
