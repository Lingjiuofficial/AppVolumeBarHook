package com.miui.appvolumebar.audio

import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import com.miui.appvolumebar.core.HookKit
import java.lang.reflect.Method

/**
 * 多应用音量 API 封装。
 *
 * 两条路，实测在本机（HyperOS 3 / Android 16）**都存在且可用**：
 *
 *   路线 A（小米私有，能读能写）—— 官方 misound 用的就是这条
 *     AudioManager.getPlayerVolume(String key)                     → float
 *     AudioManager.setPlayerVolume(AudioPlaybackConfiguration, f)  → void
 *
 *   路线 B（AOSP @SystemApi，只能写）
 *     config.getPlayerProxy() → PlayerProxy.setVolume(float)
 *
 * 优先 A（因为能读），A 不可用时降级 B（写入仍可用，读取只能靠本地记忆）。
 */
internal object PlayerVolumeApi {

    private var getPlayerVolume: Method? = null
    private var setPlayerVolume: Method? = null
    private var setVolumeViaProxy: Method? = null

    /** 本地记忆：packageName -> 音量，A 路线不可用时兜底。 */
    private val memory = HashMap<String, Float>()

    @Volatile private var resolved = false
    @Volatile private var routeA = false
    @Volatile private var routeB = false

    fun ensureResolved(am: AudioManager) {
        if (resolved) return
        synchronized(this) {
            if (resolved) return
            getPlayerVolume = runCatching {
                AudioManager::class.java.getMethod("getPlayerVolume", String::class.java)
            }.getOrNull()
            setPlayerVolume = runCatching {
                AudioManager::class.java.getMethod(
                    "setPlayerVolume",
                    AudioPlaybackConfiguration::class.java,
                    java.lang.Float.TYPE
                )
            }.getOrNull()
            setVolumeViaProxy = runCatching {
                val proxyClass = Class.forName("android.media.PlayerProxy")
                proxyClass.getMethod("setVolume", java.lang.Float.TYPE)
            }.getOrNull()

            routeA = getPlayerVolume != null && setPlayerVolume != null
            routeB = setVolumeViaProxy != null
            resolved = true
            HookKit.log(
                "PlayerVolumeApi: routeA(MIUI)=$routeA routeB(PlayerProxy)=$routeB"
            )
        }
    }

    /** 读某个应用的当前音量（0..1）；读不到返回 null。 */
    fun getVolume(am: AudioManager, key: String?, packageName: String?): Float? {
        ensureResolved(am)
        if (key != null && getPlayerVolume != null) {
            val v = runCatching {
                (getPlayerVolume!!.invoke(am, key) as? Float)
            }.getOrNull()
            if (v != null) {
                packageName?.let { memory[it] = v }
                return v
            }
        }
        return packageName?.let { memory[it] }
    }

    /** 写某个应用的音量（0..1）。返回是否成功。 */
    fun setVolume(am: AudioManager, config: AudioPlaybackConfiguration, value: Float): Boolean {
        ensureResolved(am)
        val v = value.coerceIn(0f, 1f)

        if (setPlayerVolume != null) {
            val ok = runCatching {
                setPlayerVolume!!.invoke(am, config, v)
                true
            }.getOrDefault(false)
            if (ok) return true
        }

        // 降级：PlayerProxy.setVolume
        if (setVolumeViaProxy != null) {
            val ok = runCatching {
                val proxy = AudioPlaybackConfiguration::class.java
                    .getMethod("getPlayerProxy").invoke(config)
                    ?: return@runCatching false
                setVolumeViaProxy!!.invoke(proxy, v)
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    fun rememberVolume(packageName: String, value: Float) {
        memory[packageName] = value.coerceIn(0f, 1f)
    }

    fun status(): String = "routeA=$routeA routeB=$routeB"
}
