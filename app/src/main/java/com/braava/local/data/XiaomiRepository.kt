package com.braava.local.data

import com.braava.local.data.xiaomi.MiioClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Parsed `get_status` reply of a Xiaomi/Roborock vacuum. */
data class XiaomiStatus(
    val state: Int,
    val errorCode: Int,
    val battery: Int,
    val cleanTimeSec: Int,
    val cleanAreaMm2: Long,
) {
    val stateLabel: String get() = STATE_LABELS[state] ?: "Состояние $state"
    val cleanMinutes: Int get() = cleanTimeSec / 60
    val areaM2: Double get() = cleanAreaMm2 / 1_000_000.0

    companion object {
        private val STATE_LABELS = mapOf(
            0 to "Неизвестно",
            1 to "Запуск",
            2 to "На базе",
            3 to "Ожидание",
            4 to "Ручное управление",
            5 to "Уборка",
            6 to "Возврат на базу",
            7 to "Ручной режим",
            8 to "Зарядка",
            9 to "Ошибка зарядки",
            10 to "Пауза",
            11 to "Точечная уборка",
            12 to "Ошибка",
            13 to "Выключение",
            14 to "Обновление",
            15 to "Стыковка",
            16 to "Движение к точке",
            17 to "Уборка зоны",
            18 to "Уборка комнат",
        )
    }
}

/**
 * Local control of one Xiaomi vacuum over miIO (UDP 54321).
 *
 * Polling runs while a Xiaomi robot is selected; every command opens a short
 * miIO session, so no persistent connection is needed. The robot may be asleep
 * on the dock and not answer at all until it is woken (start a cleaning).
 */
class XiaomiRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var config: RobotConfig? = null

    private val _status = MutableStateFlow<XiaomiStatus?>(null)
    val status: StateFlow<XiaomiStatus?> = _status.asStateFlow()

    private val _rooms = MutableStateFlow<List<Int>>(emptyList())
    val rooms: StateFlow<List<Int>> = _rooms.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Switches to [config] (null stops polling and clears the state). */
    fun select(config: RobotConfig?) {
        val old = this.config
        if (old?.blid == config?.blid && old?.ip == config?.ip && old?.token == config?.token) {
            return
        }
        this.config = config
        pollJob?.cancel()
        _status.value = null
        _rooms.value = emptyList()
        _error.value = null
        if (config != null) startPolling()
    }

    fun refresh() {
        val cfg = config ?: return
        scope.launch { loadStatus(cfg) }
    }

    fun loadRooms() {
        val cfg = config ?: return
        scope.launch {
            _busy.value = true
            runCatching {
                val array = client(cfg).roomMapping()
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONArray(index)?.optInt(0)?.takeIf { it > 0 }
                }.distinct().sorted()
            }.onSuccess {
                _rooms.value = it
                _error.value = null
            }.onFailure {
                _error.value = describe(it)
            }
            _busy.value = false
        }
    }

    fun start() = runCommand("Убрать") { it.start() }

    fun pause() = runCommand("Пауза") { it.pause() }

    fun stop() = runCommand("Стоп") { it.stop() }

    fun dock() = runCommand("Домой") { it.charge() }

    fun find() = runCommand("Найти") { it.findMe() }

    fun cleanSegments(segments: List<Int>) {
        if (segments.isEmpty()) return
        runCommand("Уборка комнат") { it.cleanSegments(segments) }
    }

    // ------------------------------------------------------------------

    private fun startPolling() {
        pollJob = scope.launch {
            while (isActive) {
                val cfg = config ?: break
                loadStatus(cfg)
                delay(POLL_MS)
            }
        }
    }

    private suspend fun loadStatus(cfg: RobotConfig) {
        runCatching { fetchStatus(cfg) }
            .onSuccess {
                _status.value = it
                _error.value = null
            }
            .onFailure {
                _error.value = describe(it)
            }
    }

    private fun fetchStatus(cfg: RobotConfig): XiaomiStatus {
        val array = client(cfg).status()
        if (array.length() < 6) throw IllegalStateException("Короткий ответ get_status")
        return XiaomiStatus(
            state = array.optInt(0),
            errorCode = array.optInt(1),
            battery = array.optInt(2),
            cleanTimeSec = array.optInt(4),
            cleanAreaMm2 = array.optLong(5),
        )
    }

    private fun runCommand(label: String, block: (MiioClient) -> Unit) {
        val cfg = config ?: return
        scope.launch {
            _busy.value = true
            try {
                block(client(cfg))
                _error.value = null
                delay(700)
                loadStatus(cfg)
            } catch (e: Exception) {
                _error.value = "$label: ${describe(e)}"
            } finally {
                _busy.value = false
            }
        }
    }

    private fun client(cfg: RobotConfig): MiioClient {
        val token = cfg.token
        if (token.isNullOrBlank()) throw IllegalStateException("У робота нет miIO-токена")
        return MiioClient(cfg.ip.trim(), token.trim())
    }

    private fun describe(error: Throwable): String =
        generateSequence(error as Throwable?) { it.cause }
            .mapNotNull { it.message?.takeIf { message -> message.isNotBlank() } }
            .distinct()
            .joinToString(" → ")
            .ifBlank { error.javaClass.simpleName }

    private companion object {
        const val POLL_MS = 3000L
    }
}
