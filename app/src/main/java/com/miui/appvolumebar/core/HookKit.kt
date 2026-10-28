package com.miui.appvolumebar.core

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * hook 脚手架的唯一入口。对系统进程的任何改动都必须经过这里。
 *
 * 设计目标（全部来自之前真实踩过的坑）：
 *  1. 可归因——每个 hook 点唯一 id，进出/异常都带 id，崩溃前最后一条日志能定位到点
 *  2. 可回退——每个点三态开关（off / observe / modify），配置在模块 prefs
 *  3. 可验证——OBSERVE 模式只读不改，先证明 hook 点找对再改行为
 *  4. 不抛错——所有回调 try-catch，异常只完整打印一次，避免刷屏
 *  5. 自说明——安装结束输出报告，写清楚装了哪些、跳过了哪些、为什么
 */
object HookKit {

    const val TAG = "AVBH"

    enum class Mode { OFF, OBSERVE, MODIFY }

    /** 单个 hook 点的状态。 */
    class Spec(val id: String) {
        @Volatile var mode: Mode = Mode.OFF
        @Volatile var installed = false
        @Volatile var target: String = ""
        @Volatile var lastError: String? = null
        val hits = AtomicLong(0)
        val failures = AtomicLong(0)
    }

    private val specs = ConcurrentHashMap<String, Spec>()
    private val failureLogged = ConcurrentHashMap<String, Boolean>()

    fun allSpecs(): List<Spec> = specs.values.sortedBy { it.id }

    fun specOf(id: String): Spec? = specs[id]

    // ---------------- 日志 ----------------

    private val logBuffer = StringBuilder(8192)
    private val logLock = Any()

    fun log(message: String) {
        Log.i(TAG, message)
        synchronized(logLock) {
            if (logBuffer.length < 60000) logBuffer.append(message).append('\n')
        }
        runCatching { XposedBridge.log("[$TAG] $message") }
    }

    fun logError(key: String, message: String, t: Throwable) {
        if (failureLogged.putIfAbsent(key, true) != null) return
        log("!! $message :: ${t.javaClass.simpleName}: ${t.message}")
        log(Log.getStackTraceString(t).lineSequence().take(12).joinToString("\n"))
    }

    /** 把累计的日志刷到落盘文件（宿主是 system uid，很多目录写不了，逐个试）。 */
    fun flushLog(context: android.content.Context?): String? {
        val body = synchronized(logLock) { logBuffer.toString() }
        if (body.isEmpty()) return null
        val candidates = buildList {
            context?.let {
                add(java.io.File(it.filesDir, "avbh.log"))
                add(java.io.File(it.cacheDir, "avbh.log"))
            }
            add(java.io.File("/sdcard/Android/data/com.miui.appvolumebar/files/avbh.log"))
            add(java.io.File("/data/local/tmp/avbh.log"))
        }
        for (f in candidates) {
            try {
                f.parentFile?.mkdirs()
                f.appendText(body)
                return f.absolutePath
            } catch (_: Throwable) {
            }
        }
        return null
    }

    // ---------------- 配置 ----------------

    const val PREFS_NAME = "hook_config"
    const val PREFS_PKG = "com.miui.appvolumebar"

    /** prefs 里的值 → 模式。读不到就用默认值，配置坏了也绝不影响启动。 */
    fun resolveMode(id: String, defaultMode: Mode): Mode = try {
        val prefs = de.robv.android.xposed.XSharedPreferences(PREFS_PKG, PREFS_NAME)
        prefs.reload()
        when (prefs.getString(id, null)?.lowercase(java.util.Locale.ROOT)) {
            "off" -> Mode.OFF
            "observe" -> Mode.OBSERVE
            "modify" -> Mode.MODIFY
            else -> defaultMode
        }
    } catch (_: Throwable) {
        defaultMode
    }

    // ---------------- 安装 ----------------

    fun hookAll(
        cl: ClassLoader,
        className: String,
        methodName: String,
        id: String,
        defaultMode: Mode = Mode.OBSERVE,
        observe: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
        before: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
        after: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
    ): Boolean = hookInternal(id, defaultMode, "$className#$methodName") {
        val clazz = XposedHelpers.findClass(className, cl)
        XposedBridge.hookAllMethods(clazz, methodName, callback(id, observe, before, after))
    }

    fun hookConstructor(
        cl: ClassLoader,
        className: String,
        id: String,
        defaultMode: Mode = Mode.OBSERVE,
        observe: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
        before: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
        after: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
    ): Boolean = hookInternal(id, defaultMode, "$className<init>") {
        val clazz = XposedHelpers.findClass(className, cl)
        XposedBridge.hookAllConstructors(clazz, callback(id, observe, before, after))
    }

    private inline fun hookInternal(
        id: String,
        defaultMode: Mode,
        target: String,
        install: () -> Set<XC_MethodHook.Unhook>?,
    ): Boolean {
        val spec = specs.getOrPut(id) { Spec(id) }
        spec.target = target
        spec.mode = resolveMode(id, defaultMode)

        if (spec.mode == Mode.OFF) {
            log("· $id [OFF] 跳过 ($target)")
            return false
        }
        return try {
            val unhooks = install()
            val count = unhooks?.size ?: 0
            spec.installed = count > 0
            if (count > 0) log("· $id [${spec.mode}] 已装 $count 个方法 → $target")
            else log("· $id [${spec.mode}] 目标不存在 → $target")
            count > 0
        } catch (t: Throwable) {
            spec.lastError = t.toString()
            logError("$id#install", "$id 安装失败 ($target)", t)
            false
        }
    }

    private fun callback(
        id: String,
        observe: ((XC_MethodHook.MethodHookParam) -> Unit)?,
        before: ((XC_MethodHook.MethodHookParam) -> Unit)?,
        after: ((XC_MethodHook.MethodHookParam) -> Unit)?,
    ) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val spec = specs[id] ?: return
            spec.hits.incrementAndGet()
            try {
                if (spec.mode == Mode.MODIFY) before?.invoke(param) else observe?.invoke(param)
            } catch (t: Throwable) {
                spec.failures.incrementAndGet()
                spec.lastError = t.toString()
                logError("$id#before", "$id before 抛异常", t)
            }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            val spec = specs[id] ?: return
            try {
                if (spec.mode == Mode.MODIFY) after?.invoke(param) else observe?.invoke(param)
            } catch (t: Throwable) {
                spec.failures.incrementAndGet()
                spec.lastError = t.toString()
                logError("$id#after", "$id after 抛异常", t)
            }
        }
    }

    /** 安装结束后的报告，直接回答"装了哪些、跳了哪些、为什么"。 */
    fun report(process: String) {
        log("──────── 安装报告 [$process] ────────")
        if (specs.isEmpty()) {
            log("  （没有注册任何 hook 点）")
        } else {
            specs.values.sortedBy { it.id }.forEach { s ->
                val state = when {
                    s.mode == Mode.OFF -> "OFF"
                    s.installed -> "OK"
                    else -> "MISS"
                }
                log("  ${s.id.padEnd(34)} $state  命中=${s.hits}  失败=${s.failures}  ${s.lastError ?: ""}")
            }
        }
        log("────────────────────────────────")
    }
}
