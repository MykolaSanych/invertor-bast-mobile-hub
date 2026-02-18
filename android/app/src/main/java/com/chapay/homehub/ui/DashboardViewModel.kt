package com.chapay.homehub.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapay.homehub.data.AppConfig
import com.chapay.homehub.data.StatusRepository
import com.chapay.homehub.data.UnifiedStatus
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
)

class DashboardViewModel(
    private val repository: StatusRepository = StatusRepository(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState

    private var pollingJob: Job? = null

    fun setConfig(config: AppConfig) {
        _uiState.update { it.copy(config = config) }
    }

    fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                _uiState.update { it.copy(loading = true, error = null) }
                runCatching { repository.fetchUnified(_uiState.value.config) }
                    .onSuccess { unified ->
                        _uiState.update {
                            it.copy(
                                loading = false,
                                error = null,
                                status = unified,
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
                delay((_uiState.value.config.pollIntervalSec.coerceIn(2, 60) * 1000L))
            }
        }
    }

    fun refreshNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching { repository.fetchUnified(_uiState.value.config) }
                .onSuccess { unified ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            error = null,
                            status = unified,
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
        submitCommand { repository.triggerGate(_uiState.value.config) }
    }

    private fun submitCommand(action: suspend () -> Boolean) {
        viewModelScope.launch {
            val ok = runCatching { action() }.getOrDefault(false)
            if (!ok) {
                _uiState.update { it.copy(error = "Команда не виконана") }
            }
            refreshNow()
        }
    }

    override fun onCleared() {
        pollingJob?.cancel()
        super.onCleared()
    }
}
