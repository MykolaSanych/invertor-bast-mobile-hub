package com.chapay.homehub.compose.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.chapay.homehub.compose.ui.theme.HeroNumber
import com.chapay.homehub.compose.ui.theme.OutlineSubtle
import com.chapay.homehub.compose.ui.theme.SurfaceContainer
import com.chapay.homehub.compose.ui.theme.SurfaceContainerHigh
import com.chapay.homehub.compose.ui.theme.TextMuted
import com.chapay.homehub.compose.ui.theme.TextPrimary

/**
 * Картка метрики з "героєм"-цифрою і рядком деталей (мітка/значення, як у
 * чинному WebView-додатку - напруга/частота/струм/за добу/стан тощо), щоб
 * не втрачати інформативність порівняно з ним. Два незалежні ефекти зворотного
 * зв'язку: 1) рамка картки пульсує кольором [accent], коли модуль щойно
 * відповів на опитування ([updatedAtMs] змінився) - "картка щойно чула модуль",
 * незалежно від того, чи саме ЦЕ значення змінилось; 2) сама цифра-герой
 * "сяє" (м'яке підсвічування позаду тексту), коли [valueText] реально
 * змінюється - "це число щойно стало іншим".
 */
@Composable
fun MetricCard(
    title: String,
    valueText: String,
    unit: String,
    accent: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    details: List<Pair<String, String>> = emptyList(),
    onClick: (() -> Unit)? = null,
    // Колір піктограми окремо від акценту рамки: іконка може бути індикатором
    // стану (сонце є/немає, мережа є/немає), а рамка лишається кольором картки.
    iconTint: Color = accent,
    // Дані модуля давно не оновлювались (зв'язок лагає) - картка тьмяніє, але
    // лишається на місці з останніми значеннями, а не зникає.
    stale: Boolean = false,
    // Іде запит, після якого відкриється екран деталей: біля назви крутиться індикатор.
    busy: Boolean = false,
    // Маленька кнопка в правому верхньому куті картки - відкриває журнал подій пристрою,
    // пов'язаного з цією карткою. null - кнопки немає (не для кожної картки є свій журнал).
    onOpenJournal: (() -> Unit)? = null,
    // Пункт меню картки - 24-годинний графік напруги (мережа/сонце/АКБ). null - немає
    // такого пункту (лише ці три картки мають власну напругу для показу). Коли задано
    // РАЗОМ з onOpenJournal, одна кнопка-крапки відкриває меню з обома пунктами замість
    // одинокої кнопки журналу.
    onOpenVoltageChart: (() -> Unit)? = null,
    // Момент останньої успішної відповіді МОДУЛЯ (мс, System.currentTimeMillis) -
    // не значення картки, а сам факт свіжої відповіді. 0 - пульс рамки вимкнено.
    updatedAtMs: Long = 0L,
) {
    val cardPulse = remember { Animatable(0f) }
    LaunchedEffect(updatedAtMs) {
        if (updatedAtMs > 0L) {
            cardPulse.snapTo(1f)
            cardPulse.animateTo(0f, animationSpec = tween(durationMillis = 900))
        }
    }

    val numberGlow = remember { Animatable(0f) }
    LaunchedEffect(valueText) {
        numberGlow.snapTo(1f)
        numberGlow.animateTo(0f, animationSpec = tween(durationMillis = 700))
    }

    val staleAlpha by animateFloatAsState(if (stale) 0.5f else 1f, label = "stale-alpha")
    val cardModifier = (if (onClick != null) {
        modifier.clickable(onClick = onClick)
    } else {
        modifier
    }).alpha(staleAlpha)

    Card(
        modifier = cardModifier,
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.16f + cardPulse.value * 0.55f)),
    ) {
        Box {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted,
                )
                if (busy) {
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = accent,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            AnimatedContent(targetState = valueText, label = "metric-value") { text ->
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = text,
                        style = HeroNumber.copy(
                            shadow = Shadow(
                                color = accent.copy(alpha = numberGlow.value),
                                offset = Offset.Zero,
                                blurRadius = 28f * numberGlow.value,
                            ),
                        ),
                        color = TextPrimary,
                    )
                    if (unit.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = unit,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                            modifier = Modifier.padding(bottom = 5.dp),
                        )
                    }
                }
            }
            if (details.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = OutlineSubtle, thickness = 1.dp)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    details.forEach { (label, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary,
                            )
                        }
                    }
                }
            }
        }
        if (onOpenVoltageChart != null) {
            var menuExpanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .size(28.dp)
                        .background(SurfaceContainerHigh.copy(alpha = 0.6f), CircleShape),
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Меню картки",
                        tint = TextMuted,
                        modifier = Modifier.size(15.dp),
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (onOpenJournal != null) {
                        DropdownMenuItem(
                            text = { Text("Журнал подій") },
                            leadingIcon = { Icon(Icons.Filled.Article, contentDescription = null) },
                            onClick = { menuExpanded = false; onOpenJournal() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Графік за 24 год") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = null) },
                        onClick = { menuExpanded = false; onOpenVoltageChart() },
                    )
                }
            }
        } else if (onOpenJournal != null) {
            IconButton(
                onClick = onOpenJournal,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(28.dp)
                    .background(SurfaceContainerHigh.copy(alpha = 0.6f), CircleShape),
            ) {
                Icon(
                    Icons.Filled.Article,
                    contentDescription = "Журнал подій",
                    tint = TextMuted,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        }
    }
}
