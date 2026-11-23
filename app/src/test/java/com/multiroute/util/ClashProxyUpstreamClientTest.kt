package com.multiroute.util

import com.multiroute.util.ClashProxyUpstreamClient.ControllerException
import com.multiroute.util.ClashProxyUpstreamClient.Failure
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class ClashProxyUpstreamClientTest {
    @Test
    fun discoversOnlySelectorGroupsFromLargeProxiesResponse() {
        val proxies = JSONObject()
            .put("auto", JSONObject().put("type", "Selector"))
            .put("manual", JSONObject().put("type", "Selector"))
            .put("direct", JSONObject().put("type", "Direct"))
        proxies.put("padding", JSONObject().put("type", "Unknown").put("data", "x".repeat(70 * 1024)))
        val response = JSONObject().put("proxies", proxies).toString()
        assertTrue(response.toByteArray(Charsets.UTF_8).size > 64 * 1024)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/proxies") { exchange ->
            assertEquals("/proxies", exchange.requestURI.path)
            exchange.reply(200, response)
        }
        server.start()
        try {
            val client = ClashProxyUpstreamClient(server.address.port, "")
            assertEquals(setOf("auto", "manual"), client.listSelectors().toSet())
            assertEquals(setOf("auto", "direct", "manual", "padding"), client.listProxyNames())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun readsAndSelectsNamedGroupsUsingSingleEncodedPathSegment() {
        val groupName = "A B/分流"
        val expectedPath = "/proxies/A%20B%2F%E5%88%86%E6%B5%81"
        var active = "wlan0"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/proxies") { exchange ->
            assertEquals(expectedPath, exchange.requestURI.rawPath)
            if (exchange.requestMethod == "PUT") {
                active = JSONObject(exchange.requestBody.bufferedReader().readText()).getString("name")
                exchange.reply(204)
            } else exchange.reply(200, selector(active))
        }
        server.start()
        try {
            val client = ClashProxyUpstreamClient(server.address.port, "")
            assertEquals("wlan0", client.read(groupName).current)
            assertEquals("wlan1", client.select(groupName, "wlan1").current)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun rejectsDotSegmentsBeforeMakingARequest() {
        val client = ClashProxyUpstreamClient(1, "")
        assertEquals(Failure.INVALID_SELECTOR, failure { client.read(".") })
        assertEquals(Failure.INVALID_SELECTOR, failure { client.select("..", "wlan1") })
    }

    @Test
    fun rejectsSecretsWithControlCharactersOrLineBreaks() {
        assertThrows(IllegalArgumentException::class.java) {
            ClashProxyUpstreamClient(9090, "tok\r\nX-Injected: 1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClashProxyUpstreamClient(9090, "tok\n")
        }
    }

    @Test
    fun onlySelectsAdvertisedInterfacesAndReadsBackTheResult() {
        var active = "wlan0"
        val writes = AtomicInteger()
        withController({ exchange ->
            if (exchange.requestMethod == "PUT") {
                active = JSONObject(exchange.requestBody.bufferedReader().readText()).getString("name")
                writes.incrementAndGet()
                exchange.reply(204)
            } else {
                exchange.reply(200, selector(active))
            }
        }) { client ->
            assertEquals("wlan0", client.read().current)
            assertEquals(Failure.INVALID_SELECTOR, failure { client.select("missing") })
            assertEquals("wlan0", client.select("wlan0").current)
            assertEquals(0, writes.get())
            assertEquals("wlan1", client.select("wlan1").current)
            assertEquals(1, writes.get())
        }
    }

    @Test
    fun doesNotReportSuccessIfControllerReadbackStillSelectsOriginalInterface() {
        withController({ exchange ->
            if (exchange.requestMethod == "PUT") exchange.reply(204)
            else exchange.reply(200, selector("wlan0"))
        }) { client ->
            assertEquals(Failure.UNCONFIRMED, failure { client.select("wlan1") })
        }
    }

    @Test
    fun authenticatesOnlyToTheConfiguredControllerAndRejectsRedirects() {
        val redirected = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(SELECTOR_PATH) { exchange ->
            if (exchange.requestHeaders.getFirst("Authorization") != "Bearer local-token") {
                exchange.reply(401)
            } else {
                exchange.responseHeaders.set("Location", "/redirect")
                exchange.reply(302)
            }
        }
        server.createContext("/redirect") { exchange ->
            redirected.incrementAndGet()
            exchange.reply(200, selector("wlan0"))
        }
        server.start()
        try {
            assertEquals(Failure.AUTH, failure { ClashProxyUpstreamClient(server.address.port, "").read() })
            assertEquals(Failure.CONNECTION, failure {
                ClashProxyUpstreamClient(server.address.port, "local-token").read()
            })
            assertEquals(0, redirected.get())
        } finally {
            server.stop(0)
        }
    }

    private fun withController(handler: (HttpExchange) -> Unit, test: (ClashProxyUpstreamClient) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(SELECTOR_PATH, handler)
        server.start()
        try {
            test(ClashProxyUpstreamClient(server.address.port, ""))
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.reply(status: Int, body: String? = null) = use { exchange ->
        val bytes = body?.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes?.size?.toLong() ?: -1L)
        if (bytes != null) exchange.responseBody.write(bytes)
    }

    private fun failure(action: () -> Unit): Failure =
        assertThrows(ControllerException::class.java) { action() }.failure

    private fun selector(current: String): String =
        """{"type":"Selector","now":"$current","all":["wlan0","wlan1"]}"""

    companion object {
        private const val SELECTOR_PATH = "/proxies/${ClashProxyUpstreamClient.SELECTOR}"
    }
}
