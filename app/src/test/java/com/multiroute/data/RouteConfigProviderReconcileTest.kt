package com.multiroute.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteConfigProviderReconcileTest {

    @Test
    fun `reconcileUidRulesMap prunes uninstalled apps, updates changed UIDs, and drops orphans`() {
        val packageRules = mapOf(
            "com.example.kept" to "wlan0",
            "com.example.reinstalled" to "rmnet_data0",
            "com.example.uninstalled" to "tun0",
            "com.example.clone@999" to "wlan1",
            "com.example.cloneUninstalled@999" to "wlan0"
        )
        val existingUidRules = mapOf(
            10100 to "wlan0",
            10101 to "rmnet_data0",   // old UID before reinstall
            10102 to "tun0",          // uninstalled primary app
            10999 to "wlan1",         // orphan UID without package rule
            99910200 to "wlan1",      // installed clone
            99910201 to "wlan0"       // uninstalled clone
        )

        val result = RouteConfigProvider.reconcileUidRulesMap(
            packageRules = packageRules,
            existingUidRules = existingUidRules,
            resolveUid = { key ->
                when (key) {
                    "com.example.kept" -> 10100
                    "com.example.reinstalled" -> 10250
                    "com.example.clone@999" -> 99910200
                    else -> -1
                }
            },
            secondaryLookupFailed = false
        )

        assertEquals(
            mapOf(
                10100 to "wlan0",
                10250 to "rmnet_data0",
                99910200 to "wlan1"
            ),
            result
        )
    }

    @Test
    fun `reconcileUidRulesMap preserves secondary UIDs when secondary scan fails while still pruning primary`() {
        val packageRules = mapOf(
            "com.example.kept" to "wlan0",
            "com.example.uninstalled" to "tun0",
            "com.example.clone@999" to "wlan1"
        )
        val existingUidRules = mapOf(
            10100 to "wlan0",
            10102 to "tun0",
            99910200 to "wlan1"
        )

        val result = RouteConfigProvider.reconcileUidRulesMap(
            packageRules = packageRules,
            existingUidRules = existingUidRules,
            resolveUid = { key ->
                when (key) {
                    "com.example.kept" -> 10100
                    else -> -1
                }
            },
            secondaryLookupFailed = true
        )

        assertEquals(
            mapOf(
                10100 to "wlan0",
                99910200 to "wlan1"
            ),
            result
        )
    }
}
