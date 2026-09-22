package com.chapay.homehub.compose.ui.theme

import androidx.compose.ui.graphics.Color

// "Пульт керування" - глибокий графіт замість чистого чорного, приглушені
// (не неонові) акцентні кольори за змістом метрики. Лише темна тема.
val SurfaceDeepest = Color(0xFF0D0F16)
val Surface = Color(0xFF12141C)
val SurfaceContainerLow = Color(0xFF181B25)
val SurfaceContainer = Color(0xFF1E212D)
val SurfaceContainerHigh = Color(0xFF262A38)
val SurfaceContainerHighest = Color(0xFF2F3444)
val OutlineSubtle = Color(0xFF33384A)

val TextPrimary = Color(0xFFF0F2F8)
val TextMuted = Color(0xFF9BA3B4)

// Смислові кольори пристроїв - однакові для картки/іконки/акценту, щоб
// картку можна було впізнати оком без читання підпису.
val AccentPv = Color(0xFFE8A94C) // сонце/PV - бурштиновий
val AccentGrid = Color(0xFF5B8DEF) // мережа - синій
val AccentBattery = Color(0xFF4CBB8A) // АКБ - зелений
val AccentLoad = Color(0xFFE5677A) // навантаження - кораловий
val AccentBoiler = Color(0xFFD97757) // бойлери - теракотовий (тепло)
val AccentPump = Color(0xFF3FB6B0) // насос - бірюзовий (вода/рух)
val AccentGate = Color(0xFF8B7FE8) // ворота - фіолетовий (механіка)

val StatusGood = AccentBattery
val StatusWarn = AccentPv
val StatusAlert = AccentLoad
val StatusUnknown = TextMuted
