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
import java.net.URL
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

        runProbe()
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
            }.toString()
            main.post { output.text = report }
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
        appendLine("egress (https://myip.ipip.net):")
        try {
            val conn = (URL("https://myip.ipip.net").openConnection() as HttpURLConnection).apply {
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
}
