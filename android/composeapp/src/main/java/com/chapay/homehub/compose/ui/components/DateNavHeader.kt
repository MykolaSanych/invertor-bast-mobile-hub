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
