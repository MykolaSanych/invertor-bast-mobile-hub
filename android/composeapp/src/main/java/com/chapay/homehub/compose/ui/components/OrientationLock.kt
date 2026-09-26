package com.chapay.homehub.compose.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
 *
 * Системні смуги (статус-бар/годинник згори, навігаційна знизу) - фізичні, "приклеєні" до
 * країв реального екрана, і в edge-to-edge застосунку (звичний TopAppBar сам віднімає їх
 * зі свого відступу) вони НЕ повертаються разом із вмістом: після нашого повороту на 90°
 * "верх" дочірнього composable фізично опиняється не вгорі екрана, а збоку, тож його власний
 * TopAppBar резервує відступ не там, де реально йде статус-бар - контент вилазить під нього.
 * Тому тут відступи під ці смуги рахуємо самі (у фізичних вимірах) і віднімаємо від
 * доступного простору ДО повороту.
 */
@Composable
fun LandscapeInPortrait(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val statusBarPx = WindowInsets.statusBars.getTop(density)
    val navBarPx = WindowInsets.navigationBars.getBottom(density)

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
        // Дочірній контент міряємо, ніби він на реальному ландшафтному екрані, лише
        // трохи вужчому за фізичну висоту порту - на статус-бар і навігаційну смугу
        // (вони мапляться саме на цю вісь дочірнього контенту, див. коментар нижче).
        val childWidth = (h - statusBarPx - navBarPx).coerceAtLeast(0)
        val childConstraints = Constraints.fixed(width = childWidth, height = w)
        val placeable = measurables[0].measure(childConstraints)
        layout(w, h) {
            // rotationZ=90 з transformOrigin у лівому верхньому куті шару обертає його
            // за годинниковою стрілкою навколо цієї точки; щоб після повороту шар ліг рівно
            // в портретні межі, сам шар кладемо зі зсувом +w по X. Зсув +statusBarPx по Y -
            // це та сама "нижня межа туди, де починається дочірній х=0" точка: після
            // повороту вона стає фізичним ВЕРХОМ контенту, тож саме тут лишаємо статус-бару
            // його смугу; navBarPx лишається порожнім знизу самим підбором childWidth.
            placeable.place(x = w, y = statusBarPx)
        }
    }
}
