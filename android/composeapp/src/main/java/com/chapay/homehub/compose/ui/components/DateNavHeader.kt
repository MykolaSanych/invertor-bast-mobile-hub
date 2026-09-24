package com.chapay.homehub.compose.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chapay.homehub.compose.ui.theme.TextPrimary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

fun todayIso(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)

fun shiftIsoDate(iso: String, deltaDays: Int): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val cal = Calendar.getInstance()
    cal.time = runCatching { fmt.parse(iso) }.getOrNull() ?: cal.time
    cal.add(Calendar.DAY_OF_MONTH, deltaDays)
    return fmt.format(cal.time)
}

/** Рядок навігації по добах: ‹ дата › + "сьогодні", спільний для журналу/хронології/графіка. */
@Composable
fun DateNavHeader(date: String, onDateChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val today = todayIso()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onDateChange(shiftIsoDate(date, -1)) }) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = "Попередня доба")
        }
        Text(
            text = date,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconButton(
            onClick = { onDateChange(shiftIsoDate(date, 1)) },
            enabled = date < today,
        ) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "Наступна доба")
        }
        if (date != today) {
            TextButton(onClick = { onDateChange(today) }) {
                Text("сьогодні")
            }
        }
    }
}

enum class NavGranularity { DAY, MONTH, YEAR }

private val MONTH_NAMES = listOf(
    "січень", "лютий", "березень", "квітень", "травень", "червень",
    "липень", "серпень", "вересень", "жовтень", "листопад", "грудень",
)

/** Зсув дати "YYYY-MM-DD" на крок [delta] у вказаній одиниці (доба/місяць/рік). */
fun shiftIsoDate(iso: String, granularity: NavGranularity, delta: Int): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val cal = Calendar.getInstance()
    cal.time = runCatching { fmt.parse(iso) }.getOrNull() ?: cal.time
    when (granularity) {
        NavGranularity.DAY -> cal.add(Calendar.DAY_OF_MONTH, delta)
        NavGranularity.MONTH -> {
            cal.set(Calendar.DAY_OF_MONTH, 1) // без переповнення на коротких місяцях
            cal.add(Calendar.MONTH, delta)
        }
        NavGranularity.YEAR -> {
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.add(Calendar.YEAR, delta)
        }
    }
    return fmt.format(cal.time)
}

private fun periodKey(iso: String, granularity: NavGranularity): String = when (granularity) {
    NavGranularity.DAY -> iso
    NavGranularity.MONTH -> iso.take(7)
    NavGranularity.YEAR -> iso.take(4)
}

private fun periodTitle(iso: String, granularity: NavGranularity): String = when (granularity) {
    NavGranularity.DAY -> iso
    NavGranularity.MONTH -> {
        val month = iso.substring(5, 7).toIntOrNull() ?: 1
        "${MONTH_NAMES.getOrElse(month - 1) { "" }} ${iso.take(4)}"
    }
    NavGranularity.YEAR -> iso.take(4)
}

/**
 * Навігація по періодах: ‹ доба/місяць/рік › + "сьогодні". [anchor] - завжди повна
 * дата "YYYY-MM-DD" (для місяця/року береться її початок), тож при перемиканні
 * між добою, місяцем і роком обраний період не губиться.
 */
@Composable
fun PeriodNavHeader(
    anchor: String,
    granularity: NavGranularity,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val todayKey = periodKey(todayIso(), granularity)
    val currentKey = periodKey(anchor, granularity)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onChange(shiftIsoDate(anchor, granularity, -1)) }) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = "Попередній період")
        }
        Text(
            text = periodTitle(anchor, granularity),
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1,
        )
        IconButton(
            onClick = { onChange(shiftIsoDate(anchor, granularity, 1)) },
            enabled = currentKey < todayKey,
        ) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "Наступний період")
        }
        if (currentKey != todayKey) {
            TextButton(onClick = { onChange(todayIso()) }) {
                Text("зараз")
            }
        }
    }
}
