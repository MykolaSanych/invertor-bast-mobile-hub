package com.chapay.homehub.compose.data

import kotlin.math.roundToInt

/**
 * Насос Italtecnica Sirio (частотник), власна прошивка ESP8266 (проєкт
 * `sirio_esp32`, не частина Budynok firmware) - окремий HTTP API, без пароля
 * для статусу/базових параметрів (лише розширені - за PIN, той самий, що в
 * самій прошивці Sirio). Поля дзеркалять `handleStatus()` у
 * sirio_esp32/firmware/src/main.cpp.
 */
data class SirioStatus(
    val standBy: Boolean,
    val motorOn: Boolean,
    val errorActive: Boolean,
    val errorNumber: Int,
    val extEnabled: Boolean,
    val barOrPsi: Boolean, // false=БАР, true=PSI
    val setPointPress10: Int, // тиск у десятих - ділити на 10 для бар
    val freqMax: Int,
    val ampMax10: Int,
    val amp10: Int,
    val tempIgbt: Int,
    val temp: Int,
    val hPowerOn: Int,
    val hPowerRunning: Int,
    val nrStarts: Int,
    // Наразі ЗАВЖДИ 0 - прошивка sirio_esp32 ще не підв'язала ці регістри
    // (TODO у firmware/src/main.cpp). Поля лишені тут, щоб оцінка витрати й
    // показники панелі самі "запрацювали", коли прошивку доповнять, без змін
    // на боці застосунку.
    val press10: Int,
    val freqHz: Int,
    val volt: Int,
    // e0..e13 - лічильники аварій за кодом (індекс = код E).
    val errorCounts: Map<Int, Int>,
) {
    val setPointPressBar: Double get() = setPointPress10 / 10.0
    val ampA: Double get() = amp10 / 10.0
    val ampMaxA: Double get() = ampMax10 / 10.0
    val pressBar: Double get() = press10 / 10.0
}

/**
 * Паспортна крива Q-H насоса (вкладка "Насос") - для наближеної оцінки
 * витрати за законами подібності. `points` - (витрата л/хв, напір м),
 * нормовано на fn. qunit: 0 = л/хв, 1 = м³/год (лише для показу).
 */
data class SirioPumpConfig(
    val depth: Double,
    val fn: Double,
    val qunit: Int,
    val points: List<Pair<Double, Double>>,
)

/**
 * Оцінка витрати (л/хв), порт `estFlow()` з webui/app/index.html. null - немає
 * достатніх даних (крива не задана, або прошивка ще не віддає press/freq -
 * зараз це TODO у sirio_esp32: обидва завжди 0, тож оцінка свідомо НЕ
 * показується, а не бреше нулем/вигаданим числом).
 */
fun estimateSirioFlowLpm(pump: SirioPumpConfig?, pressBar: Double, freqHz: Double): Double? {
    if (pump == null || pump.points.size < 2) return null
    if (freqHz <= 0 || pump.fn <= 0 || pressBar <= 0) return null
    val headM = pressBar * 10.197 + pump.depth
    val scale = freqHz / pump.fn
    val pts = pump.points.map { (q, h) -> (q * scale) to (h * scale * scale) }.sortedBy { it.first }
    if (pts.isEmpty()) return null
    if (headM >= pts.first().second) return 0.0
    val last = pts.last()
    if (headM <= last.second) return last.first
    for (i in 0 until pts.size - 1) {
        val (q1, h1) = pts[i]
        val (q2, h2) = pts[i + 1]
        if (headM <= h1 && headM >= h2) {
            val t = (h1 - headM) / ((h1 - h2).takeIf { it != 0.0 } ?: 1.0)
            return q1 + t * (q2 - q1)
        }
    }
    return null
}

/** PARAM[i] - назва/одиниця/масштаб(відображення=raw/scale-offset)/перелік варіантів. */
data class SirioParamDef(
    val name: String,
    val unit: String? = null,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    val enumValues: Map<Int, String>? = null,
    // Нестандартне перетворення (параметри 56/57 - напруга мережі): raw -> показане значення.
    val custom: ((Int) -> Double)? = null,
    // Обернене до custom - показане значення -> raw (для введення цифрами).
    val customInverse: ((Double) -> Int)? = null,
)

