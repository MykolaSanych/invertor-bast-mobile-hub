package com.chapay.homehub.compose.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chapay.homehub.compose.data.AppConfig
import com.chapay.homehub.compose.data.StatusRepository
import com.chapay.homehub.compose.data.UnifiedStatus
import com.chapay.homehub.compose.data.VoltageHistoryPoint
import com.chapay.homehub.compose.ui.components.ChartToolbarActions
import com.chapay.homehub.compose.ui.components.ChartViewport
import com.chapay.homehub.compose.ui.components.NavGranularity
import com.chapay.homehub.compose.ui.components.PeriodNavHeader
import com.chapay.homehub.compose.ui.components.VisibilityToggle
import com.chapay.homehub.compose.ui.components.chartGestures
import com.chapay.homehub.compose.ui.components.rememberChartViewport
import com.chapay.homehub.compose.ui.components.todayIso
import com.chapay.homehub.compose.ui.theme.AccentBattery
import com.chapay.homehub.compose.ui.theme.AccentBoiler
import com.chapay.homehub.compose.ui.theme.AccentGate
import com.chapay.homehub.compose.ui.theme.AccentGrid
import com.chapay.homehub.compose.ui.theme.AccentLoad
import com.chapay.homehub.compose.ui.theme.AccentPump
import com.chapay.homehub.compose.ui.theme.AccentPv
import com.chapay.homehub.compose.ui.theme.OutlineSubtle
import com.chapay.homehub.compose.ui.theme.SurfaceContainer
import com.chapay.homehub.compose.ui.theme.SurfaceContainerHigh
import com.chapay.homehub.compose.ui.theme.TextMuted
import com.chapay.homehub.compose.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private enum class EnergyPeriod(val label: String, val granularity: NavGranularity) {
    DAY("Доба", NavGranularity.DAY),
    MONTH("Місяць", NavGranularity.MONTH),
    YEAR("Рік", NavGranularity.YEAR),
}

// ---- Каталог параметрів поза енергією (клімат/напруга/АКБ/час роботи) ----
// Усі обрані ряди - енергія й ці додаткові - малюються РАЗОМ на одному графіку
// (UnifiedLineChart нижче), не окремими картками: коли одиниці різні, вісь Y
// стає відносною (кожен ряд - за власним діапазоном видимого вікна), а точні
// значення - в підказці під пальцем. Окремим полем лишається лише хронологія.
private enum class ParamGroup(val title: String, val unit: String) {
    TEMP("Температура", "°C"),
    HUMIDITY("Вологість", "%"),
    PRESSURE("Тиск", "гПа"),
    VOLTAGE("Напруга", "В"),
    BATTERY_SOC("Заряд АКБ", "%"),
    UPTIME("Час роботи (% доби)", "%"),
}

// Кольори зон клімату - НЕ ті самі accent'и, що в енергії/уставок: інвертор-зона
// клімату (температура ПОВІТРЯ біля модуля) - інша сутність, ніж "Сонце"
// (потужність PV), хоч фізично поруч; однаковий колір був би сплутаним.
private val AccentClimateInverter = Color(0xFFE0729C)
private val AccentClimateStreet = Color(0xFF4DD0E1)

private data class ParamSeriesDef(
    val id: String,
    val label: String,
    val group: ParamGroup,
    val color: Color,
    // Доба/Місяць/Рік - де у цього ряду реально є дані (інакше пункт меню
    // показується, але неактивним на недоступному періоді).
    val availablePeriods: Set<EnergyPeriod>,
)

private val PARAM_CATALOG = listOf(
    ParamSeriesDef("temp_inv", "Інвертор", ParamGroup.TEMP, AccentClimateInverter, setOf(EnergyPeriod.DAY, EnergyPeriod.MONTH, EnergyPeriod.YEAR)),
    ParamSeriesDef("temp_ext", "Вулиця", ParamGroup.TEMP, AccentClimateStreet, setOf(EnergyPeriod.DAY, EnergyPeriod.MONTH, EnergyPeriod.YEAR)),
    ParamSeriesDef("hum_inv", "Інвертор", ParamGroup.HUMIDITY, AccentClimateInverter, setOf(EnergyPeriod.DAY, EnergyPeriod.MONTH, EnergyPeriod.YEAR)),
    ParamSeriesDef("hum_ext", "Вулиця", ParamGroup.HUMIDITY, AccentClimateStreet, setOf(EnergyPeriod.DAY, EnergyPeriod.MONTH, EnergyPeriod.YEAR)),
    ParamSeriesDef("press_inv", "Інвертор", ParamGroup.PRESSURE, AccentClimateInverter, setOf(EnergyPeriod.DAY, EnergyPeriod.MONTH, EnergyPeriod.YEAR)),
    ParamSeriesDef("press_ext", "Вулиця", ParamGroup.PRESSURE, AccentClimateStreet, setOf(EnergyPeriod.DAY, EnergyPeriod.MONTH, EnergyPeriod.YEAR)),
    ParamSeriesDef("volt_grid", "Мережа", ParamGroup.VOLTAGE, AccentGrid, setOf(EnergyPeriod.DAY)),
    ParamSeriesDef("volt_pv", "Сонце", ParamGroup.VOLTAGE, AccentPv, setOf(EnergyPeriod.DAY)),
    ParamSeriesDef("soc_battery", "АКБ", ParamGroup.BATTERY_SOC, AccentBattery, setOf(EnergyPeriod.DAY)),
    ParamSeriesDef("up_boiler1", "Бойлер 1", ParamGroup.UPTIME, AccentBoiler, setOf(EnergyPeriod.MONTH, EnergyPeriod.YEAR)),
    ParamSeriesDef("up_pump", "Насос", ParamGroup.UPTIME, AccentPump, setOf(EnergyPeriod.MONTH, EnergyPeriod.YEAR)),
    ParamSeriesDef("up_grid", "Мережа", ParamGroup.UPTIME, AccentGrid, setOf(EnergyPeriod.MONTH, EnergyPeriod.YEAR)),
    ParamSeriesDef("up_pv", "Сонце", ParamGroup.UPTIME, AccentPv, setOf(EnergyPeriod.MONTH, EnergyPeriod.YEAR)),
    ParamSeriesDef("up_boiler2", "Бойлер 2 (гараж)", ParamGroup.UPTIME, AccentGate, setOf(EnergyPeriod.MONTH, EnergyPeriod.YEAR)),
)

// Точка графіка. x - позиція на осі періоду: хвилина початку інтервалу (доба),
// день місяця - 1 (місяць) або номер місяця - 1 (рік). Значення: середня
// потужність, Вт (доба), або енергія, кВт·год (місяць/рік).
private data class ChartPoint(val x: Int, val pv: Double, val home: Double, val grid: Double)

