package com.chapay.homehub.compose.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.chapay.homehub.compose.ui.theme.HeroNumber
import com.chapay.homehub.compose.ui.theme.SurfaceContainer
import com.chapay.homehub.compose.ui.theme.TextMuted
import com.chapay.homehub.compose.ui.theme.TextPrimary

/**
 * Картка метрики з "героєм"-цифрою. Коли [valueText] змінюється, край картки
 * робить короткий, стриманий пульс кольором [accent] - видимий, але не
 * втомливий зворотний зв'язок "дані щойно оновились" (аналог cardUpdateFlash
 * з чинного WebView-додатку, тут - через Animatable без ручного керування DOM).
 */
@Composable
fun MetricCard(
    title: String,
    valueText: String,
    unit: String,
    accent: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    secondaryText: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(valueText) {
        pulse.snapTo(1f)
        pulse.animateTo(0f, animationSpec = tween(durationMillis = 900))
    }

    val cardModifier = if (onClick != null) {
        modifier.clickable(onClick = onClick)
    } else {
        modifier
    }

    Card(
        modifier = cardModifier,
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.16f + pulse.value * 0.55f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted,
                )
            }
            Spacer(Modifier.height(10.dp))
            AnimatedContent(targetState = valueText, label = "metric-value") { text ->
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(text = text, style = HeroNumber, color = TextPrimary)
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
            if (!secondaryText.isNullOrEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