/** Опис і значення за замовчуванням (з мануала), для кнопки "і" біля параметра. */
data class SirioParamInfo(val description: String, val default: String)

object SirioCatalog {
    val PARAMS: Map<Int, SirioParamDef> = mapOf(
        0 to SirioParamDef("Уставка тиску (Pmax)", "бар", 10.0),
        1 to SirioParamDef("Δ тиску для пуску", "бар", 10.0),
        2 to SirioParamDef("Тиск сухого ходу", "бар", 10.0),
        3 to SirioParamDef("Межа тиску", "бар", 10.0),
        4 to SirioParamDef("Уставка тиску 2 (Pmax2)", "бар", 10.0),
        5 to SirioParamDef("Δ тиску для зупинки", "бар", 10.0),
        6 to SirioParamDef("Одиниця виміру", enumValues = mapOf(9 to "BAR", 10 to "PSI")),
        10 to SirioParamDef("Макс. струм (Imax)", "А", 10.0),
        11 to SirioParamDef("Напрямок обертання", enumValues = mapOf(21 to "→ (-->)", 22 to "← (<--)")),
        12 to SirioParamDef("Мінімальна частота", "Гц"),
        13 to SirioParamDef("Частота зупинки", "Гц"),
        14 to SirioParamDef("Номінальна частота двигуна", "Гц"),
        15 to SirioParamDef("Частота комутації", "кГц"),
        16 to SirioParamDef("Корекція частоти", "Гц", offset = 10.0),
        17 to SirioParamDef("М'який пуск", enumValues = mapOf(7 to "Увімк", 8 to "Вимк")),
        20 to SirioParamDef("Датчик потоку", enumValues = mapOf(7 to "Увімк", 8 to "Вимк")),
        21 to SirioParamDef(
            "Джерело команд",
            enumValues = mapOf(18 to "MAN (ручне)", 19 to "0-10 В", 20 to "PRESS (тиск)"),
        ),
        22 to SirioParamDef(
            "Допоміжний контакт",
            enumValues = mapOf(23 to "1 ↔ спарка", 24 to "2 ← старт/стоп", 25 to "3 X2 друга уставка"),
        ),
        23 to SirioParamDef(
            "Вхід плати I/O",
            enumValues = mapOf(27 to "Вимк", 26 to "ERR помилка", 24 to "2 ← старт/стоп", 25 to "3 X2 друга уставка"),
        ),
        24 to SirioParamDef(
            "Вихід плати I/O",
            enumValues = mapOf(3 to "Вимк", 4 to "ERR помилка", 5 to "P.ON працює", 6 to "AUX доп.насос"),
        ),
        25 to SirioParamDef("Затримка зупинки", "с", 10.0),
        26 to SirioParamDef("Інтервал автоскиду", "хв"),
        27 to SirioParamDef("Спроб автоскиду"),
        28 to SirioParamDef("Повний автоскид", enumValues = mapOf(7 to "Увімк", 8 to "Вимк")),
        40 to SirioParamDef("Vboost", "%"),
        41 to SirioParamDef("Сухий хід (затримка)", "с"),
        42 to SirioParamDef(
            "Захист: пусків за годину",
            enumValues = mapOf(11 to "Вимк", 12 to "10", 13 to "20", 14 to "30", 15 to "40", 16 to "50", 17 to "60"),
        ),
        43 to SirioParamDef("Антиблокування", enumValues = mapOf(7 to "Увімк", 8 to "Вимк")),
        44 to SirioParamDef("Мертвий час PWM", "×125нс"),
        45 to SirioParamDef("Ki (інтегральний)"),
        46 to SirioParamDef("Kp (пропорційний)"),
        47 to SirioParamDef("Час boost", "мс", 0.1),
        48 to SirioParamDef("Режим реле", enumValues = mapOf(1 to "NO (розімкн.)", 2 to "NC (замкн.)")),
        50 to SirioParamDef("Ta max", "°C"),
        51 to SirioParamDef("Tm max", "°C"),
        52 to SirioParamDef("Індекс зниження Ta", "Гц/°C"),
        53 to SirioParamDef("Індекс зниження Tm", "Гц/°C"),
        54 to SirioParamDef("Мінімальний потік", "л/хв"),
        55 to SirioParamDef("Темп розгону (рампа)", "Гц/с"),
        56 to SirioParamDef(
            "Мінімальна напруга", "В",
            custom = { r -> (r + 255) * 8.0 / 14.0 - 4 },
            customInverse = { d -> ((d + 4) * 14.0 / 8.0 - 255.0).roundToInt() },
        ),
        57 to SirioParamDef(
            "Максимальна напруга", "В",
            custom = { r -> (r + 255) * 8.0 / 14.0 - 4 },
            customInverse = { d -> ((d + 4) * 14.0 / 8.0 - 255.0).roundToInt() },
        ),
        59 to SirioParamDef("Debug"),
    )

