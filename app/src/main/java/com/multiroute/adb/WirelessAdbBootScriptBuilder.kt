package com.multiroute.adb

/** Telemetry persisted for the latest boot-time wireless ADB restore attempt. */
data class AdbBootStatus(
    val bootCount: Int?,
    val uptimeSeconds: Long?,
    val source: String?,
    val mode: AdbMode?,
    val port: Int?,
    val result: AdbActionResult?,
    val detail: String?
)

/**
 * Pure builder and parser for the root `/data/adb/service.d/02-multiroute-adb.sh` boot-restore
 * script and `/data/adb/multiroute/adb_boot_status` telemetry file.
 *
 * Deployed when boot restore is enabled so cold-boot recovery does not depend on OEM-restricted
 * `BOOT_COMPLETED` cold-starts (e.g., HyperOS/MIUI AutoStart blocks) or `JobScheduler` delays,
 * while enforcing the exact same unlock, primary-SSID, and 10-minute boot-window guards.
 */
internal object WirelessAdbBootScriptBuilder {
    const val BOOT_SCRIPT_PATH = "/data/adb/service.d/02-multiroute-adb.sh"
    const val BOOT_STATUS_PATH = "/data/adb/multiroute/adb_boot_status"
    const val BOOT_LOG_PATH = "/data/adb/multiroute/last_adb_boot.log"
    const val BOOT_WINDOW_SECONDS = 600L

