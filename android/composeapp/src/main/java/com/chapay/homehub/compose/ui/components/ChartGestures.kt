package com.chapay.homehub.compose.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import com.chapay.homehub.compose.ui.theme.AccentPv
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
 *  - курсор увімкнено ([cursorEnabled]) - веде вертикальну лінію спостереження ([onCursor]);
 *  - курсор вимкнено - зсуває графік ([onPan] у пікселях), а короткий дотик без руху
 *    віддає [onTap].
 * [onZoom] отримує x центру жесту (px) і множник масштабу.
 */
fun Modifier.chartGestures(
    key: Any?,
    cursorEnabled: Boolean,
    onCursor: (Offset) -> Unit,
    onPan: (Float) -> Unit,
    onZoom: (centroidX: Float, factor: Float) -> Unit,
    onTap: (Offset) -> Unit = {},
): Modifier = pointerInput(key, cursorEnabled) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var multiTouch = false
        var moved = false
        if (cursorEnabled) onCursor(down.position)
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
                if (cursorEnabled) {
                    onCursor(change.position)
                } else if (moved) {
                    val dx = change.position.x - change.previousPosition.x
                    if (dx != 0f) onPan(dx)
                }
                if (change.positionChanged()) change.consume()
            }
        } while (event.changes.any { it.pressed })
        if (!multiTouch && !moved && !cursorEnabled) onTap(down.position)
    }
}

/** Кнопки для TopAppBar: лінія спостереження, масштаб +/−, скидання масштабу. */
@Composable
fun ChartToolbarActions(
    cursorOn: Boolean,
    onCursorChange: (Boolean) -> Unit,
    viewport: ChartViewport,
) {
    IconButton(onClick = { onCursorChange(!cursorOn) }) {
        Icon(
            imageVector = if (cursorOn) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
            contentDescription = if (cursorOn) "Вимкнути лінію спостереження" else "Увімкнути лінію спостереження",
            tint = if (cursorOn) AccentPv else TextMuted,
        )
    }
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
