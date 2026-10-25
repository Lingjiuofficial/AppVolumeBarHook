package com.miui.appvolumebar.systemui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import com.miui.appvolumebar.MainHook
import java.lang.reflect.Method

/** 一个正在发声的应用。player 是 @hide 的 android.media.IPlayer 代理。 */
internal data class ActiveApp(
    val uid: Int,
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val player: Any,
)

/**
 * 应用音量数据层：不走 misound，直接用 AudioManager 的公开/隐藏 API。
 *
 * - 枚举：getActivePlaybackConfigurations() + getClientUid()/getPlayerState()（反射）
 * - 控制：AudioPlaybackConfiguration.getPlayerProxy() → IPlayer.setVolume(float)
 *
 * 注意：IPlayer 只有 setVolume 没有 getVolume（MIUI 的多应用音量本身就是只写接口），
 * 所以滑条位置靠本地缓存维持，初次一律显示满值。
 */
internal object AppVolumeController {

    private const val PLAYER_STATE_STARTED = 2
    private const val USAGE_MEDIA = 1
    private const val STREAM_MUSIC = 3

    /** packageName -> 用户最后一次设定的音量（0f..1f）。 */
    private val volumeMemory = HashMap<String, Float>()

    private var setVolumeMethod: Method? = null
    private var setVolumeClass: Class<*>? = null

    fun rememberedVolume(packageName: String): Float = volumeMemory[packageName] ?: 1f

    fun rememberVolume(packageName: String, value: Float) {
        volumeMemory[packageName] = value.coerceIn(0f, 1f)
    }

    /**
     * 列出当前正在播放媒体的第三方应用。
     * 判定口径与 ActiveAudioDetector 保持一致：uid >= 10000、playerState == STARTED、
     * usage == USAGE_MEDIA 或 volumeControlStream == STREAM_MUSIC。
     */
    fun listActiveApps(context: Context): List<ActiveApp> {
        val result = ArrayList<ActiveApp>(3)
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return result
            val pm = context.packageManager
            val seen = HashSet<Int>()

            for (config in am.activePlaybackConfigurations.orEmpty()) {
                val app = toActiveApp(config, pm) ?: continue
                if (!seen.add(app.uid)) continue
                result.add(app)
            }
        } catch (t: Throwable) {
            MainHook.log("AppVolumeController.listActiveApps failed", t)
        }
        return result
    }

    private fun toActiveApp(config: AudioPlaybackConfiguration, pm: PackageManager): ActiveApp? = try {
        val cls = config.javaClass

        val uid = invokeInt(cls, config, "getClientUid") ?: return null
        if (uid < 10000) return null

        val state = invokeInt(cls, config, "getPlayerState") ?: 0
        if (state != PLAYER_STATE_STARTED) return null

        val attrs = config.audioAttributes ?: return null
        val isMedia = attrs.usage == USAGE_MEDIA || attrs.volumeControlStream == STREAM_MUSIC
        if (!isMedia) return null

        val pkg = pm.getNameForUid(uid) ?: return null
        if (pkg == "com.miui.miwallpaper") return null

        val player = runCatching {
            cls.getDeclaredMethod("getPlayerProxy").apply { isAccessible = true }.invoke(config)
        }.getOrNull() ?: return null

        val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
        val label = appInfo?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
            ?: pkg
        val icon = appInfo?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() }

        ActiveApp(uid, pkg, label, icon, player)
    } catch (_: Throwable) {
        null
    }

    private fun invokeInt(cls: Class<*>, target: Any, name: String): Int? = try {
        val m: Method = cls.getDeclaredMethod(name).apply { isAccessible = true }
        (m.invoke(target) as? Int)
    } catch (_: Throwable) {
        try {
            (target.javaClass.getMethod(name).invoke(target) as? Int)
        } catch (_: Throwable) {
            null
        }
    }

    /** 设置单个应用的音量倍率（0f..1f）。 */
    fun setAppVolume(app: ActiveApp, value: Float): Boolean {
        val v = value.coerceIn(0f, 1f)
        rememberVolume(app.packageName, v)
        return try {
            resolveSetVolume(app.player).invoke(app.player, v)
            true
        } catch (t: Throwable) {
            MainHook.log("AppVolumeController.setAppVolume failed for ${app.packageName}", t)
            false
        }
    }

    /** IPlayer 是 aidl 代理，方法声明在接口上，按接口反射最稳。 */
    private fun resolveSetVolume(player: Any): Method {
        val cached = setVolumeMethod
        if (cached != null && setVolumeClass == player.javaClass) return cached

        val method = runCatching {
            val iface = Class.forName("android.media.IPlayer")
            iface.getMethod("setVolume", Float::class.javaPrimitiveType)
        }.getOrElse {
            // 退路：直接在代理类上找（厂商裁剪时接口可能不可见）
            var c: Class<*>? = player.javaClass
            var found: Method? = null
            while (c != null && found == null) {
                found = c.declaredMethods.firstOrNull {
                    it.name == "setVolume" &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Float::class.javaPrimitiveType
                }
                c = c.superclass
            }
            found ?: throw NoSuchMethodException("setVolume(float) not found")
        }
        method.isAccessible = true
        setVolumeMethod = method
        setVolumeClass = player.javaClass
        return method
    }
}
