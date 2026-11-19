package com.chapay.homehub.compose.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chapay.homehub.compose.data.AppConfig
import com.chapay.homehub.compose.data.AppConfigStorage
import com.chapay.homehub.compose.data.StatusRepository
import com.chapay.homehub.compose.data.UnifiedStatus
import com.chapay.homehub.compose.data.VoltageLocalHistoryStore
import com.chapay.homehub.compose.service.EventPollingServiceController
import com.chapay.homehub.compose.widget.StatusWidgetProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DashboardUiState(
    // Лише перше завантаження і ручне оновлення (pull-to-refresh). Фонове
    // опитування спінер не вмикає - інакше при повільному зв'язку "кільце"
    // крутиться постійно.
    val loading: Boolean = true,
    val error: String? = null,
    val config: AppConfig = AppConfig(),
    // "Липкий" статус: модуль, що не відповів у цьому циклі, лишає свої
    // останні успішні дані (див. fetchOnce), а не зникає з екрана.
    val status: UnifiedStatus = UnifiedStatus(),
    val lastSuccessAtMs: Long = 0L,
    // Коли кожен модуль востаннє відповів; за цим UI димить застарілі картки.
    val inverterAtMs: Long = 0L,
    val loadAtMs: Long = 0L,
    val garageAtMs: Long = 0L,
    // Час останньої СПРОБИ опитування - змінює стан на кожному циклі, щоб UI
    // перераховував "застарілість" навіть коли всі запити провалюються.
    val lastAttemptAtMs: Long = 0L,
)

