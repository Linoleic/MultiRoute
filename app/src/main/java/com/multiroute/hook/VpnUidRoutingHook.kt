package com.multiroute.hook

import android.content.Context
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.util.Range
import com.multiroute.util.RouteRuleBuilder
import io.github.libxposed.api.XposedModule
import java.io.File
import java.util.WeakHashMap

/** Adds explicitly routed clone-profile UIDs to an active VpnService network. */
internal class VpnUidRoutingHook(private val xposed: XposedModule) {
    private class TrackedVpn(val handler: Handler) {
        var injected = false
    }

    private val active = WeakHashMap<Any, TrackedVpn>()
    private var platform: Platform? = null

    @Synchronized
    fun install(loader: ClassLoader) {
        if (platform != null) return
        try {
            val type = Class.forName("com.android.server.connectivity.Vpn", false, loader)
            val api = Platform(type)
            val connect = type.declaredMethods.first { it.name == "agentConnect" && it.parameterCount == 1 }
            xposed.deoptimize(connect)
            xposed.hook(connect).intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    val vpn = chain.thisObject ?: return@runCatching
                    val tracked = TrackedVpn(Handler(api.looper.get(vpn) as Looper))
                    synchronized(active) { active[vpn] = tracked }
                    schedule(vpn, tracked, api)
                }.onFailure { logFailure(it) }
                result
            }
            platform = api
            xposed.log(Log.INFO, HOOK_TAG, "[VpnUid] Clone VPN routing hook installed")
        } catch (t: Throwable) {
            logFailure(t)
        }
    }

    fun refresh() {
        val api = synchronized(this) { platform } ?: return
        val tracked = synchronized(active) { active.toList() }
        for ((vpn, state) in tracked) schedule(vpn, state, api)
    }

    private fun schedule(vpn: Any, state: TrackedVpn, api: Platform) {
        state.handler.post {
            runCatching {
                val file = File(RouteRuleBuilder.RULE_CACHE_PATH)
                if (!file.isFile) return@runCatching
                val published = file.readText()
                synchronized(vpn) { update(vpn, state, api, published) }
            }.onFailure { logFailure(it) }
        }
    }

    private fun update(vpn: Any, state: TrackedVpn, api: Platform, published: String) {
        val agent = api.agent.get(vpn) ?: return
        val iface = api.iface.get(vpn) as? String ?: return
        val config = api.config.get(vpn) ?: return
        val oldCaps = api.caps.get(vpn) as? NetworkCapabilities ?: return
        // Platform-managed VPNs and lockdown retain their native policy.
        if (api.lockdown.getBoolean(vpn) || api.runner.get(vpn) != null) return
        val context = api.context.get(vpn) as Context
        val userId = api.userId.getInt(vpn)
        val ownerUid = api.ownerUid.getInt(vpn)
        if (ownerUid < 0) return
        val allowed = api.allowed.get(config) as? List<*>
        val denied = api.denied.get(config) as? List<*>
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (boot < 0 || published.lineSequence().firstOrNull() != "#boot:$boot") return
        val routes = RouteRuleBuilder.parseRuleCache(published).filterValues { it == iface }
        if (routes.isEmpty() && !state.injected) return

        @Suppress("UNCHECKED_CAST")
        val oldRanges = api.getUids.invoke(oldCaps) as? Set<Range<Int>> ?: return
        @Suppress("UNCHECKED_CAST")
        val native = api.nativeRanges.invoke(vpn, userId, allowed, denied) as Set<Range<Int>>
        val desired = native.toMutableSet()
        val users = context.getSystemService(UserManager::class.java) ?: return
        val cloneUsers = mutableMapOf<Int, Boolean>()
        for (uid in routes.keys) {
            if (uid / 100000 == userId || uid % 100000 == ownerUid % 100000) continue
            val targetUser = uid / 100000
            val isClone = cloneUsers.getOrPut(targetUser) {
                val info = api.getUserInfo.invoke(users, targetUser) ?: return@getOrPut false
                val parent = api.getProfileParent.invoke(users, targetUser) ?: return@getOrPut false
                api.userType.get(info) == "android.os.usertype.profile.CLONE" &&
                    api.profileId.getInt(parent) == userId
            }
            if (!isClone || native.any { it.contains(uid) }) continue
            val packages = context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
            if (packages.isEmpty() || packages.any { it in denied.orEmpty() }) continue
            if (allowed != null && packages.any { it !in allowed }) continue
            desired.add(Range(uid, uid))
        }
        val hasInjection = desired != native
        if (desired == oldRanges || (!hasInjection && !state.injected)) {
            state.injected = hasInjection
            return
        }

        val builder = api.capBuilder.newInstance(oldCaps)
        api.setUids.invoke(builder, desired)
        val newCaps = api.build.invoke(builder)
        try {
            api.caps.set(vpn, newCaps)
            api.send.invoke(null, agent, newCaps)
            state.injected = hasInjection
            xposed.log(Log.INFO, HOOK_TAG, "[VpnUid] Updated clone UID ranges for $iface")
        } catch (t: Throwable) {
            runCatching {
                api.caps.set(vpn, oldCaps)
                api.send.invoke(null, agent, oldCaps)
            }.onFailure { logFailure(it) }
            throw t
        }
    }

    private fun logFailure(t: Throwable) {
        xposed.log(Log.WARN, HOOK_TAG, "[VpnUid] VPN range update unavailable: ${t.cause ?: t}")
    }

    private class Platform(type: Class<*>) {
        val agent = type.field("mNetworkAgent")
        val iface = type.field("mInterface")
        val config = type.field("mConfig")
        val caps = type.field("mNetworkCapabilities")
        val lockdown = type.field("mLockdown")
        val runner = type.field("mVpnRunner")
        val context = type.field("mContext")
        val userId = type.field("mUserId")
        val ownerUid = type.field("mOwnerUID")
        val looper = type.field("mLooper")
        val allowed = config.type.field("allowedApplications")
        val denied = config.type.field("disallowedApplications")
        val getUserInfo = UserManager::class.java.method("getUserInfo", Int::class.javaPrimitiveType!!)
        val getProfileParent = UserManager::class.java.method("getProfileParent", Int::class.javaPrimitiveType!!)
        val userType = getUserInfo.returnType.field("userType")
        val profileId = getUserInfo.returnType.field("id")
        val getUids = caps.type.method("getUids")
        val nativeRanges = type.method(
            "createUserAndRestrictedProfilesRanges", Int::class.javaPrimitiveType!!, List::class.java, List::class.java
        )
        val capBuilder = Class.forName("android.net.NetworkCapabilities\$Builder")
            .getDeclaredConstructor(NetworkCapabilities::class.java).apply { isAccessible = true }
        val setUids = capBuilder.declaringClass.method("setUids", Set::class.java)
        val build = capBuilder.declaringClass.method("build")
        val send = type.method("doSendNetworkCapabilities", agent.type, caps.type)

        private fun Class<*>.field(name: String) = getDeclaredField(name).apply { isAccessible = true }
        private fun Class<*>.method(name: String, vararg params: Class<*>) =
            getDeclaredMethod(name, *params).apply { isAccessible = true }
    }
}
