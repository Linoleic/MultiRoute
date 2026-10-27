package com.multiroute.probe

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Development probe. Reports, from inside a real app:
 *
 * - which networks and transports this app *sees* (this is what MultiRoute's system_server hooks
 *   influence, and what a shell cannot observe for another UID), and
 * - what actually happens to its traffic: whether name resolution works, and which public IP the
 *   egress probe sees.
 *
 * Comparing the two is how "the app sees the assigned channel" and "the traffic really leaves through
 * it" are verified together, for example while a VPN is active.
 *
 * This is a debugging aid, not part of the module: it hooks nothing and ships in no release.
 */
class ProbeActivity : Activity() {

    private lateinit var output: TextView
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val refresh = Button(this).apply {
            text = "Refresh"
            setOnClickListener { runProbe() }
        }
        output = TextView(this).apply {
            textSize = 12f
            setPadding(32, 32, 32, 32)
            setTextIsSelectable(true)
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(refresh)
            addView(output)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        runProbe()
    }

    private fun runProbe() {
        output.text = "probing…"
        Thread {
            val report = StringBuilder().apply {
                appendLine("uid=${Process.myUid()}  pkg=$packageName")
                appendLine("time=${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}")
                appendLine("--- what this app sees ---")
                appendNetworks()
                appendLine()
                appendLine("--- what actually happens ---")
                appendResolve()
                appendEgress()
                appendLine("PROBE_COMPLETE")
            }.toString()
            main.post {
                output.text = report
                // Keep the diagnostic readable over ADB even when the activity is behind keyguard.
                // Reports stay in this test app's private storage and disappear on uninstall.
                runCatching { java.io.File(filesDir, "last-probe.txt").writeText(report) }
            }
        }.start()
    }

    private fun StringBuilder.appendNetworks() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        appendLine(describe("active", cm.activeNetwork, cm))
        @Suppress("DEPRECATION")
        cm.allNetworks.forEach { appendLine(describe("all", it, cm)) }
    }

    private fun describe(tag: String, network: Network?, cm: ConnectivityManager): String {
        if (network == null) return "  $tag: (none)"
        val caps = cm.getNetworkCapabilities(network)
        val lp = cm.getLinkProperties(network)
        val transports = listOfNotNull(
            "wifi".takeIf { caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true },
            "cell".takeIf { caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true },
            "vpn".takeIf { caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true },
            "eth".takeIf { caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true }
        ).joinToString("+").ifEmpty { "?" }
        val dns = lp?.dnsServers?.joinToString(",") { it.hostAddress ?: "?" }.orEmpty()
        return "  $tag: $network [$transports] iface=${lp?.interfaceName} dns=[$dns]"
    }

    private fun StringBuilder.appendResolve() {
        appendLine("DNS www.example.com:")
        try {
            val addrs = InetAddress.getAllByName("www.example.com")
            appendLine("  ok -> " + addrs.joinToString(" ") { it.hostAddress ?: "?" })
        } catch (t: Throwable) {
            appendLine("  FAILED -> ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun StringBuilder.appendEgress() {
        val endpoint = intent.getStringExtra("egress_url")?.takeIf { it.startsWith("https://") }
            ?: "https://myip.ipip.net"
        appendLine("egress ($endpoint):")
        try {
            if (intent.getBooleanExtra("ipv4_only", false) || intent.getBooleanExtra("ipv6_only", false) ||
                intent.hasExtra("bind_iface") || intent.hasExtra("destination_ip") || intent.getBooleanExtra("resolve_only", false)) {
                appendSocketEgress(URL(endpoint))
                return
            }
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "MultiRoute-probe")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            appendLine("  HTTP ${conn.responseCode}: ${body.take(140)}")
        } catch (t: Throwable) {
            appendLine("  FAILED -> ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Force the address family while keeping UID routing and TLS hostname verification intact. */
    private fun StringBuilder.appendSocketEgress(url: URL) {
        val cm = getSystemService(ConnectivityManager::class.java)
        val iface = intent.getStringExtra("bind_iface")
        @Suppress("DEPRECATION")
        val network = iface?.let { requested ->
            requireNotNull(cm.allNetworks.firstOrNull { cm.getLinkProperties(it)?.interfaceName == requested }) {
                "Requested interface is absent: $requested"
            }
        }
        appendLine("  Socket binding: ${network?.let { "$it iface=$iface" } ?: "none"}")
        val pinned = intent.getStringExtra("destination_ip")
        val addresses = if (pinned != null) arrayOf(InetAddress.getByName(pinned))
            else network?.getAllByName(url.host) ?: InetAddress.getAllByName(url.host)
        appendLine("  resolved: ${addresses.joinToString(" ") { it.hostAddress.orEmpty() }}")
        if (intent.getBooleanExtra("resolve_only", false)) return
        val ipv6 = intent.getBooleanExtra("ipv6_only", false)
        val address = addresses.first { if (ipv6) it is Inet6Address else it is Inet4Address }
        appendLine("  ${if (ipv6) "IPv6" else "IPv4"} destination: ${address.hostAddress}")
        Socket().use { plain ->
            network?.bindSocket(plain)
            plain.connect(InetSocketAddress(address, if (url.port > 0) url.port else 443), 8000)
            appendLine("  Socket local: ${plain.localSocketAddress}")
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            (factory.createSocket(plain, url.host, plain.port, true) as SSLSocket).use { tls ->
                tls.soTimeout = 8000
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                tls.startHandshake()
                val path = url.file.ifEmpty { "/" }
                tls.outputStream.write("GET $path HTTP/1.1\r\nHost: ${url.host}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                tls.outputStream.flush()
                val response = tls.inputStream.bufferedReader().use { it.readText() }
                val code = response.substringBefore("\r\n").split(' ').getOrNull(1)
                val body = response.substringAfter("\r\n\r\n").trim()
                appendLine("  HTTP $code: ${body.take(140)}")
            }
        }
    }
}