    /** Параметри меню (без входу): показуються одразу. */
    val USER_IDS = listOf(0, 1, 2, 3, 4, 5, 6, 10, 11, 12, 13, 14, 15, 16, 17, 20, 21, 22, 23, 24, 25, 26, 27, 28)

    /** Розширені параметри: потрібен вхід за PIN (та ж обережність, що в самій прошивці Sirio). */
    val ADV_IDS = listOf(40, 41, 42, 43, 44, 45, 46, 47, 48, 50, 51, 52, 53, 54, 55, 56, 57, 59)

    val INFO: Map<Int, SirioParamInfo> = mapOf(
        0 to SirioParamInfo("Уставка — постійний тиск, який Sirio підтримує, регулюючи оберти насоса.", "3.0 бар"),
        1 to SirioParamInfo("На скільки тиск має впасти нижче уставки, щоб насос запустився. Мін. різниця 0.3, рекоменд. ≥0.5 бар.", "0.5 бар"),
        2 to SirioParamInfo("Мін. тиск при макс. частоті, нижче якого спрацьовує захист від сухого ходу (діє лише коли датчик потоку вимкнено).", "0.5 бар"),
        3 to SirioParamInfo("Поріг захисту від надлишкового тиску — інвертор зупиняється до скидання користувачем.", "9.0 бар"),
        4 to SirioParamInfo("Друга уставка тиску; вмикається замиканням допоміжного входу.", "9.0 бар"),
        5 to SirioParamInfo("Наскільки тиск має перевищити уставку для негайної зупинки (захист від перетиску).", "0.5 бар"),
        6 to SirioParamInfo("Одиниця виміру тиску: BAR або PSI.", "BAR"),
        10 to SirioParamInfo("Макс. струм насоса для захисту від перевантаження. Поки стоїть 0.5 A — пристрій вимагає задати робоче значення.", "0.5 A (задати)"),
        11 to SirioParamInfo("Напрямок обертання (3-фазні): міняє чергування фаз без перепідключення. Стрілка орієнтовна.", "-->"),
        12 to SirioParamInfo("Мін. частота пуску/зупинки. 3-фазні ~25 Гц, 1-фазні ~30 Гц.", "25 Гц"),
        13 to SirioParamInfo("Частота, нижче якої двигун зупиняється (робота без датчика потоку). Качає безперервно — підніми; часті пуски — знизь.", "30 Гц"),
        14 to SirioParamInfo("Макс. номінальна частота виходу (50 або 60 Гц). Невірний вибір може пошкодити насос.", "50 Гц"),
        15 to SirioParamInfo("Частота комутації інвертора: 3 / 5 / 10 кГц. Вище — тихіше й плавніше, але гарячіше й більше завад.", "5 кГц"),
        16 to SirioParamInfo("Корекція макс. частоти ±5 Гц відносно номіналу.", "0 Гц"),
        17 to SirioParamInfo("М'який (поступовий) пуск. Якщо вимкнено — пуск на макс. обертах 1 с.", "ON"),
        20 to SirioParamInfo("Вбудований датчик потоку: зупиняє насос при нульовому потоці. Для брудної води можна вимкнути (тоді важливі частота зупинки, тиск сух.ходу та розширювальний бак).", "ON"),
        21 to SirioParamInfo("Джерело команд: за тиском (авто) або ручний режим (клавіатура — без захистів!).", "PRESS"),
        22 to SirioParamInfo("Функція доп. контакту: 1↔ спарка двох Sirio; 2← дист. старт/стоп; 3 X2 друга уставка.", "1 ↔"),
        23 to SirioParamInfo("Функція входу плати I/O: OFF; ERR (зовн. помилка → стоп); 2← старт/стоп (AND з доп.контактом); 3 X2 друга уставка.", "OFF"),
        24 to SirioParamInfo("Функція виходу плати I/O: OFF; ERR (будь-яка помилка); P.ON (насос працює); AUX (допоміжний насос). Навантаження на реле ≤0.3 A.", "OFF"),
        25 to SirioParamInfo("Через скільки секунд зупиняти насос після закриття всіх кранів. Часті пуски при малих витратах — збільш.", "10 с"),
        26 to SirioParamInfo("Через скільки хвилин авто-перезапуск після зупинки по сухому ходу. Макс 240, реком. 60.", "15 хв"),
        27 to SirioParamInfo("К-сть спроб авто-скиду по сухому ходу (0 = вимкнено, макс 20).", "5"),
        28 to SirioParamInfo("ON — авто-скид для БУДЬ-ЯКОЇ помилки, не лише сухого ходу. Обережно: може з часом нашкодити.", "OFF"),
        40 to SirioParamInfo("Відсоток підняття напруги на 0 Гц для компенсації втрат у статорі. Більше значення — вища напруга на двигуні на низьких частотах (кращий пусковий момент), але й більший нагрів.", ""),
        41 to SirioParamInfo("Затримка спрацювання захисту від сухого ходу. Збільш за дуже довгих всмоктувальних трубопроводах або насосах із довгим заливанням.", ""),
        42 to SirioParamInfo("Контроль кількості пусків за годину (виявлення протікань у системі): Вимк або ліміт 10…60. Перевищення → аварія E11.", "Вимк"),
        43 to SirioParamInfo("Проти заклинення під час тривалого простою: якщо увімкнено, насос сам запускається раз на 24 год (коли немає запиту системи), щоб не закисли механічні частини — ущільнення, крильчатка.", "Вимк"),
        44 to SirioParamInfo("Мертвий час (deadtime) між перемиканнями ключів IGBT однієї стійки. Може потребувати корекції середньої вихідної напруги при зміні частоти комутації. Міняти лише за вказівкою виробника.", ""),
        45 to SirioParamInfo("Інтегральний коефіцієнт ПІД-регулятора тиску. Більший Ki — тиск ближче до уставки (менша похибка), але завеликий дає нестабільність і коливання тиску.", ""),
        46 to SirioParamInfo("Пропорційний коефіцієнт ПІД-регулятора тиску. Більший Kp — швидша реакція на зміни тиску, але завеликий спричиняє перетиск або різкі гальмування.", ""),
        47 to SirioParamInfo("Тривалість роботи на макс. частоті на старті (коли м'який пуск вимкнено), перш ніж вступить ПІД-регулювання. Збільш, якщо насос важко стартує (особливо однофазні); зменш, якщо це створює небажаний сплеск.", ""),
        48 to SirioParamInfo("Режим реле виходу плати I/O: нормально розімкнене (NO) чи нормально замкнене (NC).", "NO"),
        50 to SirioParamInfo("Макс. температура середовища, за якою спрацьовує захист від перегріву (E4). Міняти лише за прямою вказівкою виробника — впливає на безпеку.", ""),
        51 to SirioParamInfo("Макс. температура модуля IGBT, за якою спрацьовує захист від перегріву (E5). Міняти лише за вказівкою виробника — впливає на безпеку.", ""),
        52 to SirioParamInfo("Наскільки інвертор знижує макс. частоту насоса при наближенні до «Ta max» (5.0). Зниження вмикається, коли темп. середовища підходить до межі ближче ніж на 5 °C; значення = Гц на кожен °C.", "1 Гц/°C"),
        53 to SirioParamInfo("Те саме, але за температурою модуля IGBT (межа «Tm max», 5.1): на скільки Гц знижувати частоту на кожен °C наближення до межі.", "1 Гц/°C"),
        54 to SirioParamInfo("Поріг мінімального потоку для зупинки насоса.", "6 л/хв"),
        55 to SirioParamInfo("Швидкість рампи прискорення двигуна при переході зі stand-by у роботу (Sirio Universal). На моделях Sirio IPM цей слот — вибір типу двигуна; стокова мітка «IPM mod.» оманлива.", "2 Гц/с"),
        56 to SirioParamInfo("Нижній поріг напруги мережі, за яким спрацьовує захист від зниженої напруги.", "150 В"),
        57 to SirioParamInfo("Верхній поріг напруги мережі для захисту від перенапруги (E1). Приблизно на 30 В нижче цього рівня насос починає плавно й контрольовано гальмуватись, щоб уникнути шкідливих стрибків напруги.", "245 В"),
        59 to SirioParamInfo("Службовий параметр налагодження: вибір внутрішньої змінної процесу для показу на дисплеї. У звичайній роботі не потрібен.", ""),
    )