    internal fun shellSingleQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    fun buildBootScript(
        ssid: String,
        mode: AdbMode,
        customPort: Int,
        policyFilePath: String,
        statusPath: String = BOOT_STATUS_PATH,
        logPath: String = BOOT_LOG_PATH,
        bootWindowSeconds: Long = BOOT_WINDOW_SECONDS
    ): String {
        val validPort = if (customPort in 1024..65535) customPort else 5555
        val quotedSsid = shellSingleQuote(ssid)
        val quotedPolicy = shellSingleQuote(policyFilePath)
        val quotedStatus = shellSingleQuote(statusPath)
        val quotedLog = shellSingleQuote(logPath)
        val logDir = logPath.substringBeforeLast('/', "/data/adb/multiroute")
        val quotedLogDir = shellSingleQuote(logDir)

        return """
#!/system/bin/sh
# MultiRoute boot-time wireless ADB recovery
# Generated automatically - do not edit by hand.
mkdir -p $quotedLogDir
(
  : > $quotedLog
  TARGET_SSID=$quotedSsid
  TARGET_MODE='${mode.name}'
  TARGET_PORT='$validPort'
  POLICY_PATH=$quotedPolicy
  STATUS_PATH=$quotedStatus

  log() {
    echo "[$(/system/bin/date '+%Y-%m-%d %H:%M:%S')] $*"
  }

  uptime_sec() {
    /system/bin/cut -d. -f1 /proc/uptime 2>/dev/null || echo 999999
  }

  write_status() {
    _bc=$(settings get global boot_count 2>/dev/null | /system/bin/tr -dc '0-9')
    _up=$(uptime_sec)
    cat << STATUS_EOF > "${'$'}{STATUS_PATH}.tmp"
boot_count=${'$'}_bc
uptime_sec=${'$'}_up
source=service.d
mode=${'$'}TARGET_MODE
port=${'$'}{1:-${'$'}TARGET_PORT}
result=${'$'}2
detail=${'$'}3
STATUS_EOF
    mv "${'$'}{STATUS_PATH}.tmp" "${'$'}STATUS_PATH"
  }

  read_primary_ssid() {
    _wifi=$(cmd wifi status 2>/dev/null) || return 1
    printf '%s\n' "${'$'}_wifi" | /system/bin/awk '
      /^====/ {
        if (index(${'$'}0, "ROLE_CLIENT_PRIMARY") > 0) { in_primary = 1 }
        else { in_primary = 0; seen_other = 1 }
        next
      }
      (in_primary || !seen_other) && /^Wifi is connected to ".*"${'$'}/ {
        s = ${'$'}0
        sub(/^Wifi is connected to "/, "", s)
        sub(/"${'$'}/, "", s)
        if (s != "" && s != "<unknown ssid>") { print s; exit }
      }
    '
  }

  find_adbd_port() {
    _exclude="${'$'}1"
    /system/bin/ss -lntp 2>/dev/null | /system/bin/awk -v ex="${'$'}_exclude" '
      /^LISTEN/ && index(${'$'}0, "\"adbd\"") > 0 {
        addr = ${'$'}4
        sub(/^.*:/, "", addr)
        if (addr ~ /^[0-9]+${'$'}/ && addr != ex) { print addr; exit }
      }
    '
  }

  is_trusted_network() {
    dumpsys adb 2>/dev/null | /system/bin/grep -qa 'is_trusted_network=true'
  }

  log "Starting wireless ADB boot recovery (mode=${'$'}TARGET_MODE port=${'$'}TARGET_PORT)"

  while [ "$(uptime_sec)" -lt $bootWindowSeconds ]; do
    _boot=$(getprop sys.boot_completed 2>/dev/null)
    _ce=$(getprop sys.user.0.ce_available 2>/dev/null)
    if [ "${'$'}_boot" != "1" ] || [ "${'$'}_ce" != "true" ] || [ ! -r "${'$'}POLICY_PATH" ]; then
      write_status "" "WAIT_UNLOCK" "LOCKED"
      sleep 3
      continue
    fi

    if ! /system/bin/tr -d ' \n\r\t' < "${'$'}POLICY_PATH" | /system/bin/grep -q '"enabled":true'; then
      log "Policy is disabled in CE store; exiting"
      write_status "" "INACTIVE" "DISABLED"
      exit 0
    fi

    _dev=$(settings get global development_settings_enabled 2>/dev/null)
    if [ "${'$'}_dev" != "1" ]; then
      log "Developer options disabled; aborting"
      write_status "" "FAILED" "DEV_OFF"
      exit 0
    fi

    _ssid=$(read_primary_ssid)
    if [ "${'$'}_ssid" != "${'$'}TARGET_SSID" ]; then
      write_status "" "WAIT_NETWORK" "SSID_MISMATCH"
      sleep 5
      continue
    fi

    _adb_wifi=$(settings get global adb_wifi_enabled 2>/dev/null)
    _tcp_prop=$(getprop service.adb.tcp.port 2>/dev/null)

    if [ "${'$'}TARGET_MODE" = "TLS" ]; then
      if [ "${'$'}_adb_wifi" = "1" ] && { [ -z "${'$'}_tcp_prop" ] || [ "${'$'}_tcp_prop" = "-1" ]; }; then
        _tls_port=$(getprop service.adb.tls.port 2>/dev/null)
        if [ -z "${'$'}_tls_port" ]; then _tls_port=$(find_adbd_port ""); fi
        if [ -n "${'$'}_tls_port" ] && /system/bin/ss -lntp 2>/dev/null | /system/bin/grep -Eq ":${'$'}{_tls_port}[[:space:]].*\"adbd\""; then
          _detail="ALREADY_ACTIVE"
          if is_trusted_network; then _detail="ALREADY_ACTIVE_TRUSTED"; fi
          log "TLS already listening on port ${'$'}_tls_port (${'$'}_detail)"
          write_status "${'$'}_tls_port" "READY" "${'$'}_detail"
          exit 0
        fi
      fi

      case "${'$'}_tcp_prop" in
        ''|-1|0)
          setprop service.adb.tcp.port -1
          setprop persist.adb.tcp.port ""
          ;;
        *)
          setprop service.adb.tcp.port -1
          setprop persist.adb.tcp.port ""
          setprop ctl.restart adbd
          sleep 0.8
          ;;
      esac
      settings put global adb_wifi_enabled 0
      sleep 0.2
      settings put global adb_wifi_enabled 1

      _tries=0
      while [ "${'$'}_tries" -lt 12 ]; do
        _tls_port=$(getprop service.adb.tls.port 2>/dev/null)
        if [ -z "${'$'}_tls_port" ]; then _tls_port=$(find_adbd_port ""); fi
        if [ -n "${'$'}_tls_port" ] && /system/bin/ss -lntp 2>/dev/null | /system/bin/grep -Eq ":${'$'}{_tls_port}[[:space:]].*\"adbd\""; then
          log "TLS restored on port ${'$'}_tls_port"
          write_status "${'$'}_tls_port" "READY" "SWITCHED"
          exit 0
        fi
        _tries=$((_tries + 1))
        sleep 0.5
      done
      log "TLS restore timed out waiting for listener"
      write_status "" "FAILED" "TIMEOUT"
      exit 0
    else
      if [ "${'$'}_adb_wifi" = "0" ] && [ "${'$'}_tcp_prop" = "${'$'}TARGET_PORT" ] && /system/bin/ss -lntp 2>/dev/null | /system/bin/grep -Eq ":${'$'}{TARGET_PORT}[[:space:]].*\"adbd\""; then
        log "TCP already listening on port ${'$'}TARGET_PORT"
        write_status "${'$'}TARGET_PORT" "READY" "ALREADY_ACTIVE"
        exit 0
      fi

      _was_trusted=0
      if [ "${'$'}_adb_wifi" = "1" ] && is_trusted_network; then _was_trusted=1; fi

      settings put global adb_wifi_enabled 0
      sleep 0.2
      setprop persist.adb.tls_server.enable 0
      setprop service.adb.tcp.port "${'$'}TARGET_PORT"
      setprop persist.adb.tcp.port ""
      setprop ctl.restart adbd

      _tries=0
      while [ "${'$'}_tries" -lt 12 ]; do
        _adb_now=$(settings get global adb_wifi_enabled 2>/dev/null)
        if [ "${'$'}_adb_now" = "0" ] && /system/bin/ss -lntp 2>/dev/null | /system/bin/grep -Eq ":${'$'}{TARGET_PORT}[[:space:]].*\"adbd\""; then
          _detail="SWITCHED"
          if [ "${'$'}_was_trusted" = "1" ]; then _detail="SWITCHED_OVERRIDE"; fi
          log "TCP restored on port ${'$'}TARGET_PORT (${'$'}_detail)"
          write_status "${'$'}TARGET_PORT" "READY" "${'$'}_detail"
          exit 0
        fi
        _tries=$((_tries + 1))
        sleep 0.5
      done
      log "TCP restore timed out waiting for listener on ${'$'}TARGET_PORT"
      write_status "${'$'}TARGET_PORT" "FAILED" "TIMEOUT"
      exit 0
    fi
  done

  log "Boot recovery window expired"
  write_status "" "EXPIRED" "WINDOW_EXPIRED"
  exit 0
) >/dev/null 2>&1 &
exit 0
        """.trimIndent() + "\n"
    }

