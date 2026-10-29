package com.miui.appvolumebar.audio

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import com.miui.appvolumebar.core.HookKit

/** 一个正在发声的应用。 */
internal data class ActivePlayer(
    val uid: Int,
    /** getPlayerVolume 用的 key（pm.getNameForUid）；注意可能带 :proc 后缀 */
    val key: String,
    /** 主包名，用于取图标/标签/记忆音量 */
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val config: AudioPlaybackConfiguration,
)

/**
 * 活跃播放器枚举。
 *
 * 判定条件严格照搬官方 misound 的 `com/miui/misound/playervolume/f.java#a()`：
 *
 *   uid >= 10000                      只算第三方
 *   且 不是 com.miui.miwallpaper
 *   且 playerState == 2               PLAYER_STATE_STARTED
 *   且 usage == 1 (MEDIA) 或 volumeControlStream == 3 (MUSIC)
 *   按 uid 去重（同一应用多个 player 只留一个）
 *
 * 不自己发明条件——跟系统行为对齐才不会出现"官方列出来我没有"的差异。
 */
internal object ActivePlayers {

    private const val PLAYER_STATE_STARTED = 2
    private const val USAGE_MEDIA = 1
    private const val STREAM_MUSIC = 3

    fun list(context: Context): List<ActivePlayer> {
        val out = ArrayList<ActivePlayer>(4)
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return out
            val pm = context.packageManager
            val configs = am.activePlaybackConfigurations ?: return out

            val seen = HashSet<Int>()
            for (cfg in configs) {
                val uid = intMethod(cfg, "getClientUid") ?: continue
                if (uid < 10000) continue
                if (!seen.add(uid)) continue          // 按 uid 去重

                val state = intMethod(cfg, "getPlayerState") ?: 0
                if (state != PLAYER_STATE_STARTED) continue

                val attrs = cfg.audioAttributes ?: continue
                val isMedia = attrs.usage == USAGE_MEDIA ||
                    attrs.volumeControlStream == STREAM_MUSIC
                if (!isMedia) continue

                val key = runCatching { pm.getNameForUid(uid) }.getOrNull() ?: continue
                if (key == "com.miui.miwallpaper") continue

                val pkgs = runCatching { pm.getPackagesForUid(uid) }.getOrNull()
                val pkg = pkgs?.firstOrNull() ?: key
                val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                val label = appInfo?.let {
                    runCatching { pm.getApplicationLabel(it).toString() }.getOrNull()
                } ?: pkg
                val icon: Drawable? = appInfo?.let {
                    runCatching { pm.getApplicationIcon(it) }.getOrNull()
                }

                out.add(ActivePlayer(uid, key, pkg, label, icon, cfg))
            }
        } catch (t: Throwable) {
            HookKit.log("ActivePlayers.list failed: $t")
        }
        return out
    }

    private fun intMethod(cfg: AudioPlaybackConfiguration, name: String): Int? = try {
        (cfg.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(cfg) as? Int)
    } catch (_: Throwable) {
        null
    }
}
