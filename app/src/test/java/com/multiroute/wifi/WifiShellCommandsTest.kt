package com.multiroute.wifi

import org.junit.Assert.*
import org.junit.Test

class WifiShellCommandsTest {
    @Test fun requestDeclaresInternetInsteadOfLocalOnly() {
        assertEquals("cmd wifi add-request 'Guest Wi-Fi' open -d 2 -i", WifiShellCommands.internetRequest("Guest Wi-Fi", "open", null, null, 2412))
        assertEquals("Guest Wi-Fi_2g_internet", WifiShellCommands.internetRequestKey("Guest Wi-Fi", 2412))
    }
    @Test fun passwordAndSsidAreLiteralShellArguments() {
        val cmd = WifiShellCommands.internetRequest("AP's \$HOME", "wpa2", "pw'\$(id);x", null, 5180)
        assertTrue(cmd.contains("'AP'\"'\"'s \$HOME'"))
        assertTrue(cmd.contains("'pw'\"'\"'\$(id);x'"))
    }
    @Test fun disconnectOnlyNamesTheRequestedSsid() {
        val cmd = WifiShellCommands.removeRequest("AP.* 'two'")
        assertEquals("cmd wifi remove-request 'AP.* '\"'\"'two'\"'\"''", cmd)
        assertFalse(cmd.contains("forget-network"))
        assertFalse(cmd.contains("remove-all-requests"))
    }
    @Test(expected = IllegalArgumentException::class) fun unsupportedRequestSecurityFailsBeforeShell() {
        WifiShellCommands.internetRequest("AP", "wep", "password", null, 2412)
    }
    @Test(expected = IllegalArgumentException::class) fun secureNetworkRequiresPassword() {
        WifiShellCommands.internetRequest("AP", "wpa2", null, null, 2412)
    }
    @Test fun requestKeysIncludeTheSelectedBand() {
        assertEquals("AP_5g_internet", WifiShellCommands.internetRequestKey("AP", 5260))
        assertEquals("AP_6g_internet", WifiShellCommands.internetRequestKey("AP", 6115))
    }
    @Test(expected = IllegalArgumentException::class) fun unknownBandIsNotSilentlyLocalOnly() {
        WifiShellCommands.internetRequest("AP", "open", null, null, 0)
    }
    @Test(expected = IllegalArgumentException::class) fun ssidCannotBecomeALeadingShellOption() {
        WifiShellCommands.internetRequest("-s", "open", null, null, 2412)
    }
}
