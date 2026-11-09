package com.multiroute.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs upstream selection and lifecycle hooks on NetworkStack / Tethering's
 * `UpstreamNetworkMonitor`, delegating per-instance state transitions to [TetheringUpstreamController].
 *
 * Native Tethering still owns forwarding, DNS, and the lifetime of cellular requests.
 */
internal class TetheringUpstreamHookHandler(
    private val xposed: XposedInterface
) {
    @Volatile
    private var isTetheringHooked = false

    fun install(classLoader: ClassLoader) {
        if (isTetheringHooked) return
        val handles = mutableListOf<XposedInterface.HookHandle>()
        val ready = AtomicBoolean(false)
        try {
            // Google Mainline builds can relocate Tethering classes under android.net.tethering.
            val type = listOf(
                "com.android.networkstack.tethering.UpstreamNetworkMonitor",
                "android.net.tethering.com.android.networkstack.tethering.UpstreamNetworkMonitor"
            ).firstNotNullOfOrNull { name ->
                runCatching { Class.forName(name, false, classLoader) }.getOrNull()
            } ?: throw ClassNotFoundException("UpstreamNetworkMonitor is absent from this package loader")
            val bool = Boolean::class.javaPrimitiveType!!
            // Resolve the required surface before installing any policy overrides.
            val select = type.getDeclaredMethod("getCurrentPreferredUpstream")
            val config = type.getDeclaredMethod("setUpstreamConfig", bool, bool)
            val tryCell = type.getDeclaredMethod("setTryCell", bool)
            val start = type.getDeclaredMethod("startObserveUpstreamNetworks")
            val stop = type.getDeclaredMethod("stop")
            type.getDeclaredMethod("isCellularUpstreamPermitted")
            val updates = listOf("handleAvailable", "handleNetCap", "handleLinkProp", "handleLost").map { name ->
                type.declaredMethods.first { it.name == name }
            }
            val controllers = IdentityHashMap<Any, TetheringUpstreamController>()
            fun controller(instance: Any?): TetheringUpstreamController? {
                if (instance == null) return null
                return runCatching {
                    synchronized(controllers) {
                        controllers.getOrPut(instance) {
                            TetheringUpstreamController(instance) { message ->
                                xposed.log(Log.INFO, HOOK_TAG, "[TetheringHook] $message")
                            }
                        }
                    }
                }.onFailure { xposed.log(Log.WARN, HOOK_TAG, "[TetheringHook] Monitor unavailable: $it") }.getOrNull()
            }
            fun safely(action: () -> Unit) {
                runCatching(action).onFailure { xposed.log(Log.WARN, HOOK_TAG, "[TetheringHook] Policy update failed: $it") }
            }
            fun installHook(method: Method, callback: (XposedInterface.Chain) -> Any?) {
                handles += xposed.hook(method).intercept { chain ->
                    if (ready.get()) callback(chain) else chain.proceed()
                }
            }
            listOf(select, config, tryCell, start, stop).plus(updates).forEach { xposed.deoptimize(it) }
            installHook(config) { chain ->
                val owner = controller(chain.thisObject)
                val args = runCatching {
                    owner?.nativeConfig(chain.args[0] as Boolean, chain.args[1] as Boolean)
                }.getOrNull()
                val result = if (args == null) chain.proceed()
                else chain.proceed(arrayOf<Any>(args.first, args.second))
                safely { owner?.networkChanged() }
                result
            }
            installHook(tryCell) { chain ->
                val requested = runCatching {
                    controller(chain.thisObject)?.nativeTryCell(chain.args[0] as Boolean)
                }.getOrNull()
                if (requested == null) chain.proceed() else chain.proceed(arrayOf<Any>(requested))
            }
            installHook(start) { chain ->
                val result = chain.proceed()
                safely { controller(chain.thisObject)?.started() }
                result
            }
            installHook(stop) { chain ->
                safely { controller(chain.thisObject)?.stopping() }
                chain.proceed()
            }
            updates.forEach { method ->
                installHook(method) { chain ->
                    val result = chain.proceed()
                    safely { controller(chain.thisObject)?.networkChanged() }
                    result
                }
            }
            installHook(select) { chain ->
                val native = chain.proceed()
                runCatching { controller(chain.thisObject)?.select(native) ?: native }
                    .onFailure { xposed.log(Log.WARN, HOOK_TAG, "[TetheringHook] Using native upstream: $it") }
                    .getOrDefault(native)
            }
            // Publish load evidence even before the first hotspot session.
            type.declaredMethods.firstOrNull { it.name == "startTrackDefaultNetwork" }?.let { method ->
                xposed.deoptimize(method)
                installHook(method) { chain ->
                    val result = chain.proceed()
                    controller(chain.thisObject)
                    result
                }
            }
            ready.set(true)
            isTetheringHooked = true
            xposed.log(Log.INFO, HOOK_TAG, "[TetheringHook] Installed upstream selection, lifecycle and network-event hooks")
        } catch (t: Throwable) {
            ready.set(false)
            handles.asReversed().forEach { runCatching { it.unhook() } }
            xposed.log(Log.ERROR, HOOK_TAG, "[TetheringHook] Unsupported native monitor; retaining system behavior", t)
        }
    }
}
