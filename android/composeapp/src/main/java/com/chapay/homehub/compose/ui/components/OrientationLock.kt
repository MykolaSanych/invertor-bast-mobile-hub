package com.chapay.homehub.compose.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Примусово тримає екран у горизонтальній орієнтації, поки composable у складі. */
@Composable
fun LockLandscapeOrientation() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(Unit) {
        val original = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity.requestedOrientation = original }
    }
}

/**
 * Показує [content] як "ландшафтний" екран усередині портретного вікна - без реального
 * повороту пристрою (телефон лишається портретним, у пейджері вкладок можна вільно
 * гортати вбік поруч із портретними вкладками). Дочірньому composable дістаються
 * SWAPPED-обмеження (він бачить широкий "ландшафтний" простір: ширина = висота цього
 * блоку, висота = ширина), а весь блок повертається на 90° і зсувається так, щоб
 * рівно лягти в портретні межі. Дотики Compose трансформує разом із шаром
 * (graphicsLayer), тож pointerInput дочірнього контенту отримує координати вже у
 * своєму, неповернутому просторі - жести (масштаб/курсор) працюють як і в реальному
 * ландшафті.
 */
@Composable
fun LandscapeInPortrait(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(
        modifier = modifier,
        content = {
            Box(
                Modifier.graphicsLayer {
                    rotationZ = 90f
                    transformOrigin = TransformOrigin(0f, 0f)
                },
            ) {
                content()
            }
        },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        // Дочірній контент міряємо, ніби він на реальному ландшафтному екрані
        // w=h(портрету) x h=w(портрету) - саме тому графіки/хронологія виходять широкими.
        val childConstraints = Constraints.fixed(width = h, height = w)
        val placeable = measurables[0].measure(childConstraints)
        layout(w, h) {
            // rotationZ=90 з transformOrigin у лівому верхньому куті шару обертає його
            // за годинниковою стрілкою навколо цієї точки; щоб після повороту шар (h x w)
            // рівно ліг у портретні межі (w x h) від (0,0), сам шар кладемо зі зсувом +w по X.
            placeable.place(x = w, y = 0)
        }
    }
}
