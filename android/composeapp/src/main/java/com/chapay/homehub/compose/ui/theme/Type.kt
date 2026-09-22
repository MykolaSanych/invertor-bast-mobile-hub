package com.chapay.homehub.compose.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Системний Roboto - без завантажуваних шрифтів. HeroNumber - окремий стиль
// (не частина Typography Material3) для великої "жирної" цифри на картці,
// з табличними цифрами (FontFeatureSettings tnum), щоб розряди не стрибали
// вбік при кожному оновленні значення.
val HeroNumber = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 32.sp,
    lineHeight = 36.sp,
)

val HomeHubTypography = Typography()
