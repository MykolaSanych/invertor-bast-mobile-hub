package com.chapay.homehub.compose.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import com.chapay.homehub.compose.ui.theme.TextPrimary
import com.chapay.homehub.compose.ui.theme.TextMuted

/**
 * Видиме вікно осі X графіка: [start, end] усередині [domainMin, domainMax].
 * Масштаб і зсув змінюють лише вікно - самі дані графік не чіпає.
 */
@Stable
class ChartViewport(
    val domainMin: Float,
    val domainMax: Float,
    private val minSpan: Float,
) {
    var start by mutableFloatStateOf(domainMin)
        private set
    var end by mutableFloatStateOf(domainMax)
        private set

    val span: Float get() = end - start
    val isZoomed: Boolean get() = span < (domainMax - domainMin) - 0.001f

    private fun setWindow(newStart: Float, newSpan: Float) {
        val fullSpan = domainMax - domainMin
        val s = newSpan.coerceIn(minOf(minSpan, fullSpan), fullSpan)
        val a = newStart.coerceIn(domainMin, domainMax - s)
        start = a
        end = a + s
    }

    /** Зсув вікна на частку його ширини (додатне - вперед по осі). */
    fun panFraction(deltaFraction: Float) = setWindow(start + deltaFraction * span, span)

    /** Масштаб навколо точки anchorFraction (0..1 у межах вікна); factor > 1 - наближення. */
    fun zoomAround(anchorFraction: Float, factor: Float) {
        if (factor <= 0f) return
        val fullSpan = domainMax - domainMin
        val newSpan = (span / factor).coerceIn(minOf(minSpan, fullSpan), fullSpan)
        val anchor = start + anchorFraction * span
        setWindow(anchor - anchorFraction * newSpan, newSpan)
    }

    fun zoomCentered(factor: Float) = zoomAround(0.5f, factor)

    fun reset() = setWindow(domainMin, domainMax - domainMin)
}

@Composable
fun rememberChartViewport(key: Any?, domainMin: Float, domainMax: Float, minSpan: Float): ChartViewport =
    remember(key, domainMin, domainMax, minSpan) { ChartViewport(domainMin, domainMax, minSpan) }

/**
 * Жести графіка. Два пальці - масштаб і зсув. Один палець:
 *  - [dragMovesCursor] (графік не наближено) - веде вертикальну лінію-курсор ([onCursor]);
 *  - інакше (графік наближено) - зсуває його ([onPan] у пікселях), а короткий дотик
 *    без руху віддає [onTap] (поставити курсор / обрати лінію).
 * [onZoom] отримує x центру жесту (px) і множник масштабу.
 */
fun Modifier.chartGestures(
    key: Any?,
    dragMovesCursor: Boolean,
    onCursor: (Offset) -> Unit,
    onPan: (Float) -> Unit,
    onZoom: (centroidX: Float, factor: Float) -> Unit,
    onTap: (Offset) -> Unit = {},
): Modifier = pointerInput(key, dragMovesCursor) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var multiTouch = false
        var moved = false
        if (dragMovesCursor) onCursor(down.position)
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                multiTouch = true
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                val centroid = event.calculateCentroid()
                if (zoom != 1f) onZoom(centroid.x, zoom)
                if (pan.x != 0f) onPan(pan.x)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else if (pressed.size == 1 && !multiTouch) {
                val change: PointerInputChange = pressed[0]
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                if (dragMovesCursor) {
                    onCursor(change.position)
                } else if (moved) {
                    val dx = change.position.x - change.previousPosition.x
                    if (dx != 0f) onPan(dx)
                }
                if (change.positionChanged()) change.consume()
            }
        } while (event.changes.any { it.pressed })
        if (!multiTouch && !moved && !dragMovesCursor) onTap(down.position)
    }
}

/** Кнопки для TopAppBar: масштаб +/−, скидання масштабу. */
@Composable
fun ChartToolbarActions(viewport: ChartViewport) {
    IconButton(onClick = { viewport.zoomCentered(1.6f) }) {
        Icon(Icons.Filled.ZoomIn, contentDescription = "Наблизити")
    }
    IconButton(onClick = { viewport.zoomCentered(1f / 1.6f) }, enabled = viewport.isZoomed) {
        Icon(Icons.Filled.ZoomOut, contentDescription = "Віддалити")
    }
    IconButton(onClick = { viewport.reset() }, enabled = viewport.isZoomed) {
        Icon(Icons.Filled.ZoomOutMap, contentDescription = "Весь день")
    }
}

/**
 * Перемикач видимості однієї лінії/рядка: "око" + підпис (+ значення). Натискання
 * ховає або показує відповідну лінію на графіку; прихована виглядає тьмяною.
 */
@Composable
fun VisibilityToggle(
    label: String,
    color: Color,
    visible: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
            contentDescription = if (visible) "Сховати: $label" else "Показати: $label",
            tint = if (visible) color else TextMuted.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (visible) TextPrimary else TextMuted.copy(alpha = 0.6f),
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (visible) TextMuted else TextMuted.copy(alpha = 0.5f),
                )
            }
        }
    }
}
