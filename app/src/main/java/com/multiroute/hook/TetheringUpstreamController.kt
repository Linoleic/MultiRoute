package com.multiroute.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.util.AtomicFile
import com.multiroute.BuildConfig
import com.multiroute.util.HotspotCandidate
import com.multiroute.util.HotspotHookStateParser
import com.multiroute.util.HotspotRequestPolicy
import com.multiroute.util.HotspotUpstreamPolicy
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method

/** One native monitor, accessed only on its own state-machine Handler. */
internal class TetheringUpstreamController(
    private val monitor: Any,
    private val report: (String) -> Unit
) {
    companion object {
        const val TARGET_KEY = "multiroute_hotspot_upstream"
        const val UPDATE_ACTION = "com.multiroute.ACTION_UPDATE_HOTSPOT_UPSTREAM"

        private fun field(type: Class<*>, name: String): Field {
            var current: Class<*>? = type
            while (current != null) {
                try { return current.getDeclaredField(name).apply { isAccessible = true } }
                catch (_: NoSuchFieldException) { current = current.superclass }
            }
            throw NoSuchFieldException(name)
        }

        private fun value(obj: Any, name: String): Any? = field(obj.javaClass, name).get(obj)
    }

    private val type = monitor.javaClass
    private val context = value(monitor, "mContext") as Context
    private val handler = value(monitor, "mHandler") as Handler
    private val listener = requireNotNull(value(monitor, "mEventListener"))
    private val configMethod = type.getDeclaredMethod("setUpstreamConfig", Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType).apply { isAccessible = true }
    private val tryCellMethod = type.getDeclaredMethod("setTryCell", Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }
    private val permittedMethod = type.getDeclaredMethod("isCellularUpstreamPermitted").apply { isAccessible = true }
    private val eventMethod: Method = listener.javaClass.methods.first {
        it.name == "onUpstreamEvent" && it.parameterTypes.size == 2
    }.apply { isAccessible = true }
    private val switchEvent = field(type, "EVENT_DEFAULT_SWITCHED").getInt(null)
    private val policy = HotspotRequestPolicy(
        value(monitor, "mAutoUpstream") as Boolean,
        value(monitor, "mDunRequired") as Boolean,
        value(monitor, "mTryCell") as Boolean
    )
    private var applyingPolicy = false
    private var selectedId: Long? = null
    private var selectionKey: Pair<String, Long?>? = null
    private var forceReevaluation = false
    private var receiverRegistered = false
    private var observerRegistered = false
    private var lastBeacon: String? = null
    private val startTicks = HotspotHookStateParser.processStartTicks(
        runCatching { File("/proc/self/stat").readText() }.getOrNull()
    ) ?: 0L

    private val reselect = Runnable {
        val forced = forceReevaluation
        forceReevaluation = false
        if (!policy.observing) return@Runnable
        try {
            refreshTarget()
            applyRequestPolicy()
            val selected = choose()
            val key = policy.target to selected?.first?.id
            if (forced || key != selectionKey) {
                selectionKey = key
                eventMethod.invoke(listener, switchEvent, null)
                report("Reevaluate target=${policy.target}, candidate=${selected?.first?.iface ?: "default"}")
            }
        } catch (t: Throwable) { report("Reevaluation failed: ${t.cause ?: t}") }
    }

    init {
        // Settings and broadcasts arrive on this Handler, never on the process main thread.
        handler.post {
            runCatching {
                refreshTarget()
                registerSettings()
                publish("none")
            }.onFailure { report("Monitor initialization failed: $it") }
        }
    }

    private fun checkThread() { check(Looper.myLooper() === handler.looper) }

    private fun onHandler(action: () -> Unit) {
        handler.post {
            runCatching(action).onFailure { report("Handler update failed: $it") }
        }
    }

    private fun refreshTarget() {
        policy.target = HotspotUpstreamPolicy.normalize(Settings.Global.getString(context.contentResolver, TARGET_KEY))
    }

    fun nativeConfig(auto: Boolean, dun: Boolean): Pair<Boolean, Boolean> {
        // Initial Tethering configuration can be supplied by the service's main thread.
        // Let that native call finish unchanged, then reconcile on the monitor's Handler.
        if (Looper.myLooper() !== handler.looper) {
            onHandler {
                policy.onNativeConfig(auto, dun)
                refreshTarget()
                applyRequestPolicy()
                if (policy.observing) schedule(true)
            }
            return auto to dun
        }
        checkThread()
        if (!applyingPolicy) {
            policy.onNativeConfig(auto, dun)
            refreshTarget()
        }
        return if (applyingPolicy) auto to dun else policy.effective().let { it.first to it.second }
    }

    fun nativeTryCell(tryCell: Boolean): Boolean {
        if (Looper.myLooper() !== handler.looper) {
            onHandler {
                policy.onNativeTryCell(tryCell)
                refreshTarget()
                applyRequestPolicy()
            }
            return tryCell
        }
        checkThread()
        if (!applyingPolicy) {
            policy.onNativeTryCell(tryCell)
            refreshTarget()
        }
        return if (applyingPolicy) tryCell else policy.effective().third
    }

    fun started() {
        if (Looper.myLooper() !== handler.looper) { onHandler { started() }; return }
        checkThread()
        policy.observing = true
        registerSettings()
        refreshTarget()
        applyRequestPolicy()
        schedule(true)
    }

    fun stopping() {
        if (Looper.myLooper() !== handler.looper) { onHandler { stopping() }; return }
        checkThread()
        policy.observing = false
        policy.onNativeTryCell(false)
        handler.removeCallbacks(reselect)
        forceReevaluation = false
        selectionKey = null
        selectedId = null
        applyRequestPolicy()
        publish("none")
    }

    fun networkChanged() {
        if (Looper.myLooper() !== handler.looper) { onHandler { networkChanged() }; return }
        checkThread()
        if (policy.observing && policy.target != "default") schedule(false)
    }

    private fun schedule(force: Boolean) {
        forceReevaluation = forceReevaluation || force
        // Fixed window: coalesce updates without indefinitely postponing work during churn.
        if (!handler.hasCallbacks(reselect)) handler.postDelayed(reselect, 100)
    }

    private fun applyRequestPolicy() {
        if (applyingPolicy) return
        val (auto, dun, tryCell) = policy.effective()
        applyingPolicy = true
        try {
            val configChanged = value(monitor, "mAutoUpstream") != auto || value(monitor, "mDunRequired") != dun
            val requestChanged = value(monitor, "mTryCell") != tryCell ||
                (tryCell && value(monitor, "mMobileNetworkCallback") == null)
            if (configChanged) {
                configMethod.invoke(monitor, auto, dun)
            }
            // Native code owns permission checks, DUN requests and release-on-stop.
            if (requestChanged) {
                tryCellMethod.invoke(monitor, tryCell)
            }
            if (configChanged || requestChanged) {
                report("Request policy: nativeAuto=${policy.nativeAuto}, auto=$auto, dun=$dun, " +
                    "tryCell=$tryCell, requested=${value(monitor, "mMobileNetworkCallback") != null}")
            }
        } finally { applyingPolicy = false }
    }

    private fun registerSettings() {
        if (!receiverRegistered) {
            runCatching {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) { schedule(true) }
                }
                val filter = IntentFilter(UPDATE_ACTION)
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED)
                else context.registerReceiver(receiver, filter, null, handler)
                receiverRegistered = true
            }.onFailure { report("Receiver registration failed: $it") }
        }
        if (!observerRegistered) {
            runCatching {
                context.contentResolver.registerContentObserver(Settings.Global.getUriFor(TARGET_KEY), false,
                    object : ContentObserver(handler) {
                        override fun onChange(selfChange: Boolean) { schedule(true) }
                    })
                observerRegistered = true
            }.onFailure { report("Settings observer registration failed: $it") }
        }
    }

    private fun choose(): Pair<HotspotCandidate, Any>? {
        val states = value(monitor, "mNetworkMap") as? Map<*, *> ?: return null
        val candidates = states.mapNotNull { (key, state) ->
            if (state == null || key !is Network) return@mapNotNull null
            val lp = value(state, "linkProperties") as? LinkProperties ?: return@mapNotNull null
            val nc = value(state, "networkCapabilities") as? NetworkCapabilities ?: return@mapNotNull null
            HotspotCandidate(
                id = key.networkHandle,
                iface = lp.interfaceName.orEmpty(),
                cellular = nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                internet = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                dun = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_DUN),
                validated = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                hasAddress = lp.linkAddresses.isNotEmpty(),
                hasDefaultRoute = lp.routes.any { it.isDefaultRoute },
                subscriptions = if (Build.VERSION.SDK_INT >= 31) nc.subscriptionIds else emptySet()
            ) to state
        }
        val permitted = permittedMethod.invoke(monitor) as Boolean
        val chosen = HotspotUpstreamPolicy.select(policy.target, candidates.map { it.first }, policy.nativeDun,
            permitted, selectedId, SubscriptionManager.getDefaultDataSubscriptionId()) ?: return null
        return candidates.first { it.first.id == chosen.id }
    }

    fun select(defaultState: Any?): Any? {
        checkThread()
        refreshTarget()
        val chosen = if (policy.observing) choose() else null
        selectedId = chosen?.first?.id
        selectionKey = policy.target to selectedId
        val state = chosen?.second ?: defaultState
        val active = state?.let { (value(it, "linkProperties") as? LinkProperties)?.interfaceName } ?: "none"
        publish(active)
        return state
    }

    private fun publish(active: String) {
        val text = HotspotHookStateParser.beacon(android.os.Process.myPid(), startTicks,
            BuildConfig.HOTSPOT_HOOK_BUILD_ID, policy.target, active)
        if (text == lastBeacon) return
        runCatching {
            val file = AtomicFile(File(context.cacheDir, HotspotHookStateParser.FILE_NAME))
            val stream = file.startWrite()
            try {
                stream.write(text.toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (t: Throwable) {
                file.failWrite(stream)
                throw t
            }
            lastBeacon = text
        }.onFailure { report("Beacon write failed: $it") }
        report("Status beacon: $text")
    }
}