    val ERROR_NAMES: Map<Int, String> = mapOf(
        0 to "Низька напруга", 1 to "Висока напруга", 2 to "Коротке замикання", 3 to "Сухий хід",
        4 to "Температура середовища", 5 to "Температура IGBT", 6 to "Перевантаження", 8 to "Помилка зв'язку",
        9 to "Перетиск (межа тиску)", 10 to "Зовнішня помилка", 11 to "Забагато пусків/год",
        12 to "Помилка 12В", 13 to "Помилка датчика тиску",
    )

    val ERROR_DESC: Map<Int, String> = mapOf(
        0 to "Напруга живлення надто низька. Перевір вхідну напругу.",
        1 to "Напруга живлення надто висока. Перевір вхідну напругу.",
        2 to "Коротке замикання на виході інвертора (підключення, ізоляція проводів чи двигун). Негайно вимкни живлення й усунь причину — пуск при КЗ небезпечний.",
        3 to "Зупинка через брак води на всмоктуванні. Скидається кнопкою reset; за увімкненого автоскиду пробує відновитись сам.",
        4 to "Перевищено макс. внутрішню температуру середовища інвертора. Перевір умови охолодження.",
        5 to "Перевищено макс. температуру модуля IGBT. Перевір струм насоса й температуру води.",
        6 to "Струм насоса перевищив Imax. Інтенсивне використання, часті пуски, проблема обмоток чи з'єднання двигуна.",
        8 to "Внутрішня помилка послідовного зв'язку Sirio. Звернись у сервіс.",
        9 to "Перевищено поріг тиску (P.Limit). Перевір параметр межі тиску та можливі причини перетиску.",
        10 to "Замкнено вхід I/O при увімкненій функції «зовнішня помилка».",
        11 to "Перевищено к-сть пусків за годину. Перевір протікання в системі та передзаряд баків.",
        12 to "Аномалія у внутрішньому колі живлення 12 В. Потрібна перевірка виробником.",
        13 to "Датчик тиску видає некоректне значення. Потрібна перевірка виробником.",
    )

    /** Код меню у форматі "X.Y" (адреса 21 -> "2.1"), як на дисплеї частотника. */
    fun menuCode(index: Int): String = "${index / 10}.${index % 10}"

    /**
     * Обернене до [formatParamValue] - уведене користувачем число -> raw
     * значення регістра. null для enum-параметрів (там обирають варіант, а не
     * вводять число) або якщо текст не розпізнано.
     */
    fun rawForDisplayValue(index: Int, display: Double): Int? {
        val def = PARAMS[index] ?: return null
        if (def.enumValues != null) return null
        def.customInverse?.let { return it(display) }
        return Math.round((display + def.offset) * def.scale).toInt()
    }

    /** Показане значення параметра: перелік -> підпис, інакше raw/scale-offset. */
    fun formatParamValue(index: Int, raw: Int): String {
        val def = PARAMS[index] ?: return raw.toString()
        def.enumValues?.let { return it[raw] ?: "= $raw" }
        def.custom?.let { return it(raw).roundToInt().toString() }
        val v = raw / def.scale - def.offset
        return if (def.scale == 10.0) "%.1f".format(v) else (Math.round(v * 100) / 100.0).let {
            if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
        }
    }
}