// Що потрібно графіку від осі X залежно від періоду.
private class XAxis(
    val domainMax: Float,
    val pointWidth: Float,
    // Найбільша прогалина (в одиницях осі), яку лінія перекриває пунктиром: пропущені
    // точки - збій запису/зв'язку, а не справжній розрив. Більші прогалини - розрив.
    val maxBridge: Float,
    val minSpan: Float,
    val tickSteps: IntArray,
    val maxTicks: Float,
    val tickLabel: (Int) -> String,
    val pointTitle: (Int) -> String,
    // Вісь Y - енергія в кВт·год (дробові підписи), а не потужність у Вт.
    val energyUnits: Boolean,
) {
    val unit: String get() = if (energyUnits) "кВт·год" else "Вт"
}

private val MONTH_SHORT = listOf("Січ", "Лют", "Бер", "Кві", "Тра", "Чер", "Лип", "Сер", "Вер", "Жов", "Лис", "Гру")
private val MONTH_FULL = listOf(
    "Січень", "Лютий", "Березень", "Квітень", "Травень", "Червень",
    "Липень", "Серпень", "Вересень", "Жовтень", "Листопад", "Грудень",
)

// Заголовок шапки замість громіздкого рядка керування, який тепер під "сендвічем".
private fun periodTitleFor(period: EnergyPeriod, anchor: String): String = when (period) {
    EnergyPeriod.DAY -> anchor
    EnergyPeriod.MONTH -> {
        val month = anchor.substring(5, 7).toIntOrNull() ?: 1
        "${MONTH_FULL.getOrElse(month - 1) { "" }} ${anchor.take(4)}"
    }
    EnergyPeriod.YEAR -> anchor.take(4)
}

private fun daysIn(year: Int, month: Int): Int {
    val cal = java.util.Calendar.getInstance()
    cal.set(year, month - 1, 1)
    return cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
}

private fun buildAxis(period: EnergyPeriod, stepMinutes: Int, anchor: String): XAxis = when (period) {
    // Секунди доби (не хвилини) - та сама одиниця, що й у TimelineBody нижче, тож
    // обидва графіки поділяють один viewport і одну лінійку часу (див. dayViewport
    // в EnergyChartScreen). tickSteps/maxTicks тут не беруть участі в прорисовці
    // DAY (showXAxisLabels=false - кроки сітки бере tickStepFor з TimelineScreen),
    // лишені для узгодженості типу XAxis.
    EnergyPeriod.DAY -> XAxis(
        domainMax = ((1440 - stepMinutes) * 60).coerceAtLeast(1).toFloat(),
        pointWidth = (stepMinutes * 60).toFloat(),
        maxBridge = stepMinutes * 60 * 6f,
        minSpan = (stepMinutes * 60 * 2).toFloat(),
        tickSteps = TICK_STEPS,
        maxTicks = 8f,
        tickLabel = { hm(it) },
        pointTitle = { "${hm(it)}-${hm(it + stepMinutes * 60)}" },
        energyUnits = false,
    )
    EnergyPeriod.MONTH -> {
        val year = anchor.take(4).toIntOrNull() ?: 2000
        val month = anchor.substring(5, 7).toIntOrNull() ?: 1
        XAxis(
            domainMax = (daysIn(year, month) - 1).coerceAtLeast(1).toFloat(),
            pointWidth = 1f,
            maxBridge = 3f,
            minSpan = 4f,
            tickSteps = intArrayOf(1, 2, 3, 5, 7),
            maxTicks = 12f,
            tickLabel = { (it + 1).toString() },
            pointTitle = { "%d.%02d.%d".format(it + 1, month, year) },
            energyUnits = true,
        )
    }
    EnergyPeriod.YEAR -> XAxis(
        domainMax = 11f,
        pointWidth = 1f,
        maxBridge = 1f,
        minSpan = 3f,
        tickSteps = intArrayOf(1),
        maxTicks = 12f,
        tickLabel = { MONTH_SHORT.getOrElse(it) { "" } },
        pointTitle = { "${MONTH_FULL.getOrElse(it) { "" }} ${anchor.take(4)}" },
        energyUnits = true,
    )
}

private class LoadedPeriod(val points: List<ChartPoint>, val stepMinutes: Int, val totalsWh: Triple<Double, Double, Double>)

// /api/daily: 15-хв слоти (pv_w/home_w/grid_w) або, для старої прошивки, годинні Вт·год.
private fun parseDay(payload: JSONObject): LoadedPeriod {
    val slotsArr = payload.optJSONArray("slots")
    val parsed = mutableListOf<ChartPoint>()
    var step = 60
    if (slotsArr != null && slotsArr.length() > 0) {
        for (i in 0 until slotsArr.length()) {
            val row = slotsArr.optJSONObject(i) ?: continue
            parsed.add(
                ChartPoint(
                    x = row.optInt("m", i * 15) * 60,
                    pv = row.optDouble("pv_w", 0.0),
                    home = row.optDouble("home_w", 0.0),
                    grid = row.optDouble("grid_w", 0.0),
                ),
            )
        }
        step = 15
    } else {
        val hoursArr = payload.optJSONArray("hours")
        if (hoursArr != null) {
            for (i in 0 until hoursArr.length()) {
                val row = hoursArr.optJSONObject(i) ?: continue
                parsed.add(
                    ChartPoint(
                        x = row.optInt("hour", i) * 3600,
                        pv = row.optDouble("pv", 0.0),
                        home = row.optDouble("home", 0.0),
                        grid = row.optDouble("grid", 0.0),
                    ),
                )
            }
        }
    }
    val t = payload.optJSONObject("totals")
    return LoadedPeriod(
        points = parsed,
        stepMinutes = step,
        totalsWh = Triple(
            t?.optDouble("dailyPV", 0.0) ?: 0.0,
            t?.optDouble("dailyHome", 0.0) ?: 0.0,
            t?.optDouble("dailyGrid", 0.0) ?: 0.0,
        ),
    )
}

