package com.miui.appvolumebar.core

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import java.lang.reflect.Method

/**
 * 音频 API 兼容性探测（**纯只读，绝不修改任何音量**）。
 *
 * 要回答的问题：
 *   1. MIUI 私有的 setPlayerVolume/getPlayerVolume 在本机存在吗
 *   2. AOSP 的 getPlayerProxy → PlayerProxy.setVolume 在本机存在吗
 *   3. AudioPlaybackConfiguration 的三个 @hide 方法是否还叫这个名字
 *   4. SystemUI（system uid）是否持有相关权限
 *   5. pm.getNameForUid(uid) 实际返回什么（读音量时要用它当 key）
 *   6. 当前活跃应用的实时音量能不能读出来
 *
 * 背景：三个官方 APK 交叉验证过——plugin 和 SystemUI 里这两个 API 一次都没出现，
 * 只有 misound 用 MIUI 私有的那套。所以兼容性必须在真机上确认，不能靠推断。
 */
internal object AudioProbe {

    private const val TAG = "AudioProbe"

    fun run(context: Context?) {
        if (context == null) {
            HookKit.log("║ [AudioProbe] 没有 Context，跳过")
            return
        }
        HookKit.log("║ ── 音频 API 探测（只读）──")
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am == null) {
                HookKit.log("║   ✗ AudioManager 拿不到")
                return
            }

            checkMethods()
            checkPermissions(context)
            dumpActivePlayers(context, am)
        } catch (t: Throwable) {
            HookKit.log("║   ✗ AudioProbe 异常: $t")
        }
    }

    private fun checkMethods() {
        // MIUI 私有
        val getPlayerVolume = methodOf(
            AudioManager::class.java, "getPlayerVolume", String::class.java
        )
        HookKit.log("║   ${mark(getPlayerVolume)} AudioManager.getPlayerVolume(String)")

        val setPlayerVolume = methodOf(
            AudioManager::class.java, "setPlayerVolume",
            AudioPlaybackConfiguration::class.java, Float::class.javaPrimitiveType
        )
        HookKit.log("║   ${mark(setPlayerVolume)} AudioManager.setPlayerVolume(APC, float)")

        // AOSP @SystemApi
        val getPlayerProxy = methodOf(AudioPlaybackConfiguration::class.java, "getPlayerProxy")
        HookKit.log("║   ${mark(getPlayerProxy)} AudioPlaybackConfiguration.getPlayerProxy()" +
            (getPlayerProxy?.let { " → ${it.returnType.name}" } ?: ""))

        val playerProxyClass = runCatching {
            Class.forName("android.media.PlayerProxy")
        }.getOrNull()
        HookKit.log("║   ${if (playerProxyClass != null) "✓" else "✗"} 类 android.media.PlayerProxy" +
            (playerProxyClass?.let { " (methods=${it.methods.size})" } ?: ""))
        if (playerProxyClass != null) {
            val setVol = methodOf(playerProxyClass, "setVolume", Float::class.javaPrimitiveType)
            HookKit.log("║   ${mark(setVol)} PlayerProxy.setVolume(float)")
        }

        // 其余 @hide
        listOf("getClientUid", "getClientPid", "getPlayerState").forEach { name ->
            HookKit.log("║   ${mark(methodOf(AudioPlaybackConfiguration::class.java, name))} AudioPlaybackConfiguration.$name()")
        }
    }

    private fun checkPermissions(context: Context) {
        listOf(
            "android.permission.MODIFY_AUDIO_ROUTING",
            "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.MODIFY_PHONE_STATE",
        ).forEach { perm ->
            val granted = runCatching {
                context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            HookKit.log("║   ${if (granted) "✓" else "✗"} 权限 $perm")
        }
    }

    /** 只读地列出活跃播放器 + 它们的实时音量。 */
    private fun dumpActivePlayers(context: Context, am: AudioManager) {
        val configs = runCatching { am.activePlaybackConfigurations }.getOrNull()
        if (configs.isNullOrEmpty()) {
            HookKit.log("║   当前没有活跃播放（放点音乐再弹一次面板）")
            return
        }
        val pm = context.packageManager
        val getPlayerVolume = methodOf(AudioManager::class.java, "getPlayerVolume", String::class.java)

        HookKit.log("║   活跃播放器 ${configs.size} 个：")
        configs.take(6).forEach { cfg ->
            val uid = runCatching {
                cfg.javaClass.getDeclaredMethod("getClientUid").apply { isAccessible = true }
                    .invoke(cfg) as Int
            }.getOrNull()
            val state = runCatching {
                cfg.javaClass.getDeclaredMethod("getPlayerState").apply { isAccessible = true }
                    .invoke(cfg) as Int
            }.getOrNull()
            val usage = cfg.audioAttributes?.usage

            val key = if (uid != null) runCatching { pm.getNameForUid(uid) }.getOrNull() else null
            val pkgs = if (uid != null) runCatching { pm.getPackagesForUid(uid) }.getOrNull() else null
            val vol = if (getPlayerVolume != null && key != null) {
                runCatching { (getPlayerVolume.invoke(am, key) as Float) }.getOrNull()
            } else null

            HookKit.log(
                "║     uid=$uid state=$state usage=$usage\n" +
                    "║       nameForUid=$key\n" +
                    "║       packages=${pkgs?.joinToString("/") ?: "-"}  currVol=$vol"
            )
        }
    }

    private fun methodOf(cls: Class<*>, name: String, vararg params: Class<*>): Method? =
        runCatching { cls.getMethod(name, *params) }.getOrNull()
            ?: runCatching { cls.getDeclaredMethod(name, *params).apply { isAccessible = true } }.getOrNull()

    private fun mark(m: Method?) = if (m != null) "✓" else "✗"
}