/** Стан екрана режимів інвертора. */
data class InverterSettingsUiState(
    // Іде читання (і на плитці "Сонце" крутиться індикатор до відкриття екрана).
    val loading: Boolean = false,
    // Іде запис нового режиму.
    val busy: Boolean = false,
    val settings: com.chapay.homehub.compose.data.InverterSettings? = null,
    val message: String? = null,
    val error: String? = null,
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    val repository = StatusRepository(application)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState

    private var pollingJob: Job? = null

    private val _inverterSettings = MutableStateFlow(InverterSettingsUiState())
    val inverterSettings: StateFlow<InverterSettingsUiState> = _inverterSettings

    /** Читає режим і пріоритети інвертора; викликається при натисканні на плитку, до відкриття екрана. */
    suspend fun loadInverterSettings() {
        _inverterSettings.update { it.copy(loading = true, error = null, message = null) }
        val settings = runCatching { repository.fetchInverterSettings(_uiState.value.config) }.getOrNull()
        _inverterSettings.update {
            it.copy(
                loading = false,
                settings = settings ?: it.settings,
                error = if (settings == null) "Інвертор не відповів на запит режимів (або прошивка застаріла)" else null,
            )
        }
    }

    fun setInverterOutputPriority(value: Int) = applyInverterPriority(output = value, charger = null, label = "Пріоритет виходу змінено")

    fun setInverterChargerPriority(value: Int) = applyInverterPriority(output = null, charger = value, label = "Пріоритет зарядки змінено")

    /** Авто-перемикання пріоритету виходу (SBU/сонце, п.2 переробки). */
    fun setInverterOutputAuto(enabled: Boolean) {
        if (_inverterSettings.value.busy) return
        viewModelScope.launch {
            _inverterSettings.update { it.copy(busy = true, error = null, message = null) }
            val result = runCatching { repository.setInverterOutputAuto(_uiState.value.config, enabled) }
                .getOrElse { com.chapay.homehub.compose.data.InverterSetResult(false, null, it.message ?: "Помилка") }
            _inverterSettings.update {
                if (result.ok) {
                    it.copy(busy = false, settings = result.settings ?: it.settings, message = if (enabled) "Авто-пріоритет увімкнено" else "Авто-пріоритет вимкнено", error = null)
                } else {
                    it.copy(busy = false, error = result.error ?: "Команда не виконана")
                }
            }
        }
    }

    private fun applyInverterPriority(output: Int?, charger: Int?, label: String) {
        if (_inverterSettings.value.busy) return
        viewModelScope.launch {
            _inverterSettings.update { it.copy(busy = true, error = null, message = null) }
            val result = runCatching { repository.setInverterPriority(_uiState.value.config, output, charger) }
                .getOrElse { com.chapay.homehub.compose.data.InverterSetResult(false, null, it.message ?: "Помилка") }
            _inverterSettings.update {
                if (result.ok) {
                    it.copy(busy = false, settings = result.settings ?: it.settings, message = label, error = null)
                } else {
                    it.copy(busy = false, error = result.error ?: "Команда не виконана")
                }
            }
        }
    }
    // Опитування не накладаються: pull-to-refresh чи команда під час фонового
    // циклу чекають його завершення замість паралельних дублікатів запитів.
    private val fetchMutex = Mutex()

    init {
        val stored = AppConfigStorage.load(application)
        _uiState.update { it.copy(config = stored) }
        if (stored.backgroundServiceEnabled) EventPollingServiceController.start(application)
        startPolling()
        startArchiveSync()
    }

    // Архів історії на телефоні: після запуску (з паузою, щоб не заважати першому
    // завантаженню) і далі раз на 30 хв підтягуємо закриті доби, яких ще нема, та
    // повідомляємо інвертору, що вони збережені - лише тоді він чистить свою пам'ять.
    private var archiveJob: Job? = null

    private fun startArchiveSync() {
        archiveJob?.cancel()
        archiveJob = viewModelScope.launch {
            delay(45_000L)
            while (true) {
                runCatching { repository.syncHistoryArchive(_uiState.value.config) }
                delay(30 * 60_000L)
            }
        }
    }

    fun setConfig(config: AppConfig) {
        val was = _uiState.value.config
        AppConfigStorage.save(getApplication(), config)
        if (config.backgroundServiceEnabled != was.backgroundServiceEnabled) {
            if (config.backgroundServiceEnabled) {
                EventPollingServiceController.start(getApplication())
            } else {
                EventPollingServiceController.stop(getApplication())
            }
        }
        // Інші адреси/вимкнені модулі - старі "липкі" дані більше не актуальні.
        _uiState.update {
            it.copy(
                config = config,
                status = UnifiedStatus(),
                inverterAtMs = 0L,
                loadAtMs = 0L,
                garageAtMs = 0L,
            )
        }
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
        viewModelScope.launch { fetchOnce(showSpinner = true) }
    }

    private suspend fun fetchOnce(showSpinner: Boolean = false) {
        if (showSpinner) _uiState.update { it.copy(loading = true) }
        fetchMutex.withLock {
            val config = _uiState.value.config
            // fetchUnified() ніколи не кидає виняток - модуль, що не відповів,
            // просто дає null у своєму полі.
            val unified = runCatching { repository.fetchUnified(config) }.getOrNull() ?: UnifiedStatus()
            val now = System.currentTimeMillis()
            val hasAnyData = unified.inverter != null || unified.loadController != null || unified.garage != null
            // Власна 24-год історія напруг/заряду на телефоні - підстраховка на
            // випадок, якщо модуль перезавантажився й втратив частину своєї
            // (навіть флеш-) історії між двома відкриттями графіка. Третє поле -
            // SOC АКБ (%), не напруга - інформативніше для цього графіка.
            unified.inverter?.let {
                VoltageLocalHistoryStore.recordIfDue(getApplication(), now, it.lineVoltage, it.pvVoltage, it.batterySoc)
            }

            var merged: UnifiedStatus? = null
            _uiState.update { cur ->
                // Конфіг змінили, поки йшов запит, - результат стосується старих
                // адрес, відкидаємо (setConfig уже запустив новий цикл).
                if (cur.config != config) return@update cur.copy(loading = false)
                val old = cur.status
                val status = UnifiedStatus(
                    inverter = unified.inverter ?: old.inverter,
                    loadController = unified.loadController ?: old.loadController,
                    garage = unified.garage ?: old.garage,
                    updatedAtMs = now,
                )
                merged = status
                cur.copy(
                    loading = false,
                    error = if (hasAnyData || !anyModuleEnabled(config)) null else "Немає відповіді від модулів",
                    status = status,
                    lastSuccessAtMs = if (hasAnyData) now else cur.lastSuccessAtMs,
                    inverterAtMs = if (unified.inverter != null) now else cur.inverterAtMs,
                    loadAtMs = if (unified.loadController != null) now else cur.loadAtMs,
                    garageAtMs = if (unified.garage != null) now else cur.garageAtMs,
                    lastAttemptAtMs = now,
                )
            }

            // Віджет оновлюємо лише коли цей цикл справді щось отримав: інакше
            // транзиентна помилка перетирала б віджет рисками поверх щойно
            // показаних реальних даних. Передаємо злитий (липкий) статус - щоб
            // модуль, що мигнув, не давав у віджеті риски.
            val toWidget = merged
            if (hasAnyData && toWidget != null && StatusWidgetProvider.hasActiveWidgets(getApplication())) {
                // triggerPulse=false: цей виклик спрацьовує на КОЖЕН цикл
                // живого опитування - пульс тут зайвий і планує ще один
                // незалежний мережевий запит через 3.2с (StatusWidgetRefreshWorker).
                StatusWidgetProvider.updateAllWidgets(getApplication(), toWidget, triggerPulse = false)
            }
        }
    }

    private fun anyModuleEnabled(config: AppConfig) =
        config.inverterEnabled || config.loadControllerEnabled || config.garageEnabled

    fun setInverterGridMode(mode: String) {
        submitCommand { repository.setInverterGridMode(_uiState.value.config, mode) }
    }

    fun setInverterLoadMode(mode: String) {
        submitCommand { repository.setInverterLoadMode(_uiState.value.config, mode) }
    }

    // NONE|ON|OFF - фіксація довгим натисканням (п.3а переробки). Короткий тап
    // на ON/OFF лишається звичайною тимчасовою зміною режиму (setXMode вище).
    fun setLoadLock(mode: String) {
        submitCommand { repository.setInverterLoadLock(_uiState.value.config, mode) }
    }

    fun setBoiler1Mode(mode: String) {
        submitCommand { repository.setBoiler1Mode(_uiState.value.config, mode) }
    }

    fun setBoiler1Lock(mode: String) {
        submitCommand { repository.setBoiler1Lock(_uiState.value.config, mode) }
    }

    fun setPumpMode(mode: String) {
        submitCommand { repository.setPumpMode(_uiState.value.config, mode) }
    }

    fun setPumpLock(mode: String) {
        submitCommand { repository.setPumpLock(_uiState.value.config, mode) }
    }

    fun setBoiler2Mode(mode: String) {
        submitCommand { repository.setBoiler2Mode(_uiState.value.config, mode) }
    }

    fun setBoiler2Lock(mode: String) {
        submitCommand { repository.setBoiler2Lock(_uiState.value.config, mode) }
    }

    fun setBoiler1AutoWindow(enabled: Boolean, start: String, end: String) {
        submitCommand { repository.setBoiler1AutoWindow(_uiState.value.config, enabled, start, end) }
    }

    fun setPumpAutoWindow(enabled: Boolean, start: String, end: String) {
        submitCommand { repository.setPumpAutoWindow(_uiState.value.config, enabled, start, end) }
    }

    fun setBoiler2AutoWindow(enabled: Boolean, start: String, end: String) {
        submitCommand { repository.setBoiler2AutoWindow(_uiState.value.config, enabled, start, end) }
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
        archiveJob?.cancel()
        super.onCleared()
    }
}
