package com.example.lantorrentvideo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("connection", 0)
    private val api = LanApi()

    private val _server = MutableStateFlow(prefs.getString("server", "") ?: "")
    val server: StateFlow<String> = _server.asStateFlow()
    private val _token = MutableStateFlow(prefs.getString("token", "") ?: "")
    val token: StateFlow<String> = _token.asStateFlow()
    private val _state = MutableStateFlow<UiState>(UiState.Ready(emptyList()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun connect(server: String = _server.value, token: String = _token.value) {
        val normalized = api.normalizeServer(server)
        if (normalized.isBlank()) {
            _state.value = UiState.Error("请输入服务器地址")
            return
        }
        _server.value = normalized
        _token.value = token
        prefs.edit().putString("server", normalized).putString("token", token).apply()
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = try {
                UiState.Ready(api.listVideos(normalized, token))
            } catch (e: Exception) {
                UiState.Error(e.message ?: "连接失败")
            }
        }
    }

    fun discovered(address: String) {
        if (_server.value.isBlank()) {
            _server.value = address
            connect(address, _token.value)
        }
    }
}
