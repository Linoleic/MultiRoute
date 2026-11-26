package com.multiroute.ui.main

import com.multiroute.model.AppItem
import com.multiroute.model.CHANNEL_DEFAULT
import org.junit.Assert.assertEquals
import org.junit.Test

class AppRoutingSortTest {

    @Test
    fun sortInstalledAppsOrdersProbeConfiguredUserSystemAndAlphabetical() {
        val input = listOf(
            AppItem("com.sys.b", "SysB", null, 1001, CHANNEL_DEFAULT, isSystemApp = true),
            AppItem("com.user.z", "Zebra", null, 10002, CHANNEL_DEFAULT, isSystemApp = false),
            AppItem("com.user.a", "Alpha", null, 10001, CHANNEL_DEFAULT, isSystemApp = false),
            AppItem("com.sys.configured", "SysConfigured", null, 1002, "wlan0", isSystemApp = true),
            AppItem("com.user.configured", "UserConfigured", null, 10003, "rmnet_data0", isSystemApp = false),
            AppItem("com.multiroute.probe", "ProbeClone", null, 99910004, CHANNEL_DEFAULT, isSystemApp = false, userId = 999),
            AppItem("com.multiroute.probe", "ProbeMain", null, 10004, CHANNEL_DEFAULT, isSystemApp = false, userId = 0)
        )

        val sorted = sortInstalledApps(input)

        assertEquals(
            listOf(
                "com.multiroute.probe@0",
                "com.multiroute.probe@999",
                "com.user.configured@0",
                "com.sys.configured@0",
                "com.user.a@0",
                "com.user.z@0",
                "com.sys.b@0"
            ),
            sorted.map { "${it.packageName}@${it.userId}" }
        )
    }
}
