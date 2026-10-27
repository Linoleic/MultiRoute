package com.multiroute.wifi

import org.junit.Assert.*
import org.junit.Test

class WifiShellCommandsTest {
    @Test fun slaveBridgeQuotesApkAndBssidWithoutPassingCredentials() {
        assertEquals("CLASSPATH='/data/app/AP'\"'\"'s/base.apk' app_process / com.multiroute.wifi.SlaveWifiBridge connect 13 'ap'\"'\"'\$(id)'",
            WifiShellCommands.slaveConnect("/data/app/AP's/base.apk", 13, "ap'\$(id)"))
    }
    @Test fun savedNetworkIdZeroAndUnspecifiedBssidAreValidForSlaveBridge() {
        assertEquals("CLASSPATH='/data/app/base.apk' app_process / com.multiroute.wifi.SlaveWifiBridge connect 0",
            WifiShellCommands.slaveConnect("/data/app/base.apk", 0, null))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidSlaveNetworkIdFailsBeforeDispatch() {
        WifiShellCommands.slaveConnect("/data/app/base.apk", -1, null)
    }
    @Test fun slaveDisconnectHasOneBridgeInvocation() {
        assertEquals("CLASSPATH='/data/app/base.apk' app_process / com.multiroute.wifi.SlaveWifiBridge disconnect",
            WifiShellCommands.slaveDisconnect("/data/app/base.apk"))
    }
    @Test fun savedRequestUsesBandAndBssidWithoutCredentialsOrSsid() {
        assertEquals("cmd wifi add-request -s -b '12:34:56:78:9a:bc' -d 2 -i",
            WifiShellCommands.savedInternetRequest("12:34:56:78:9a:bc", 2412))
        assertEquals("NoSsid_2g_internet", WifiShellCommands.savedInternetRequestKey(2412))
    }
    @Test fun passwordAndSsidAreLiteralShellArguments() {
        assertEquals("'AP'\"'\"'s \$HOME'", WifiShellCommands.quote("AP's \$HOME"))
        assertEquals("'pw'\"'\"'\$(id);x'", WifiShellCommands.quote("pw'\$(id);x"))
    }
    @Test fun disconnectOnlyNamesTheRequestedSsid() {
        val cmd = WifiShellCommands.removeRequest("AP.* 'two'")
        assertEquals("cmd wifi remove-request 'AP.* '\"'\"'two'\"'\"''", cmd)
        assertFalse(cmd.contains("forget-network"))
        assertFalse(cmd.contains("remove-all-requests"))
    }
    @Test(expected = IllegalArgumentException::class) fun malformedBssidFailsBeforeShell() {
        WifiShellCommands.savedInternetRequest("12:34:56:78:9a:bc; id", 2412)
    }
    @Test(expected = IllegalArgumentException::class) fun multicastBssidCannotSelectAnAccessPoint() {
        WifiShellCommands.savedInternetRequest("ff:ff:ff:ff:ff:ff", 2412)
    }
    @Test fun requestKeysIncludeTheSelectedBand() {
        assertEquals("NoSsid_5g_internet", WifiShellCommands.savedInternetRequestKey(5260))
        assertEquals("NoSsid_6g_internet", WifiShellCommands.savedInternetRequestKey(6115))
        assertTrue(WifiShellCommands.savedInternetRequest("12:34:56:78:9a:bc", 6115).endsWith("-d 6 -i"))
    }
    @Test(expected = IllegalArgumentException::class) fun unknownBandIsNotSilentlyLocalOnly() {
        WifiShellCommands.savedInternetRequest("12:34:56:78:9a:bc", 0)
    }
    @Test(expected = IllegalArgumentException::class) fun placeholderBssidCannotSelectAnAccessPoint() {
        WifiShellCommands.savedInternetRequest("02:00:00:00:00:00", 2412)
    }
    @Test(expected = IllegalArgumentException::class) fun emptyBssidCannotBecomeAnUnpinnedBandRequest() {
        WifiShellCommands.savedInternetRequest("", 2412)
    }
}