// /api/monthly: {"month","days":[{"day","pv","home","grid",...}]} у Вт·год за добу, плюс
// архів на телефоні (закриті доби, які модуль міг уже видалити зі своєї пам'яті). Для доби
// беремо архів, а якщо його немає - те, що віддав модуль. Доба без жодного виміру (усі
// нулі) - "немає даних", а не "нуль": точку пропускаємо.
private fun parseMonth(
    payload: JSONObject?,
    archive: Map<String, Triple<Double, Double, Double>>,
    month: String,
): LoadedPeriod {
    val perDay = sortedMapOf<Int, Triple<Double, Double, Double>>()
    val days = payload?.optJSONArray("days")
    if (days != null) {
        for (i in 0 until days.length()) {
            val row = days.optJSONObject(i) ?: continue
            perDay[row.optInt("day", i + 1)] = Triple(row.optDouble("pv", 0.0), row.optDouble("home", 0.0), row.optDouble("grid", 0.0))
        }
    }
    archive.forEach { (date, t) ->
        if (date.startsWith(month) && date.length == 10) perDay[date.substring(8, 10).toIntOrNull() ?: return@forEach] = t
    }
    val points = mutableListOf<ChartPoint>()
    var pv = 0.0
    var home = 0.0
    var grid = 0.0
    perDay.forEach { (d, t) ->
        if (t.first == 0.0 && t.second == 0.0 && t.third == 0.0) return@forEach
        points.add(ChartPoint(d - 1, t.first / 1000.0, t.second / 1000.0, t.third / 1000.0))
        pv += t.first
        home += t.second
        grid += t.third
    }
    return LoadedPeriod(points, 0, Triple(pv, home, grid))
}

// /api/yearly: масиви з 12 місячних сум (Вт·год) і data_count (доби з даними), плюс архів на
// телефоні. Для місяця беремо те джерело, що покриває більше діб: після чистки модуль має
// менше днів у старих місяцях, ніж архів.
private fun parseYear(
    payload: JSONObject?,
    archive: Map<String, Triple<Double, Double, Double>>,
    year: String,
): LoadedPeriod {
    val pvArr = payload?.optJSONArray("pv")
    val homeArr = payload?.optJSONArray("home")
    val gridArr = payload?.optJSONArray("grid")
    val counts = payload?.optJSONArray("data_count")
    val points = mutableListOf<ChartPoint>()
    var pv = 0.0
    var home = 0.0
    var grid = 0.0
    for (i in 0 until 12) {
        val prefix = "%s-%02d".format(year, i + 1)
        val archiveDays = archive.filterKeys { it.startsWith(prefix) }.values
            .filter { it.first != 0.0 || it.second != 0.0 || it.third != 0.0 }
        val deviceCount = counts?.optInt(i, 0) ?: 0
        val p: Double
        val h: Double
        val g: Double
        if (archiveDays.isNotEmpty() && archiveDays.size >= deviceCount) {
            p = archiveDays.sumOf { it.first }
            h = archiveDays.sumOf { it.second }
            g = archiveDays.sumOf { it.third }
        } else {
            p = pvArr?.optDouble(i, 0.0) ?: 0.0
            h = homeArr?.optDouble(i, 0.0) ?: 0.0
            g = gridArr?.optDouble(i, 0.0) ?: 0.0
            if (deviceCount == 0 && p == 0.0 && h == 0.0 && g == 0.0) continue
        }
        points.add(ChartPoint(i, p / 1000.0, h / 1000.0, g / 1000.0))
        pv += p
        home += h
        grid += g
    }
    return LoadedPeriod(points, 0, Triple(pv, home, grid))
}

// ---- Клімат (temp/hum/press, зони "інвертор"/"вулиця") - з ТОГО САМОГО payload, що й
// енергія (/api/daily, /api/monthly, /api/yearly вже віддають ці поля), тому без
// додаткових запитів до модуля. 0.0 - завжди "немає виміру цього часу/дня", а не
// легітимний нуль (прошивка сама форматує так - sanitizeFloatValue), тож такі точки
// пропускаються, а не малюються як провал графіка до нуля.
private fun parseClimateFromPayload(period: EnergyPeriod, payload: JSONObject): Map<String, List<Pair<Float, Double>>> {
    val out = mutableMapOf<String, MutableList<Pair<Float, Double>>>()
    fun add(id: String, x: Float, v: Double) {
        if (v == 0.0) return
        out.getOrPut(id) { mutableListOf() }.add(x to v)
    }
    fun addRow(row: JSONObject, x: Float) {
        add("temp_inv", x, row.optDouble("temp", 0.0))
        add("hum_inv", x, row.optDouble("hum", 0.0))
        add("press_inv", x, row.optDouble("press", 0.0))
        add("temp_ext", x, row.optDouble("temp_ext", 0.0))
        add("hum_ext", x, row.optDouble("hum_ext", 0.0))
        add("press_ext", x, row.optDouble("press_ext", 0.0))
    }
    when (period) {
        EnergyPeriod.DAY -> {
            val hours = payload.optJSONArray("hours") ?: return emptyMap()
            for (i in 0 until hours.length()) {
                val row = hours.optJSONObject(i) ?: continue
                addRow(row, (row.optInt("hour", i) * 3600).toFloat())
            }
        }
        EnergyPeriod.MONTH -> {
            val days = payload.optJSONArray("days") ?: return emptyMap()
            for (i in 0 until days.length()) {
                val row = days.optJSONObject(i) ?: continue
                addRow(row, (row.optInt("day", i + 1) - 1).toFloat())
            }
        }
        EnergyPeriod.YEAR -> {
            fun arr(key: String) = payload.optJSONArray(key)
            val fields = mapOf(
                "temp_inv" to arr("temp"), "hum_inv" to arr("hum"), "press_inv" to arr("press"),
                "temp_ext" to arr("temp_ext"), "hum_ext" to arr("hum_ext"), "press_ext" to arr("press_ext"),
            )
            for (i in 0 until 12) {
                fields.forEach { (id, a) -> add(id, i.toFloat(), a?.optDouble(i, 0.0) ?: 0.0) }
            }
        }
    }
    return out
}

// ---- Напруга мережі/сонця і заряд АКБ - лише Доба (пристрій тримає 2 доби,
// далі видаляє; для Місяця/Року свідомо не накопичуємо окремий архів - див.
// обговорення з користувачем). x - секунда ВІД ПІВНОЧІ переглянутої доби,
// та сама вісь, що і в графіку енергії/хронології нижче.
private fun localMidnightEpochSec(dateIso: String): Long {
    val cal = java.util.Calendar.getInstance()
    cal.clear()
    cal.set(dateIso.substring(0, 4).toInt(), dateIso.substring(5, 7).toInt() - 1, dateIso.substring(8, 10).toInt())
    return cal.timeInMillis / 1000
}

private fun buildVoltageSeriesForDay(points: List<VoltageHistoryPoint>, dateIso: String): Map<String, List<Pair<Float, Double>>> {
    val midnight = localMidnightEpochSec(dateIso)
    val out = mutableMapOf<String, MutableList<Pair<Float, Double>>>()
    points.forEach { p ->
        val secOfDay = p.epochSec - midnight
        if (secOfDay < 0 || secOfDay >= SECONDS_IN_DAY) return@forEach
        val x = secOfDay.toFloat()
        if (p.grid > 0.0) out.getOrPut("volt_grid") { mutableListOf() }.add(x to p.grid)
        if (p.pv > 0.0) out.getOrPut("volt_pv") { mutableListOf() }.add(x to p.pv)
        if (p.battery > 0.0) out.getOrPut("soc_battery") { mutableListOf() }.add(x to p.battery)
    }
    return out
}

