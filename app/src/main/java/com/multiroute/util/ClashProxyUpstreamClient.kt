package com.multiroute.util

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/** Controls the opt-in selector on a Clash-compatible IPv4 loopback controller. */
internal class ClashProxyUpstreamClient(port: Int, private val secret: String) {
    init {
        require(port in 1..65535)
    }

    private val controller = URL("http://127.0.0.1:$port")

    data class Selector(val current: String, val members: Set<String>)

    enum class Failure { AUTH, MISSING_SELECTOR, INVALID_SELECTOR, CONNECTION, UNCONFIRMED }

    class ControllerException(val failure: Failure) : IOException()

    fun listSelectors(): List<String> {
        val reply = request(URL(controller, "/proxies"), "GET", HttpURLConnection.HTTP_OK) { connection ->
            readBody(connection, MAX_LIST_RESPONSE_BYTES)
        }
        try {
            val proxies = JSONObject(reply).getJSONObject("proxies")
            return proxies.keys().asSequence()
                .filter { name -> isValidGroup(name) && proxies.optJSONObject(name)?.optString("type") == "Selector" }
                .sorted()
                .toList()
        } catch (_: JSONException) {
            fail(Failure.INVALID_SELECTOR)
        }
    }

    fun read(group: String = SELECTOR): Selector {
        if (!isValidGroup(group)) fail(Failure.INVALID_SELECTOR)
        val url = URL(controller, "/proxies/${encodePathSegment(group)}")
        val reply = request(url, "GET", HttpURLConnection.HTTP_OK) { connection ->
            readBody(connection, MAX_RESPONSE_BYTES)
        }
        try {
            val payload = JSONObject(reply)
            if (payload.optString("type") != "Selector") fail(Failure.INVALID_SELECTOR)
            val all = payload.getJSONArray("all")
            val members = buildSet {
                for (index in 0 until all.length()) {
                    val member = all.get(index) as? String ?: fail(Failure.INVALID_SELECTOR)
                    add(member)
                }
            }
            val current = payload.get("now") as? String ?: fail(Failure.INVALID_SELECTOR)
            if (current.isBlank() || current !in members) fail(Failure.INVALID_SELECTOR)
            return Selector(current, members)
        } catch (_: JSONException) {
            fail(Failure.INVALID_SELECTOR)
        }
    }

    fun select(member: String): Selector = select(SELECTOR, member)

    fun select(group: String, member: String): Selector {
        val selector = read(group)
        if (member !in selector.members) fail(Failure.INVALID_SELECTOR)
        if (member == selector.current) return selector

        val body = JSONObject().put("name", member).toString().toByteArray(Charsets.UTF_8)
        try {
            request(URL(controller, "/proxies/${encodePathSegment(group)}"), "PUT", HttpURLConnection.HTTP_NO_CONTENT, body) { }
        } catch (e: ControllerException) {
            if (e.failure == Failure.CONNECTION) fail(Failure.UNCONFIRMED)
            throw e
        }
        val confirmed = try {
            read(group)
        } catch (_: ControllerException) {
            fail(Failure.UNCONFIRMED)
        }
        if (confirmed.current != member) fail(Failure.UNCONFIRMED)
        return confirmed
    }

    private fun readBody(connection: HttpURLConnection, maxBytes: Int): String =
        connection.inputStream.use { input ->
            val buffer = ByteArray(4096)
            val response = ByteArrayOutputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (response.size() + count > maxBytes) fail(Failure.INVALID_SELECTOR)
                response.write(buffer, 0, count)
            }
            response.toString(Charsets.UTF_8.name())
        }

    private fun isValidGroup(value: String): Boolean = value.isNotBlank() && value != "." && value != ".."

    private fun encodePathSegment(value: String): String {
        val hex = "0123456789ABCDEF"
        return buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val code = byte.toInt() and 0xff
                if (code in 'a'.code..'z'.code || code in 'A'.code..'Z'.code ||
                    code in '0'.code..'9'.code || code == '-'.code || code == '_'.code || code == '~'.code) {
                    append(code.toChar())
                } else {
                    append('%').append(hex[code shr 4]).append(hex[code and 0x0f])
                }
            }
        }
    }

    private fun <T> request(
        url: URL,
        method: String,
        expectedStatus: Int,
        body: ByteArray? = null,
        onSuccess: (HttpURLConnection) -> T
    ): T {
        val connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = method
            if (secret.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $secret")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            return when (connection.responseCode) {
                expectedStatus -> onSuccess(connection)
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> fail(Failure.AUTH)
                HttpURLConnection.HTTP_NOT_FOUND -> fail(Failure.MISSING_SELECTOR)
                else -> fail(Failure.CONNECTION)
            }
        } catch (e: ControllerException) {
            throw e
        } catch (_: IOException) {
            fail(Failure.CONNECTION)
        } finally {
            connection.disconnect()
        }
    }

    private fun fail(failure: Failure): Nothing = throw ControllerException(failure)

    companion object {
        const val SELECTOR = "MultiRoute-Upstream"
        private const val TIMEOUT_MS = 2500
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val MAX_LIST_RESPONSE_BYTES = 1024 * 1024
    }
}
