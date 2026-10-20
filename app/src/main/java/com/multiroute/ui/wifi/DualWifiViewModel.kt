package com.multiroute.ui.wifi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.multiroute.wifi.DualWifiManager
import com.multiroute.wifi.DualWifiStatus
import com.multiroute.wifi.WifiScanItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DualWifiUiState(
    val status: DualWifiStatus = DualWifiStatus(),
    val isActivating: Boolean = false,
    val activationResult: String? = null,
    val pendingPasswordItem: WifiScanItem? = null,
    val isAutoReconnect: Boolean = true,
    val selectedScheme: com.multiroute.wifi.DualWifiScheme = com.multiroute.wifi.DualWifiScheme.AUTO,
    val infoMessage: String? = null
)

class DualWifiViewModel(application: Application) : AndroidViewModel(application) {

    val status: StateFlow<DualWifiStatus> = DualWifiManager.status

    private val _uiState = MutableStateFlow(
        DualWifiUiState(
            isAutoReconnect = com.multiroute.data.RouteConfigProvider.isDualWifiAutoReconnectEnabled(application),
            selectedScheme = com.multiroute.wifi.DualWifiScheme.fromId(com.multiroute.data.RouteConfigProvider.getDualWifiScheme(application))
        )
    )
    val uiState: StateFlow<DualWifiUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            DualWifiManager.status.collect { s ->
                _uiState.update { it.copy(status = s) }
            }
        }
        checkCapability()
    }

    fun checkCapability() {
        viewModelScope.launch {
            DualWifiManager.checkCapability(getApplication())
            scanNetworks()
        }
    }

    fun onSelectScheme(scheme: com.multiroute.wifi.DualWifiScheme) {
        com.multiroute.data.RouteConfigProvider.setDualWifiScheme(getApplication(), scheme.id)
        _uiState.update { it.copy(selectedScheme = scheme) }
        viewModelScope.launch {
            DualWifiManager.checkCapability(getApplication())
        }
    }

    fun activateDualWifi() {
        viewModelScope.launch {
            _uiState.update { it.copy(isActivating = true, activationResult = null) }
            val ok = DualWifiManager.activateDualWifi(getApplication(), _uiState.value.selectedScheme)
            _uiState.update {
                it.copy(
                    isActivating = false,
                    activationResult = if (ok) "激活成功！" else "激活失败，请检查 Root 授权"
                )
            }
        }
    }

    fun clearBlocklist() {
        viewModelScope.launch {
            DualWifiManager.clearNetworkBlocklist()
            _uiState.update { it.copy(infoMessage = "已清除 Wi-Fi 临时限制，可重新尝试连接") }
            DualWifiManager.checkCapability(getApplication())
        }
    }

    fun scanNetworks() {
        viewModelScope.launch {
            DualWifiManager.scanWifiNetworks(getApplication())
        }
    }

    fun onSelectScanItem(item: WifiScanItem) {
        if (item.isSaved || !item.isSecure) {
            connectSecondary(item, item.savedPassword)
        } else {
            _uiState.update { it.copy(pendingPasswordItem = item) }
        }
    }

    fun dismissPasswordDialog() {
        _uiState.update { it.copy(pendingPasswordItem = null) }
    }

    fun connectSecondary(item: WifiScanItem, password: String?) {
        _uiState.update { it.copy(pendingPasswordItem = null) }
        DualWifiManager.connectSecondaryWifiAsync(
            context = getApplication(),
            ssid = item.ssid,
            passphrase = password,
            frequencyMhz = item.frequencyMhz,
            bssid = item.bssid,
            capabilities = item.capabilities
        )
    }

    fun disconnectSecondary() {
        DualWifiManager.disconnectSecondaryWifi(getApplication())
    }

    fun setAutoReconnect(enabled: Boolean) {
        com.multiroute.data.RouteConfigProvider.setDualWifiAutoReconnectEnabled(getApplication(), enabled)
        _uiState.update { it.copy(isAutoReconnect = enabled) }
    }

    fun openSystemWifiSettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        getApplication<Application>().startActivity(intent)
    }

    fun clearActivationResult() {
        _uiState.update { it.copy(activationResult = null) }
    }

    fun clearInfoMessage() {
        _uiState.update { it.copy(infoMessage = null) }
    }
}