    fun formatStatus(
        bootCount: Int?,
        uptimeSeconds: Long?,
        source: String,
        mode: AdbMode,
        port: Int?,
        result: AdbActionResult,
        detail: String
    ): String = buildString {
        appendLine("boot_count=${bootCount?.toString().orEmpty()}")
        appendLine("uptime_sec=${uptimeSeconds?.toString().orEmpty()}")
        appendLine("source=$source")
        appendLine("mode=${mode.name}")
        appendLine("port=${port?.toString().orEmpty()}")
        appendLine("result=${result.name}")
        appendLine("detail=$detail")
    }

    fun parseStatus(lines: List<String>): AdbBootStatus? {
        if (lines.isEmpty()) return null
        val map = mutableMapOf<String, String>()
        for (raw in lines) {
            val idx = raw.indexOf('=')
            if (idx <= 0) continue
            map[raw.substring(0, idx).trim()] = raw.substring(idx + 1).trim()
        }
        if (map.isEmpty()) return null
        return AdbBootStatus(
            bootCount = map["boot_count"]?.toIntOrNull(),
            uptimeSeconds = map["uptime_sec"]?.toLongOrNull(),
            source = map["source"]?.takeIf { it.isNotEmpty() },
            mode = map["mode"]?.let { runCatching { AdbMode.valueOf(it) }.getOrNull() },
            port = map["port"]?.toIntOrNull()?.takeIf { it in 1..65535 },
            result = map["result"]?.let { runCatching { AdbActionResult.valueOf(it) }.getOrNull() },
            detail = map["detail"]?.takeIf { it.isNotEmpty() }
        )
    }
}
