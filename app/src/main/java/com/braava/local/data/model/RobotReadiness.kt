package com.braava.local.data.model

/**
 * Decoders for the robot's readiness and pad fields.
 *
 * `cleanMissionStatus.notReady` is NOT a bitmask: it is an index into
 * iRobot's 73-entry `RobotReadinessState` enum, and the firmware maps the
 * wire value to that index through a lookup table (no arithmetic rule).
 * Sources: iRobot Home 7.18.0 APK analysis and firmware dumps.
 *
 * An unconfirmed wire value is shown as its number rather than guessed at.
 */
object RobotReadiness {

    private val WIRE_TO_INDEX = mapOf(
        1 to 1, 2 to 2, 3 to 3, 4 to 4, 6 to 6, 7 to 7, 10 to 10,
        15 to 12, 16 to 13, 18 to 15, 21 to 18, 22 to 19, 24 to 22,
        26 to 26, 28 to 31, 29 to 32, 31 to 23, 34 to 17, 35 to 33,
        36 to 34, 51 to 38, 57 to 39, 72 to 66,
        25 to 25, 32 to 20,
        78 to 69,
        23 to 29, 37 to 29, 38 to 29,
        33 to 21, 39 to 37,
        66 to 62, 67 to 63, 68 to 64, 69 to 65,
    )

    private val LABELS = mapOf(
        0 to "Готов",
        1 to "Датчик края сработал",
        2 to "Оба колеса подняты",
        3 to "Левое колесо поднято",
        4 to "Правое колесо поднято",
        5 to "Финальная стыковка",
        6 to "Щётки застряли",
        7 to "Нет контейнера",
        8 to "Сбой навигации",
        9 to "Не сконфигурирован",
        10 to "В режиме rcon",
        11 to "Неверная команда",
        12 to "Недостаточный заряд",
        13 to "Контейнер полон",
        14 to "Нет связи с навигацией",
        15 to "Идёт обновление прошивки",
        16 to "Спящий режим зарядки",
        17 to "Неверная салфетка",
        18 to "Система безопасности отключена",
        19 to "Проблема с гироскопом",
        20 to "Крышка открыта",
        21 to "Обнаружен удар",
        22 to "Несовпадение версии карты",
        23 to "Мало воды в баке",
        24 to "Нет салфетки",
        25 to "Бампер не отвечает",
        26 to "Питание отключено",
        27 to "Новая чистящая голова",
        28 to "У расписания нет часов",
        29 to "Ошибка авторизации батареи",
        30 to "Ходовая часть не отвечает",
        31 to "Неверная калибровка",
        32 to "В зоне базы",
        33 to "Тайм-аут определения салфетки",
        34 to "Засор автоочистки",
        35 to "Сбой шины питания",
        36 to "Тайм-аут зарядки",
        37 to "Сохранение карты",
        38 to "Камера не работает",
        39 to "Отказ движения назад",
        40 to "Ошибка безопасности: ограничение",
        41 to "Ошибка безопасности: наклон",
        42 to "Ошибка безопасности: опрокидывание",
        43 to "Ошибка безопасности: подъём",
        44 to "Ошибка безопасности: стоп",
        45 to "Аппаратная ошибка безопасности",
        46 to "Неизвестная ошибка безопасности",
        47 to "Ошибка безопасности: ручка поднята",
        48 to "Сбой локализации",
        49 to "Мало маяков",
        50 to "Отказ предпроверки",
        51 to "Безопасность: заклинило левое колесо",
        52 to "Безопасность: заклинило правое колесо",
        53 to "Не на базе",
        54 to "Мост не ограничен",
        55 to "Мост не достаёт до базы",
        56 to "Мост не достаёт до участка",
        57 to "Перегрев мотора колеса",
        58 to "Переохлаждение мотора колеса",
        59 to "Перегрев мотора щёток",
        60 to "Переохлаждение мотора щёток",
        61 to "Парк отключён",
        62 to "Подписка истекла",
        63 to "Плата навигации неисправна",
        64 to "Загрузка карты",
        65 to "Снят с базы",
        66 to "Течь бака",
        67 to "Датчик уровня жидкости неисправен",
        68 to "Датчик уровня жидкости неисправен",
        69 to "Несовпадение чистящей головы",
        70 to "Датчик типа пола неисправен",
        71 to "Неизвестно",
        72 to "Обновление ПО",
    )

    /** Human label for a raw `notReady` wire value, or null when ready. */
    fun label(wire: Int?): String? {
        if (wire == null || wire == 0) return null
        val index = WIRE_TO_INDEX[wire] ?: if (wire <= 10) wire else null
        return index?.let { LABELS[it] } ?: "Причина не готова: $wire"
    }

    private val PAD = mapOf(
        "reusableDry" to "Сухая (многоразовая)",
        "reusableWet" to "Влажная (многоразовая)",
        "reusablewet" to "Влажная (многоразовая)",
        "dispDry" to "Сухая (одноразовая)",
        "dispWet" to "Влажная (одноразовая)",
        "padPlate" to "Установлена платформа",
        "noPad" to "Платформа не установлена",
        "invalid" to "Салфетка не распознана",
    )

    fun padLabel(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return PAD[raw] ?: raw
    }
}
