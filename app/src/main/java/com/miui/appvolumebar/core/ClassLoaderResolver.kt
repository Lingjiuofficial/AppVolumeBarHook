package com.miui.appvolumebar.core

/**
 * 找出「真正能看到音量面板类」的那个 ClassLoader。
 *
 * 背景（真机自检得出的结论）：
 *   HyperOS 3 的音量面板不是 com.android.systemui 自己的类，
 *   而是 miui.systemui.plugin 这个【插件包】里的类，由 SystemUI 动态加载。
 *   所以在 SystemUI 进程里用 Class.forName(名字, false, 系统loader) 一定是 ✗，
 *   而且 miui.systemui.plugin 也不作为独立进程出现在 LSPosed 日志里。
 *
 * 可选路径（按可靠性排序）：
 *   1. plugin 已经加载过 → PluginInstanceInjector.sClassLoaders 里存着
 *   2. 当前线程 contextClassLoader
 *   3. 进程自身的 loader
 *
 * 如果全都拿不到（插件还没加载），就 hook PluginInstance.loadPlugin 等它加载完再解析。
 */
internal object ClassLoaderResolver {

    private const val INJECTOR_CLASS = "com.miui.systemui.plugin.PluginInstanceInjector"
    private const val PLUGIN_PACKAGE = "miui.systemui.plugin"

    /** 音量面板的标志类，用来验证某个 loader 是否"看得到"目标。 */
    const val MARKER_CLASS = "com.android.systemui.miui.volume.VolumePanelViewController"

    class Candidate(val source: String, val loader: ClassLoader)

    /** 列出所有候选 loader（去重）。 */
    fun candidates(processLoader: ClassLoader): List<Candidate> {
        val out = ArrayList<Candidate>(4)
        out.add(Candidate("process", processLoader))

        try {
            Thread.currentThread().contextClassLoader?.let {
                out.add(Candidate("thread", it))
            }
        } catch (_: Throwable) {
        }

        try {
            readInjectorLoaders(processLoader).forEachIndexed { i, cl ->
                out.add(Candidate("injector[$i]", cl))
            }
        } catch (_: Throwable) {
        }

        return out.distinctBy { it.loader }
    }

    /** 返回第一个能看见 MARKER_CLASS 的 loader；都看不见返回 null。 */
    fun resolve(processLoader: ClassLoader): Candidate? =
        candidates(processLoader).firstOrNull { canSeeMarker(it.loader) }

    fun canSeeMarker(loader: ClassLoader): Boolean =
        runCatching { Class.forName(MARKER_CLASS, false, loader) }.isSuccess

    /** PluginInstanceInjector.sClassLoaders 可能是 Map 也可能是 Collection，两种都兼容。 */
    private fun readInjectorLoaders(processLoader: ClassLoader): List<ClassLoader> {
        val cls = Class.forName(INJECTOR_CLASS, false, processLoader)
        val field = cls.getDeclaredField("sClassLoaders").apply { isAccessible = true }
        return when (val value = field.get(null)) {
            is Map<*, *> -> value.values.filterIsInstance<ClassLoader>()
            is Collection<*> -> value.filterIsInstance<ClassLoader>()
            else -> emptyList()
        }
    }

    /** 插件包名，供 hook PluginInstance.loadPlugin 时做匹配。 */
    fun isTargetPlugin(pkg: String?): Boolean = pkg == PLUGIN_PACKAGE
}
