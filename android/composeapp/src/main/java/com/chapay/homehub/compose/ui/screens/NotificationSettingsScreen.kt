package com.chapay.homehub.compose.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chapay.homehub.compose.data.AppConfig
import com.chapay.homehub.compose.ui.theme.SurfaceContainer
import com.chapay.homehub.compose.ui.theme.TextMuted

private data class NotifyItem(
    val label: String,
    val get: (AppConfig) -> Boolean,
    val with: (AppConfig, Boolean) -> AppConfig,
)

private data class NotifyGroup(val card: String, val items: List<NotifyItem>)

// Перевантаження/захист АКБ - один спільний перемикач (notifyPowerOverload) на
// всі реле: бойлер1/насос ділять один фізичний захист load_controller, тож це не
// помилка дублювання, а те, як насправді влаштована прошивка (єдиний контур).
private val OVERLOAD_ITEM = NotifyItem(
    "Перевантаження / захист АКБ",
    { it.notifyPowerOverload },
    { c, v -> c.copy(notifyPowerOverload = v) },
)

private val GROUPS = listOf(
    NotifyGroup(
        "СОНЦЕ",
        listOf(NotifyItem("Поява/зникнення генерації", { it.notifyPvGeneration }, { c, v -> c.copy(notifyPvGeneration = v) })),
    ),
    NotifyGroup(
        "МЕРЕЖА",
        listOf(
            NotifyItem("Наявність напруги мережі", { it.notifyGridPresence }, { c, v -> c.copy(notifyGridPresence = v) }),
            NotifyItem("Реле мережі увімк./вимк.", { it.notifyGridRelay }, { c, v -> c.copy(notifyGridRelay = v) }),
            NotifyItem("Зміна режиму", { it.notifyGridMode }, { c, v -> c.copy(notifyGridMode = v) }),
        ),
    ),
    NotifyGroup(
        "НАВАНТАЖЕННЯ",
        listOf(
            NotifyItem("Зміна режиму", { it.notifyLoadMode }, { c, v -> c.copy(notifyLoadMode = v) }),
            OVERLOAD_ITEM,
        ),
    ),
    NotifyGroup("БОЙЛЕР 1", listOf(NotifyItem("Зміна режиму", { it.notifyBoiler1Mode }, { c, v -> c.copy(notifyBoiler1Mode = v) }), OVERLOAD_ITEM)),
    NotifyGroup("НАСОС", listOf(NotifyItem("Зміна режиму", { it.notifyPumpMode }, { c, v -> c.copy(notifyPumpMode = v) }), OVERLOAD_ITEM)),
    NotifyGroup("БОЙЛЕР 2", listOf(NotifyItem("Зміна режиму", { it.notifyBoiler2Mode }, { c, v -> c.copy(notifyBoiler2Mode = v) }), OVERLOAD_ITEM)),
    NotifyGroup("ВОРОТА", listOf(NotifyItem("Відчинено/зачинено", { it.notifyGateState }, { c, v -> c.copy(notifyGateState = v) }))),
    NotifyGroup(
        "НАСОС SIRIO",
        listOf(
            NotifyItem("Аварія (розшифрована)", { it.notifySirioError }, { c, v -> c.copy(notifySirioError = v) }),
            NotifyItem("Запуск/зупинка", { it.notifySirioMotor }, { c, v -> c.copy(notifySirioMotor = v) }),
        ),
    ),
    NotifyGroup(
        "ЗАГАЛЬНЕ",
        listOf(NotifyItem("Модуль не відповідає", { it.notifyModuleOffline }, { c, v -> c.copy(notifyModuleOffline = v) })),
    ),
)

/**
 * Дерево "картка -> повідомлення, які вона надсилає": кожна група відповідає
 * картці дашборда (СОНЦЕ/МЕРЕЖА/НАВАНТАЖЕННЯ/...), розгортається дотиком, а
 * всередині - перемикачі саме тих notify*-полів AppConfig, які ця картка реально
 * може надіслати (фонова служба - [com.chapay.homehub.compose.service.EventPollingService]
 * - перевіряє їх перед кожним сповіщенням).
 *
 * "Перевантаження/захист АКБ" повторюється під кількома картками навмисно - у
 * прошивці бойлер1/насос і так ділять один фізичний захисний контур, тож це
 * один і той самий перемикач (notifyPowerOverload), а не випадковий дубль.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(config: AppConfig, onSave: (AppConfig) -> Unit, onBack: () -> Unit) {
    var working by remember { mutableStateOf(config) }
    var expanded by remember { mutableStateOf(setOf("СОНЦЕ")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Сповіщення") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                "Для кожної картки дашборда - які саме її події надсилати сповіщенням. " +
                    "Фонова служба має бути увімкнена (перемикач вище), інакше сповіщення не прийдуть, " +
                    "навіть якщо тут усе позначено.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(12.dp))

            GROUPS.forEach { group ->
                val isExpanded = group.card in expanded
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { expanded = if (isExpanded) expanded - group.card else expanded + group.card }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(group.card, style = MaterialTheme.typography.titleSmall)
                        Icon(
                            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (isExpanded) "Згорнути" else "Розгорнути",
                        )
                    }
                    if (isExpanded) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            group.items.forEach { item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(item.label, style = MaterialTheme.typography.bodyMedium)
                                    Switch(
                                        checked = item.get(working),
                                        onCheckedChange = { working = item.with(working, it) },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onSave(working); onBack() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Зберегти")
            }
        }
    }
}
