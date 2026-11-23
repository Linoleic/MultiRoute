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

/**
 * Adjusts active VpnService UID ranges: injects explicitly VPN-routed clone-profile UIDs and
 * subtracts UIDs explicitly assigned to other (non-VPN) channels.
 */
internal class VpnUidRoutingHook(private val xposed: XposedModule) {
    private class TrackedVpn(val handler: Handler) {
        var overridden = false
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
            xposed.log(Log.INFO, HOOK_TAG, "[VpnUid] VPN UID routing hook installed")
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
        val allRoutes = RouteRuleBuilder.parseRuleCache(published)
        val vpnRouteUids = allRoutes.filterValues { it == iface }.keys
        val excludedUids = allRoutes.filterValues { it != iface }.keys
        if (vpnRouteUids.isEmpty() && excludedUids.isEmpty() && !state.overridden) return

        @Suppress("UNCHECKED_CAST")
        val oldRanges = api.getUids.invoke(oldCaps) as? Set<Range<Int>> ?: return
        @Suppress("UNCHECKED_CAST")
        val native = api.nativeRanges.invoke(vpn, userId, allowed, denied) as Set<Range<Int>>
        val users = context.getSystemService(UserManager::class.java) ?: return
        val allowedSet = allowed?.mapNotNull { it as? String }?.toSet()
        val deniedSet = denied?.mapNotNull { it as? String }?.toSet().orEmpty()
        val decision = VpnUidRangePolicy.evaluate(
            routeUids = vpnRouteUids,
            excludedUids = excludedUids,
            hostUserId = userId,
            ownerUid = ownerUid,
            allowedPackages = allowedSet,
            deniedPackages = deniedSet,
            nativeRanges = native.map { it.lower..it.upper },
            oldRanges = oldRanges.map { it.lower..it.upper },
            wasOverridden = state.overridden,
            isCloneUser = { targetUser ->
                val info = api.getUserInfo.invoke(users, targetUser) ?: return@evaluate false
                val parent = api.getProfileParent.invoke(users, targetUser) ?: return@evaluate false
                VpnUidRangePolicy.isEligibleCloneUser(
                    targetUserId = targetUser,
                    hostUserId = userId,
                    userType = api.userType.get(info) as? String,
                    profileParentId = api.profileId.getInt(parent)
                )
            },
            packagesForUid = { uid ->
                context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
            }
        )
        if (!decision.shouldPublish) {
            state.overridden = decision.hasOverride
            return
        }
        val desired = decision.desiredRanges.mapTo(LinkedHashSet(decision.desiredRanges.size)) {
            Range(it.first, it.last)
        }

        val builder = api.capBuilder.newInstance(oldCaps)
        api.setUids.invoke(builder, desired)
        val newCaps = api.build.invoke(builder)
        try {
            api.caps.set(vpn, newCaps)
            api.send.invoke(null, agent, newCaps)
            state.overridden = decision.hasOverride
            xposed.log(
                Log.INFO,
                HOOK_TAG,
                "[VpnUid] Updated UID ranges for $iface (injected=${decision.injectedUids.size}, excluded=${decision.excludedUids.size})"
            )
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
