package com.chapay.homehub.compose.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chapay.homehub.compose.data.AppConfig
import com.chapay.homehub.compose.data.AppConfigStorage
import com.chapay.homehub.compose.data.StatusRepository
import com.chapay.homehub.compose.data.UnifiedStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val config: AppConfig = AppConfig(),
    val status: UnifiedStatus = UnifiedStatus(),
    val lastSuccessAtMs: Long = 0L,
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = StatusRepository(application)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState

    private var pollingJob: Job? = null

    init {
        val stored = AppConfigStorage.load(application)
        _uiState.update { it.copy(config = stored) }
        startPolling()
    }

    fun setConfig(config: AppConfig) {
        AppConfigStorage.save(getApplication(), config)
        _uiState.update { it.copy(config = config) }
        refreshNow()
    }

    fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                fetchOnce()
                delay(_uiState.value.config.pollIntervalSec.coerceIn(2, 60) * 1000L)
            }
        }
    }

    fun refreshNow() {
        viewModelScope.launch { fetchOnce() }
    }

    private suspend fun fetchOnce() {
        _uiState.update { it.copy(loading = true, error = null) }
        runCatching { repository.fetchUnified(_uiState.value.config) }
            .onSuccess { unified ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = null,
                        status = unified,
                        lastSuccessAtMs = System.currentTimeMillis(),
                    )
                }
            }
            .onFailure { err ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = err.message ?: "Помилка запиту",
                    )
                }
            }
    }

    fun setInverterGridMode(mode: String) {
        submitCommand { repository.setInverterGridMode(_uiState.value.config, mode) }
    }

    fun setInverterLoadMode(mode: String) {
        submitCommand { repository.setInverterLoadMode(_uiState.value.config, mode) }
    }

    fun setBoiler1Mode(mode: String) {
        submitCommand { repository.setBoiler1Mode(_uiState.value.config, mode) }
    }

    fun setPumpMode(mode: String) {
        submitCommand { repository.setPumpMode(_uiState.value.config, mode) }
    }

    fun setBoiler2Mode(mode: String) {
        submitCommand { repository.setBoiler2Mode(_uiState.value.config, mode) }
    }

    fun triggerGate() {
        submitCommand {
            repository.triggerGate(
                config = _uiState.value.config,
                source = "compose_hub",
                reason = "compose hub",
            )
        }
    }

    fun toggleGarageLight() {
        submitCommand {
            repository.toggleGarageLight(
                config = _uiState.value.config,
                source = "compose_hub",
                reason = "compose hub",
            )
        }
    }

    private fun submitCommand(action: suspend () -> Boolean) {
        viewModelScope.launch {
            val ok = runCatching { action() }.getOrDefault(false)
            if (!ok) {
                _uiState.update { it.copy(error = "Команда не виконана") }
            }
            fetchOnce()
        }
    }

    override fun onCleared() {
        pollingJob?.cancel()
        super.onCleared()
    }
}