// ---- "% часу роботи за добу" (Місяць/Рік) - з АРХІВУ на телефоні (15-хв прапорці
// /api/history, той самий файл, що й /api/timeline, лише глибший: 120 діб на
// пристрої, і назавжди в кеші телефону). Глибина росте з дня підключення -
// минуле до цього не відновити (пристрій уже міг почистити старі файли).
private fun buildUptimeSeries(ratios: Map<String, Double>, period: EnergyPeriod, periodKey: String): List<Pair<Float, Double>> =
    when (period) {
        EnergyPeriod.MONTH -> ratios.filterKeys { it.startsWith(periodKey) && it.length == 10 }
            .mapNotNull { (date, r) -> date.substring(8, 10).toIntOrNull()?.let { (it - 1).toFloat() to r * 100.0 } }
            .sortedBy { it.first }
        EnergyPeriod.YEAR -> (0 until 12).mapNotNull { m ->
            val prefix = "%s-%02d".format(periodKey, m + 1)
            val days = ratios.filterKeys { it.startsWith(prefix) }.values
            if (days.isEmpty()) null else m.toFloat() to (days.average() * 100.0)
        }
        EnergyPeriod.DAY -> emptyList() // День - смуги хронології нижче, не лінія
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnergyChartScreen(
    config: AppConfig,
    repository: StatusRepository,
    status: UnifiedStatus,
    liveTotals: Triple<Double, Double, Double>?,
    onBack: (() -> Unit)? = null,
) {
    var period by rememberSaveable { mutableStateOf(EnergyPeriod.DAY) }
    // Опорна дата "YYYY-MM-DD": для місяця/року береться її початок, тож період
    // не губиться при перемиканні Доба/Місяць/Рік.
    var anchor by rememberSaveable { mutableStateOf(todayIso()) }
    var points by remember { mutableStateOf<List<ChartPoint>>(emptyList()) }
    var stepMinutes by remember { mutableStateOf(60) }
    // Підсумки завантаженого періоду, Вт·год.
    var savedTotals by remember { mutableStateOf(Triple(0.0, 0.0, 0.0)) }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    // Вид: лінії або кругова діаграма розподілу.
    var pieMode by rememberSaveable { mutableStateOf(false) }
    // Які лінії показувати: сонце / навантаження / мережа - можна ховати окремо, щоб
    // роздивитись одну з них.
    var showPv by rememberSaveable { mutableStateOf(true) }
    var showHome by rememberSaveable { mutableStateOf(true) }
    var showGrid by rememberSaveable { mutableStateOf(true) }
    // Вертикальний курсор із підказкою (у одиницях осі X): не наближено - веде палець, наближено - дотик.
    var cursorX by remember { mutableStateOf<Float?>(null) }

    // Додаткові параметри (клімат/напруга/АКБ/час роботи) - вимкнені за замовчуванням,
    // щоб вигляд графіка не змінився для тих, хто їх не потребує. id з PARAM_CATALOG.
    var selectedExtra by rememberSaveable { mutableStateOf(setOf<String>()) }
    var expandedGroups by remember { mutableStateOf(setOf<String>()) }
    var climateSeries by remember { mutableStateOf<Map<String, List<Pair<Float, Double>>>>(emptyMap()) }
    var voltageSeries by remember { mutableStateOf<Map<String, List<Pair<Float, Double>>>>(emptyMap()) }
    var uptimeSeries by remember { mutableStateOf<Map<String, List<Pair<Float, Double>>>>(emptyMap()) }

    val periodKey = when (period) {
        EnergyPeriod.DAY -> anchor
        EnergyPeriod.MONTH -> anchor.take(7)
        EnergyPeriod.YEAR -> anchor.take(4)
    }

    // Вкладки нижньої навігації лишаються живими в пам'яті (saveState/
    // restoreState), тож одноразовий LaunchedEffect не оновлював би дані при
    // поверненні на той самий період - цикл з оновленням (доба - 30с, місяць/рік -
    // 2 хв: інвертор рахує їх скануванням файлів 8-20 с). Спінер - лише на першому
    // завантаженні цього (період, конфіг).
    LaunchedEffect(period, periodKey, config) {
        // Новий період/конфіг - старі точки чужого періоду не показуємо.
        points = emptyList()
        savedTotals = Triple(0.0, 0.0, 0.0)
        errorText = null
        cursorX = null
        climateSeries = emptyMap()

        // Клімат - з ТОГО САМОГО payload, що й енергія (без окремого запиту).
        fun applyClimate(payload: JSONObject?) {
            if (payload != null) climateSeries = parseClimateFromPayload(period, payload)
        }

        // Розбір відповіді модуля разом з архівом на телефоні (для місяця/року).
        suspend fun buildLoaded(payload: JSONObject?): LoadedPeriod? {
            val archive = if (period == EnergyPeriod.DAY) {
                emptyMap()
            } else {
                runCatching { repository.archivedDailyTotals(config) }.getOrDefault(emptyMap())
            }
            return when (period) {
                EnergyPeriod.DAY -> payload?.let { parseDay(it) }
                EnergyPeriod.MONTH -> parseMonth(payload, archive, periodKey).takeIf { payload != null || it.points.isNotEmpty() }
                EnergyPeriod.YEAR -> parseYear(payload, archive, periodKey).takeIf { payload != null || it.points.isNotEmpty() }
            }
        }

        fun show(loaded: LoadedPeriod) {
            points = loaded.points
            if (period == EnergyPeriod.DAY) stepMinutes = loaded.stepMinutes
            savedTotals = loaded.totalsWh
        }

        // Що вже є на телефоні (кеш + архів) - показуємо одразу, не чекаючи повільний модуль
        // (місяць 8 с, рік ~20 с); мережа лише оновлює.
        val cached = runCatching {
            when (period) {
                EnergyPeriod.DAY -> repository.peekInverterDaily(config, periodKey)
                EnergyPeriod.MONTH -> repository.peekInverterMonthly(config, periodKey)
                EnergyPeriod.YEAR -> repository.peekInverterYearly(config, periodKey.toIntOrNull() ?: 0)
            }
        }.getOrNull()
        applyClimate(cached)
        val first = buildLoaded(cached)
        if (first != null) {
            show(first)
            loading = false
        }
        var firstLoad = first == null
        while (true) {
            if (firstLoad) loading = true
            val payload = runCatching {
                when (period) {
                    EnergyPeriod.DAY -> repository.fetchInverterDaily(config, periodKey)
                    EnergyPeriod.MONTH -> repository.fetchInverterMonthly(config, periodKey)
                    EnergyPeriod.YEAR -> repository.fetchInverterYearly(config, periodKey.toIntOrNull() ?: 0)
                }
            }.getOrNull()
            applyClimate(payload)
            val loaded = buildLoaded(payload)
            if (loaded == null) {
                // Тимчасовий збій зв'язку (лагає ZeroTier) не стирає вже показаний
                // графік - помилка лише якщо показувати ще нічого.
                if (points.isEmpty()) {
                    errorText = "немає даних (модуль інвертора недоступний або дані за цей період відсутні)"
                }
            } else {
                errorText = null
                show(loaded)
            }
            loading = false
            firstLoad = false
            // Коли даних ще нема - повторюємо швидко (для повільних місяця/року - через 15с).
            val fast = loaded == null && points.isEmpty()
            delay(
                when {
                    period == EnergyPeriod.DAY -> if (fast) 5_000L else 30_000L
                    else -> if (fast) 15_000L else 120_000L
                },
            )
        }
    }

    // Напруга мережі/сонця і заряд АКБ - лише Доба, і лише якщо переглянута доба в
    // 2-добовому вікні, яке тримає пристрій (сьогодні/учора); для давніших діб
    // пристрій дані вже видалив - показувати нема чого.
    LaunchedEffect(period, anchor, config) {
        if (period != EnergyPeriod.DAY) {
            voltageSeries = emptyMap()
            return@LaunchedEffect
        }
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
        val yesterday = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
        if (anchor != todayIso() && anchor != yesterday) {
            voltageSeries = emptyMap()
            return@LaunchedEffect
        }
        while (true) {
            val result = runCatching { repository.fetchInverterVoltageHistory(config) }.getOrNull()
            if (result != null) voltageSeries = buildVoltageSeriesForDay(result.points, anchor)
            delay(30_000L)
        }
    }

    // "% часу роботи за добу" (Місяць/Рік) - з архіву на телефоні, без мережевих
    // запитів (120 потенційних запитів на відкриття графіка були б завеликою ціною);
    // архів наповнює фоновий syncHistoryArchive() раз на 30 хв, поки застосунок відкрито.
    LaunchedEffect(period, periodKey, config) {
        if (period == EnergyPeriod.DAY) {
            uptimeSeries = emptyMap()
            return@LaunchedEffect
        }
        while (true) {
            val lc = config.loadControllerBaseUrl
            val gr = config.garageBaseUrl
            val next = mapOf(
                "up_boiler1" to runCatching { repository.archivedUptimeRatios(lc, 0) }.getOrDefault(emptyMap()),
                "up_pump" to runCatching { repository.archivedUptimeRatios(lc, 1) }.getOrDefault(emptyMap()),
                "up_grid" to runCatching { repository.archivedUptimeRatios(lc, 2) }.getOrDefault(emptyMap()),
                "up_pv" to runCatching { repository.archivedUptimeRatios(lc, 3) }.getOrDefault(emptyMap()),
                "up_boiler2" to runCatching { repository.archivedUptimeRatios(gr, 0) }.getOrDefault(emptyMap()),
            ).mapValues { (_, ratios) -> buildUptimeSeries(ratios, period, periodKey) }.filterValues { it.isNotEmpty() }
            uptimeSeries = next
            delay(120_000L)
        }
    }

    val isToday = anchor == todayIso()
    // Історія (/api/daily) містить лише години, які прошивка вже встигла
    // записати у файл; лічильники "за добу" з /api/status - завжди живі.
    // Для сьогоднішньої доби в підсумках показуємо живі, щоб вони збігались із
    // дашбордом, а розбіжність з історією виносимо в підказку під графіком.
    val dayLive = period == EnergyPeriod.DAY && isToday && liveTotals != null
    val shownTotals = if (dayLive) liveTotals!! else savedTotals
    val unsavedHomeWh = if (dayLive) liveTotals!!.second - savedTotals.second else 0.0

    val axis = buildAxis(period, stepMinutes, anchor)
    val viewport = rememberChartViewport(period to periodKey, 0f, axis.domainMax, axis.minSpan)
    // Доба: графік потужності й хронологія (TimelineBody нижче) ділять ОДИН і той самий
    // (секундний) viewport, тож масштаб/зсув синхронні, а вертикальна лінійка часу - спільна
    // (хронологія малює підписи, графік - лише сітку без підписів, див. showXAxisLabels нижче).
    val dayViewport = rememberChartViewport(anchor, 0f, SECONDS_IN_DAY.toFloat(), MIN_VIEW_SECONDS)
    val activeViewport = if (period == EnergyPeriod.DAY) dayViewport else viewport

    fun totalText(wh: Double) =
        if (period == EnergyPeriod.DAY) "${wh.roundToInt()} Вт·год" else "%.1f кВт·год".format(wh / 1000.0)

    // Усі елементи керування (дата/період/вигляд/видимість ліній/масштаб) - під
    // кнопкою-"сендвічем", яка сама лежить ПОВЕРХ графіка (плаваюча, без окремого
    // рядкашапки), а не в окремій TopAppBar - це і звільняє максимум висоти для
    // графіка+хронології, як просив користувач.
    var menuExpanded by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            if (pieMode) {
                EnergyPieSection(
                    status = status,
                    savedTotals = savedTotals,
                    isToday = period == EnergyPeriod.DAY && isToday,
                    // Зверху - місце під плаваючі сендвіч/перемикач-кнопки (лежать поверх усього
                    // екрана в тому самому куті): без цього відступу власний рядок фільтрів
                    // діаграми ("Джерела"/"Споживачі"/...) ховався б під ними.
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 52.dp),
                    dayMode = period == EnergyPeriod.DAY,
                    periodLabel = when (period) {
                        EnergyPeriod.DAY -> "За добу"
                        EnergyPeriod.MONTH -> "За місяць"
                        EnergyPeriod.YEAR -> "За рік"
                    },
                )
            } else {
                // Графік + хронологія не влазять в один нерухомий екран без прокрутки,
                // коли додані ще й обрані параметри.
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                ) {
                    when {
                        loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        errorText != null -> Text(errorText.orEmpty(), color = TextMuted, modifier = Modifier.padding(16.dp))
                        else -> {
                            // Усі обрані ряди - енергія (pv/home/grid) і додаткові параметри -
                            // одним списком на ОДНЕ полотно (за проханням користувача): один
                            // спільний графік, а не картка на кожну одиницю вимірювання.
                            val energyIds = listOf(
                                Triple("pv", "Сонце", AccentPv) to showPv,
                                Triple("home", "Навантаження", AccentLoad) to showHome,
                                Triple("grid", "Мережа", AccentGrid) to showGrid,
                            )
                            val combinedSeries = buildList {
                                energyIds.forEach { (info, shown) ->
                                    if (!shown) return@forEach
                                    val (id, label, color) = info
                                    val pts = points.map { p ->
                                        p.x.toFloat() to when (id) {
                                            "pv" -> p.pv
                                            "home" -> p.home
                                            else -> p.grid
                                        }
                                    }
                                    if (pts.isNotEmpty()) add(UnifiedSeries(id, label, color, axis.unit, pts))
                                }
                                PARAM_CATALOG.filter { it.id in selectedExtra && period in it.availablePeriods }.forEach { def ->
                                    val pts = when (def.group) {
                                        ParamGroup.TEMP, ParamGroup.HUMIDITY, ParamGroup.PRESSURE -> climateSeries[def.id]
                                        ParamGroup.VOLTAGE, ParamGroup.BATTERY_SOC -> voltageSeries[def.id]
                                        ParamGroup.UPTIME -> uptimeSeries[def.id]
                                    }
                                    if (!pts.isNullOrEmpty()) {
                                        add(UnifiedSeries(def.id, "${def.label} · ${def.group.title}", def.color, def.group.unit, pts))
                                    }
                                }
                            }
                            UnifiedLineChart(
                                series = combinedSeries,
                                axis = axis,
                                viewport = activeViewport,
                                cursorX = cursorX,
                                onCursor = { cursorX = it },
                                emptyText = when {
                                    combinedSeries.isEmpty() && (showPv || showHome || showGrid || selectedExtra.isNotEmpty()) ->
                                        "усі обрані ряди порожні за цей період"
                                    combinedSeries.isEmpty() -> "усі лінії приховані - оберіть хоча б одну в меню"
                                    else -> when (period) {
                                        EnergyPeriod.DAY -> "немає даних за цю добу"
                                        EnergyPeriod.MONTH -> "немає даних за цей місяць"
                                        EnergyPeriod.YEAR -> "немає даних за цей рік"
                                    }
                                },
                                leftMarginDp = if (period == EnergyPeriod.DAY) 72.dp else 44.dp,
                                modifier = Modifier.fillMaxWidth().height(320.dp),
                            )
                            if (unsavedHomeWh > 50.0) {
                                Text(
                                    text = "В історію записано ${savedTotals.second.roundToInt()} з " +
                                        "${shownTotals.second.roundToInt()} Вт·год (навантаження): решта - поточна година " +
                                        "або ще не записана прошивкою.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                    // Хронологія роботи обладнання - лише для доби (таймлайн по
                    // суті й показує саме один день), на тому самому екрані, що й
                    // графік потужності, щоб порівнювати навантаження з тим, яке
                    // обладнання в цей момент реально працювало - одним поглядом,
                    // без перемикання вкладок. Без заголовка - курсор/підписи рядків
                    // прямо на графіку й так досить зрозумілі. Єдине поле, що
                    // лишається ОКРЕМИМ від об'єднаного графіка вище - за проханням
                    // користувача.
                    if (period == EnergyPeriod.DAY) {
                        HorizontalDivider(color = OutlineSubtle, thickness = 1.dp, modifier = Modifier.padding(vertical = 2.dp))
                        TimelineBody(
                            date = anchor,
                            config = config,
                            repository = repository,
                            compact = true,
                            externalViewport = dayViewport,
                            // Той самий курсор, що й у графіка вище - рухається синхронно.
                            externalCursor = cursorX,
                            onExternalCursorChange = { cursorX = it },
                            modifier = Modifier.fillMaxWidth().height(130.dp),
                        )
                    }
                }
            }
        }

        // DropdownMenu - це Popup, а Popup НЕ успадковує graphicsLayer-поворот від
        // LandscapeInPortrait: меню показувалось в нормальній орієнтації, "боком"
        // відносно вже повернутого графіка позаду. Тому тут - звичайний Surface у
        // тому самому дереві композиції (не Popup), він обертається разом з усім
        // іншим. Напівпрозорий скрим на весь екран замінює автоматичне закриття
        // Popup дотиком поза ним.
        if (menuExpanded) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { menuExpanded = false },
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp),
        ) {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier
                    .size(36.dp)
                    .background(SurfaceContainerHigh.copy(alpha = 0.85f), CircleShape),
            ) {
                Icon(Icons.Filled.Menu, contentDescription = "Керування графіком", tint = TextPrimary)
            }
            if (menuExpanded) {
                Surface(
                    color = SurfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier.padding(top = 40.dp).widthIn(max = 300.dp),
                ) {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    ) {
                        if (onBack != null) {
                            DropdownMenuItem(
                                text = { Text("Назад") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
                                onClick = { menuExpanded = false; onBack() },
                            )
                            HorizontalDivider()
                        }
                        Text(
                            periodTitleFor(period, anchor),
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        PeriodNavHeader(
                            anchor = anchor,
                            granularity = period.granularity,
                            onChange = { anchor = it },
                            modifier = Modifier.width(260.dp),
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            EnergyPeriod.entries.forEachIndexed { index, item ->
                                SegmentedButton(
                                    selected = period == item,
                                    onClick = { period = item },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = EnergyPeriod.entries.size),
                                ) { Text(item.label, maxLines = 1) }
                            }
                        }
                        // Перемикач Лінії/Діаграма - окрема завжди видима кнопка поруч із
                        // сендвічем (не тут): користувач раніше не знаходив її захованою в
                        // прокрутному меню, тому дублювати її тут більше не варто.
                        if (!pieMode) {
                            HorizontalDivider()
                            SummaryLegendRow(
                                totals = listOf(totalText(shownTotals.first), totalText(shownTotals.second), totalText(shownTotals.third)),
                                unitNote = when (period) {
                                    EnergyPeriod.DAY -> if (stepMinutes == 15) "Вт, середня за 15 хв" else "Вт, середня за годину"
                                    EnergyPeriod.MONTH -> "кВт·год за добу"
                                    EnergyPeriod.YEAR -> "кВт·год за місяць"
                                },
                                visible = listOf(showPv, showHome, showGrid),
                                onToggle = { index ->
                                    when (index) {
                                        0 -> showPv = !showPv
                                        1 -> showHome = !showHome
                                        else -> showGrid = !showGrid
                                    }
                                },
                                modifier = Modifier.width(280.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                            HorizontalDivider()
                            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                ChartToolbarActions(activeViewport)
                            }
                            HorizontalDivider()
                            Text(
                                "Інші параметри",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextMuted,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                            ParamGroup.entries.forEach { group ->
                                val defs = PARAM_CATALOG.filter { it.group == group }
                                val availableNow = defs.filter { period in it.availablePeriods }
                                if (availableNow.isEmpty()) return@forEach
                                val groupKey = group.name
                                val expanded = groupKey in expandedGroups
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            expandedGroups = if (expanded) expandedGroups - groupKey else expandedGroups + groupKey
                                        }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val activeCount = availableNow.count { it.id in selectedExtra }
                                    Text(
                                        if (activeCount > 0) "${group.title} ($activeCount)" else group.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (activeCount > 0) TextPrimary else TextMuted,
                                    )
                                    Icon(
                                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                        contentDescription = if (expanded) "Згорнути" else "Розгорнути",
                                        tint = TextMuted,
                                    )
                                }
                                if (expanded) {
                                    Column(modifier = Modifier.padding(start = 8.dp)) {
                                        defs.forEach { def ->
                                            val available = period in def.availablePeriods
                                            VisibilityToggle(
                                                label = if (available) def.label else "${def.label} (не за цей період)",
                                                color = def.color,
                                                visible = def.id in selectedExtra,
                                                onToggle = {
                                                    if (available) {
                                                        selectedExtra = if (def.id in selectedExtra) selectedExtra - def.id else selectedExtra + def.id
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Перемикач Лінії/Діаграма - окрема завжди видима кнопка поруч із сендвічем
        // (не всередині нього): раніше цей перемикач був лише в прокрутному меню,
        // і користувач не міг його там знайти.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 44.dp, top = 4.dp),
        ) {
            IconButton(
                onClick = { pieMode = !pieMode },
                modifier = Modifier
                    .size(36.dp)
                    .background(SurfaceContainerHigh.copy(alpha = 0.85f), CircleShape),
            ) {
                Icon(
                    imageVector = if (pieMode) Icons.AutoMirrored.Filled.ShowChart else Icons.Filled.PieChart,
                    contentDescription = if (pieMode) "Показати лінії" else "Показати діаграму",
                    tint = TextPrimary,
                )
            }
        }
    }
}

/**
 * Легенда, підсумки періоду й перемикачі видимості в одному рядку: "око" + назва +
 * значення. Натискання ховає/показує відповідну лінію графіка.
 */
@Composable
private fun SummaryLegendRow(
    totals: List<String>,
    unitNote: String,
    visible: List<Boolean>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VisibilityToggle("сонце", AccentPv, visible[0], { onToggle(0) }, detail = totals[0])
        VisibilityToggle("навантаження", AccentLoad, visible[1], { onToggle(1) }, detail = totals[1])
        VisibilityToggle("мережа", AccentGrid, visible[2], { onToggle(2) }, detail = totals[2])
        Spacer(Modifier.weight(1f))
        Text(unitNote, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

/** "Гарна" верхня межа осі Y: 1/2/2.5/5 x 10^k, поділена на 4 інтервали. */
private fun niceAxisMax(rawMax: Double): Double {
    if (rawMax <= 0.0) return 1.0
    val exp = 10.0.pow(kotlin.math.floor(log10(rawMax / 4.0)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * exp }.first { it * 4.0 >= rawMax }
    return step * 4.0
}

/** Вузький діапазон з запасом (на відміну від niceAxisMax - може бути від'ємним: температура). */
private fun tightAxisRange(minV: Double, maxV: Double): Pair<Double, Double> {
    if (!minV.isFinite() || !maxV.isFinite()) return 0.0 to 1.0
    if (minV == maxV) return (minV - 1.0) to (maxV + 1.0)
    val pad = (maxV - minV) * 0.15
    return (minV - pad) to (maxV + pad)
}

private data class UnifiedSeries(val id: String, val label: String, val color: Color, val unit: String, val points: List<Pair<Float, Double>>)

// Одиниці, для яких вісь Y природно починається з нуля (Вт/кВт·год/%); решта
// (°C/гПа/В) - вузький діапазон із запасом, інакше шкала виглядала б як майже
// рівна лінія біля верху.
private val UNIT_FROM_ZERO = setOf("Вт", "кВт·год", "%")

/**
 * ВСІ обрані ряди - енергія й додаткові параметри - на ОДНОМУ полотні (за явним
 * проханням користувача): коли всі ряди однієї одиниці - справжня вісь Y із
 * підписами (як і раніше для енергії). Коли одиниці різні (°C поруч із Вт) -
 * вісь стає ВІДНОСНОЮ: кожен ряд нормалізується окремо за своїм видимим
 * діапазоном (0..1), щоб форми кривих можна було чесно порівняти без хибного
 * спільного масштабу. Точні значення в обох випадках - у підказці під пальцем.
 */
@Composable
private fun UnifiedLineChart(
    series: List<UnifiedSeries>,
    axis: XAxis,
    viewport: ChartViewport,
    cursorX: Float?,
    onCursor: (Float?) -> Unit,
    emptyText: String,
    modifier: Modifier = Modifier,
    leftMarginDp: Dp = 44.dp,
) {
    if (series.isEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceContainer), modifier = modifier) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(emptyText, color = TextMuted, modifier = Modifier.padding(16.dp))
            }
        }
        return
    }

    val density = LocalDensity.current
    val leftMarginPx = with(density) { leftMarginDp.toPx() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    fun plotWidthPx() = (canvasSize.width - leftMarginPx).coerceAtLeast(1f)
    fun xValueAt(px: Float): Float = viewport.start + ((px - leftMarginPx) / plotWidthPx()).coerceIn(0f, 1f) * viewport.span

    val axisPaint = remember(density) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#9BA3B4")
            textSize = with(density) { 11.sp.toPx() }
        }
    }
    val tipTitlePaint = remember(density) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#F0F2F8")
            textSize = with(density) { 12.sp.toPx() }
            isFakeBoldText = true
        }
    }
    val tipTextPaint = remember(density) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#F0F2F8")
            textSize = with(density) { 12.sp.toPx() }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
        modifier = modifier,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .onSizeChanged { canvasSize = it }
                .chartGestures(
                    key = Unit,
                    dragMovesCursor = !viewport.isZoomed,
                    onCursor = { onCursor(xValueAt(it.x)) },
                    onTap = { onCursor(xValueAt(it.x)) },
                    onPan = { dx -> viewport.panFraction(-dx / plotWidthPx()) },
                    onZoom = { cx, factor ->
                        viewport.zoomAround(((cx - leftMarginPx) / plotWidthPx()).coerceIn(0f, 1f), factor)
                    },
                ),
        ) {
            val plotLeft = leftMarginPx
            val bottomMargin = 22.dp.toPx()
            val plotTop = 14.dp.toPx()
            val plotW = size.width - plotLeft
            val plotH = size.height - plotTop - bottomMargin
            val v0 = viewport.start
            val vSpan = viewport.span

            fun visibleOf(pts: List<Pair<Float, Double>>) = pts.filter { it.first >= v0 && it.first <= v0 + vSpan }

            val distinctUnits = series.map { it.unit }.distinct()
            // Спільна (справжня) вісь - лише якщо ВСІ обрані ряди однієї одиниці.
            val uniformUnit = distinctUnits.singleOrNull()

            val (yMin, yMax) = if (uniformUnit != null) {
                val visVals = series.flatMap { visibleOf(it.points) }.map { it.second }
                if (uniformUnit in UNIT_FROM_ZERO) {
                    0.0 to niceAxisMax(visVals.maxOrNull() ?: 0.0)
                } else {
                    tightAxisRange(visVals.minOrNull() ?: 0.0, visVals.maxOrNull() ?: 1.0)
                }
            } else {
                0.0 to 1.0
            }
            val ySpan = (yMax - yMin).takeIf { it > 0.0 } ?: 1.0

            // Відносний режим: кожен ряд - за своїм власним видимим діапазоном (0..1).
            fun normalize(s: UnifiedSeries): (Double) -> Double {
                if (uniformUnit != null) return { v -> v }
                val vis = visibleOf(s.points).map { it.second }.ifEmpty { s.points.map { it.second } }
                val lo = vis.minOrNull() ?: 0.0
                val hi = vis.maxOrNull() ?: 1.0
                val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
                return { v -> (v - lo) / span }
            }

            fun xAt(x: Float) = plotLeft + (x - v0) / vSpan * plotW
            fun yAt(v: Double) = plotTop + plotH - ((v - yMin) / ySpan * plotH).toFloat().coerceIn(0f, plotH)

            val gridStroke = 1.dp.toPx()
            for (i in 0..4) {
                val frac = i / 4.0
                val y = plotTop + plotH - (frac * plotH).toFloat()
                drawLine(OutlineSubtle, Offset(plotLeft, y), Offset(size.width, y), strokeWidth = gridStroke)
                if (uniformUnit != null) {
                    val value = yMin + ySpan * frac
                    val decimals = if (uniformUnit == "%" || uniformUnit == "Вт") 0 else 1
                    val label = "%.${decimals}f".format(value)
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        plotLeft - 6.dp.toPx() - axisPaint.measureText(label),
                        y + axisPaint.textSize / 3f,
                        axisPaint,
                    )
                }
            }
            // Підпис одиниці (чи "відносний масштаб" для змішаних) - над полотном зліва.
            drawContext.canvas.nativeCanvas.drawText(
                uniformUnit ?: "відносний масштаб (див. значення під пальцем)",
                plotLeft,
                9.dp.toPx(),
                axisPaint,
            )

            clipRect(left = plotLeft, top = 0f, right = size.width, bottom = size.height) {
                val step = axis.tickSteps.firstOrNull { vSpan / it <= axis.maxTicks } ?: axis.tickSteps.last()
                var tick = (ceil(v0 / step) * step).toInt()
                while (tick <= v0 + vSpan) {
                    val x = xAt(tick.toFloat())
                    drawLine(OutlineSubtle, Offset(x, plotTop), Offset(x, plotTop + plotH), strokeWidth = 1f)
                    val label = axis.tickLabel(tick)
                    drawContext.canvas.nativeCanvas.drawText(label, x - axisPaint.measureText(label) / 2f, size.height - 4.dp.toPx(), axisPaint)
                    tick += step
                }

                val lineStroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                series.forEach { s ->
                    val norm = normalize(s)
                    val pts = s.points.sortedBy { it.first }
                    // Типовий крок цього ряду - з його ВЛАСНИХ сусідніх точок (різні
                    // джерела мають різну щільність вибірки: 15хв/година/5хв/доба).
                    val stepHint = if (pts.size >= 2) (pts[1].first - pts[0].first).coerceAtLeast(1f) else axis.pointWidth
                    val path = Path()
                    pts.forEachIndexed { i, (x, v) ->
                        val px = xAt(x)
                        val py = yAt(norm(v))
                        if (i == 0) {
                            path.moveTo(px, py)
                        } else {
                            val gap = x - pts[i - 1].first
                            if (gap <= stepHint * 1.5f) path.lineTo(px, py) else path.moveTo(px, py)
                        }
                    }
                    drawPath(path, s.color, style = lineStroke)
                    if (pts.size <= 50) {
                        val r = (4f / 3f).dp.toPx()
                        pts.forEach { (x, v) -> drawCircle(s.color, radius = r, center = Offset(xAt(x), yAt(norm(v)))) }
                    }
                }
            }

            if (cursorX != null) {
                val x = xAt(cursorX)
                if (x in plotLeft..size.width) {
                    drawLine(TextMuted.copy(alpha = 0.6f), Offset(x, plotTop), Offset(x, plotTop + plotH), strokeWidth = 1.dp.toPx())
                    val rows = series.mapNotNull { s ->
                        val nearest = s.points.minByOrNull { abs(it.first - cursorX) } ?: return@mapNotNull null
                        if (abs(nearest.first - cursorX) > vSpan * 0.08f) return@mapNotNull null
                        val decimals = if (s.unit == "%" || s.unit == "Вт") 0 else 1
                        s.color to "${s.label}: ${"%.${decimals}f".format(nearest.second)} ${s.unit}"
                    }
                    if (rows.isNotEmpty()) {
                        val title = axis.pointTitle(cursorX.toInt())
                        val pad = 8.dp.toPx()
                        val lineH = tipTextPaint.textSize * 1.3f
                        val boxW = (rows.map { it.second } + title).maxOf { tipTextPaint.measureText(it) } + pad * 2 + 14.dp.toPx()
                        val boxH = pad * 2 + lineH * (rows.size + 1)
                        val boxX = if (x + 10.dp.toPx() + boxW > size.width) x - 10.dp.toPx() - boxW else x + 10.dp.toPx()
                        val boxY = plotTop + 2.dp.toPx()
                        drawRoundRect(
                            color = SurfaceContainerHigh,
                            topLeft = Offset(boxX, boxY),
                            size = Size(boxW, boxH),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                        )
                        drawContext.canvas.nativeCanvas.drawText(title, boxX + pad, boxY + pad + lineH * 0.8f, tipTitlePaint)
                        rows.forEachIndexed { r, (color, text) ->
                            val ty = boxY + pad + lineH * (r + 1) + lineH * 0.8f
                            drawCircle(color, 4.dp.toPx(), Offset(boxX + pad + 4.dp.toPx(), ty - tipTextPaint.textSize / 3f))
                            drawContext.canvas.nativeCanvas.drawText(text, boxX + pad + 14.dp.toPx(), ty, tipTextPaint)
                        }
                    }
                }
            }
        }
    }
}
