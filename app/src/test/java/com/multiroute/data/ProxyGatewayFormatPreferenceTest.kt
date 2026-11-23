package com.multiroute.data

import com.multiroute.util.ProxyGatewayFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyGatewayFormatPreferenceTest {
    @Test
    fun defaultsToAndroidVpnFormatWhenCompatibleVpnIsAvailable() {
        assertEquals(
            ProxyGatewayFormat.MIHOMO_ANDROID_VPN,
            resolveGatewayFormat(saved = null, compatibleVpnAvailable = true)
        )
    }

    @Test
    fun defaultsToSingBoxWhenNoCompatibleVpnIsAvailable() {
        assertEquals(
            ProxyGatewayFormat.SING_BOX,
            resolveGatewayFormat(saved = null, compatibleVpnAvailable = false)
        )
    }

    @Test
    fun preservesSavedChoiceAndFallsBackWhenSavedVpnFormatIsUnavailable() {
        assertEquals(
            ProxyGatewayFormat.MIHOMO,
            resolveGatewayFormat(saved = ProxyGatewayFormat.MIHOMO, compatibleVpnAvailable = false)
        )
        assertEquals(
            ProxyGatewayFormat.SING_BOX,
            resolveGatewayFormat(saved = ProxyGatewayFormat.MIHOMO_ANDROID_VPN, compatibleVpnAvailable = false)
        )
        assertEquals(
            ProxyGatewayFormat.MIHOMO_ANDROID_VPN,
            resolveGatewayFormat(saved = ProxyGatewayFormat.MIHOMO_ANDROID_VPN, compatibleVpnAvailable = true)
        )
    }
}
