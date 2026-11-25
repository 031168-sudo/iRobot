package com.braava.local.data

import android.content.Context
import com.braava.local.data.cloud.XiaomiCloud
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
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Parsed `get_status` reply of a Xiaomi/Roborock vacuum. */
data class XiaomiCleanRecord(
    val start: Long,
    val durationSec: Int,
    val areaMm2: Long,
)

data class XiaomiConsumables(
    val filterSec: Long,
    val mainBrushSec: Long,
    val sideBrushSec: Long,
    val sensorSec: Long,
)

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
 * Xiaomi vacuum control.
 *
 * Tries local miIO first (fast, no internet). When the phone is on a VPN or
 * mobile data and the LAN is unreachable, the same command is sent through the
 * Xiaomi cloud if a session is available. Status and room ids follow the same
 * local-first / cloud-second rule.
 */
class XiaomiRepository(context: Context) {

    private val appContext = context.applicationContext
    private val roomStore = XiaomiRoomStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var config: RobotConfig? = null

    private var cloudSession: XiaomiCloud.Session? = null
    private var cloudDid: String? = null

    private val _status = MutableStateFlow<XiaomiStatus?>(null)
    val status: StateFlow<XiaomiStatus?> = _status.asStateFlow()

    private val _rooms = MutableStateFlow<List<Int>>(emptyList())
    val rooms: StateFlow<List<Int>> = _rooms.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _history = MutableStateFlow<List<XiaomiCleanRecord>>(emptyList())
    val history: StateFlow<List<XiaomiCleanRecord>> = _history.asStateFlow()

    private val _consumables = MutableStateFlow<XiaomiConsumables?>(null)
    val consumables: StateFlow<XiaomiConsumables?> = _consumables.asStateFlow()

    private val _soundVolume = MutableStateFlow<Int?>(null)
    val soundVolume: StateFlow<Int?> = _soundVolume.asStateFlow()

    private val _extraBusy = MutableStateFlow(false)
    val extraBusy: StateFlow<Boolean> = _extraBusy.asStateFlow()

    fun setCloud(session: XiaomiCloud.Session?, did: String?) {
        cloudSession = session
        cloudDid = did
    }

    /** Switches to [config] (null stops polling and clears the state). */
    fun select(config: RobotConfig?) {
        val old = this.config
        if (old?.blid == config?.blid && old?.ip == config?.ip && old?.token == config?.token) {
            return
        }
        this.config = config
        pollJob?.cancel()
        _status.value = null
        _rooms.value = config?.let { roomStore.load(it.blid) } ?: emptyList()
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
                val array = runCatching { client(cfg).roomMapping() }.getOrNull()
                    ?: cloudCommand("get_room_mapping")?.optJSONArray("result")
                    ?: throw IllegalStateException("Комнаты не получены")
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONArray(index)?.optInt(0)?.takeIf { it > 0 }
                }.distinct().sorted()
            }.onSuccess {
                _rooms.value = it
                roomStore.save(cfg.blid, it)
                _error.value = null
                log("↓ комнаты: ${it.joinToString(",")}")
            }.onFailure {
                _error.value = describe(it)
                log("✗ комнаты: ${describe(it)}")
            }
            _busy.value = false
        }
    }

    fun start() = runCommand("Убрать", "app_start")

    fun pause() = runCommand("Пауза", "app_pause")

    fun stop() = runCommand("Стоп", "app_stop")

    fun dock() = runCommand("Домой", "app_charge")

    fun find() = runCommand("Найти", "find_me")

    fun cleanSegments(segments: List<Int>) {
        if (segments.isEmpty()) return
        val ids = JSONArray().apply { segments.forEach { put(it) } }
        val localParams = JSONArray().put(
            JSONObject().put("segments", ids).put("repeat", 1),
        )
        // The CN cloud wants a plain array of segment ids: [17].
        val cloudParams = JSONArray().apply { segments.forEach { put(it) } }
        runCommand(
            "Уборка комнат",
            "app_segment_clean",
            localParams,
            stopFirst = true,
            cloudParams = cloudParams,
        )
    }

    // ------------------------------------------------------------------

    private fun log(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _log.value = (_log.value + "$stamp  $line").takeLast(50)
    }

    fun loadHistory() {
        val cfg = config ?: return
        scope.launch {
            _extraBusy.value = true
            try {
                val summary = cloudCommand("get_clean_summary")
                    ?: throw IllegalStateException("Нет облачной сессии")
                val result = summary.optJSONArray("result")
                    ?: throw IllegalStateException("Нет истории уборок")
                val stamps = result.optJSONArray(3) ?: JSONArray()
                val records = mutableListOf<XiaomiCleanRecord>()
                for (index in 0 until minOf(stamps.length(), 10)) {
                    val stamp = stamps.optLong(index)
                    val record = cloudCommand(
                        "get_clean_record",
                        JSONArray().put(stamp),
                    ) ?: continue
                    val arr = record.optJSONArray("result")?.optJSONArray(0) ?: continue
                    records += XiaomiCleanRecord(
                        start = arr.optLong(0),
                        durationSec = arr.optInt(2),
                        areaMm2 = arr.optLong(3),
                    )
                }
                _history.value = records
                _error.value = null
                log("↓ история: ${records.size} записей")
            } catch (e: Exception) {
                _error.value = describe(e)
                log("✗ история: ${describe(e)}")
            } finally {
                _extraBusy.value = false
            }
        }
    }

    fun loadConsumables() {
        val cfg = config ?: return
        scope.launch {
            _extraBusy.value = true
            try {
                val json = cloudCommand("get_consumable")
                    ?: throw IllegalStateException("Нет облачной сессии")
                val obj = json.optJSONArray("result")?.optJSONObject(0)
                    ?: throw IllegalStateException("Нет данных о расходниках")
                _consumables.value = XiaomiConsumables(
                    filterSec = obj.optLong("filter_work_time"),
                    mainBrushSec = obj.optLong("main_brush_work_time"),
                    sideBrushSec = obj.optLong("side_brush_work_time"),
                    sensorSec = obj.optLong("sensor_dirty_time"),
                )
                _error.value = null
            } catch (e: Exception) {
                _error.value = describe(e)
                log("✗ расходники: ${describe(e)}")
            } finally {
                _extraBusy.value = false
            }
        }
    }

    fun loadSound() {
        val cfg = config ?: return
        scope.launch {
            try {
                val json = cloudCommand("get_sound_volume")
                    ?: throw IllegalStateException("Нет облачной сессии")
                _soundVolume.value = json.optJSONArray("result")?.optInt(0)
                _error.value = null
            } catch (e: Exception) {
                _error.value = describe(e)
            }
        }
    }

    fun setSound(volume: Int) {
        val cfg = config ?: return
        val value = volume.coerceIn(0, 100)
        scope.launch {
            runCatching {
                cloudCommand("change_sound_volume", JSONArray().put(value))
                    ?: throw IllegalStateException("Нет облачной сессии")
            }.onSuccess {
                _soundVolume.value = value
                _error.value = null
            }.onFailure {
                _error.value = describe(it)
            }
        }
    }

    fun spot() {
        val cfg = config ?: return
        scope.launch {
            _busy.value = true
            try {
                cloudCommand("app_spot") ?: throw IllegalStateException("Нет облачной сессии")
                log("↓ точечная уборка запущена")
                delay(1200)
                loadStatus(cfg)
            } catch (e: Exception) {
                _error.value = describe(e)
            } finally {
                _busy.value = false
            }
        }
    }

    fun rcStart() {
        val cfg = config ?: return
        scope.launch { runCatching { cloudCommand("app_rc_start") } }
    }

    fun rcMove(velocity: Double, omega: Double, durationMs: Int, seq: Int) {
        val cfg = config ?: return
        val params = JSONArray().put(
            JSONObject()
                .put("velocity", velocity)
                .put("omega", omega)
                .put("duration", durationMs)
                .put("seqnum", seq),
        )
        scope.launch { runCatching { cloudCommand("app_rc_move", params) } }
    }

    fun rcEnd() {
        val cfg = config ?: return
        scope.launch { runCatching { cloudCommand("app_rc_end") } }
    }

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
                log("✗ статус: ${describe(it)}")
            }
    }

    private suspend fun fetchStatus(cfg: RobotConfig): XiaomiStatus {
        val localJson = runCatching { client(cfg).command("get_status") }.getOrNull()
        if (localJson != null) {
            log("↓ статус: ${localJson.toString().take(300)}")
            val array = localJson.optJSONArray("result")
            if (array != null) {
                val parsed = runCatching { parseStatus(array) }.getOrNull()
                if (parsed != null) return parsed
            }
        }
        val cloud = cloudCommand("get_status")?.optJSONArray("result")
            ?: throw IllegalStateException(
                "Робот не отвечает ни по Wi-Fi, ни через облако",
            )
        return parseStatus(cloud)
    }

    private fun parseStatus(array: JSONArray): XiaomiStatus {
        // m1s (both locally and via cloud) returns [{"state":..., "battery":...}],
        // older models return a plain array of numbers.
        val obj = array.optJSONObject(0)
        if (obj != null) {
            return XiaomiStatus(
                state = obj.optInt("state"),
                errorCode = obj.optInt("error_code"),
                battery = obj.optInt("battery"),
                cleanTimeSec = obj.optInt("clean_time"),
                cleanAreaMm2 = obj.optLong("clean_area"),
            )
        }
        if (array.length() < 1) throw IllegalStateException("Пустой ответ get_status")
        return XiaomiStatus(
            state = array.optInt(0),
            errorCode = if (array.length() > 1) array.optInt(1) else 0,
            battery = if (array.length() > 2) array.optInt(2) else 0,
            cleanTimeSec = if (array.length() > 4) array.optInt(4) else 0,
            cleanAreaMm2 = if (array.length() > 5) array.optLong(5) else 0L,
        )
    }

    private fun runCommand(
        label: String,
        method: String,
        params: JSONArray = JSONArray(),
        stopFirst: Boolean = false,
        cloudParams: JSONArray? = null,
    ) {
        val cfg = config ?: return
        scope.launch {
            _busy.value = true
            try {
                if (stopFirst) {
                    log("↑ app_stop (перед комнатой)")
                    runCatching { client(cfg).command("app_stop") }
                    delay(900)
                }
                log("↑ $method $params")
                val local = runCatching { client(cfg).command(method, params) }
                if (local.isSuccess) {
                    log("↓ локально: ${local.getOrNull()?.toString()?.take(160)}")
                } else {
                    log("✗ локально: ${describe(local.exceptionOrNull()!!)}")
                    // Local miIO is unreliable on this robot, so fall back to
                    // the cloud for every command (with the cloud-specific
                    // params for room cleaning).
                    val cloud = cloudCommand(method, cloudParams ?: params)
                    if (cloud == null) {
                        throw (local.exceptionOrNull() ?: IllegalStateException("нет связи"))
                    }
                    log("↓ облако: $cloud")
                }
                _error.value = null
                delay(1200)
                loadStatus(cfg)
            } catch (e: Exception) {
                _error.value = "$label: ${describe(e)}"
                log("✗ $label: ${describe(e)}")
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun cloudCommand(
        method: String,
        params: JSONArray = JSONArray(),
    ): JSONObject? {
        val session = cloudSession ?: return null
        val did = cloudDid ?: return null
        return runCatching {
            XiaomiCloud().sendCommand(session, did, method, params)
        }.onFailure {
            log("✗ облако: ${describe(it)}")
        }.getOrNull()
    }

    private fun client(cfg: RobotConfig): MiioClient {
        val token = cfg.token
        if (token.isNullOrBlank()) throw IllegalStateException("У робота нет miIO-токена")
        return MiioClient(
            cfg.ip.trim(),
            token.trim(),
            network = LocalNetwork.wifi(appContext),
        )
    }

    private fun describe(error: Throwable): String {
        val text = generateSequence(error as Throwable?) { it.cause }
            .mapNotNull { it.message?.takeIf { message -> message.isNotBlank() } }
            .distinct()
            .joinToString(" → ")
        if (text.isBlank()) return error.javaClass.simpleName
        val hint = if (text.contains("EPERM", ignoreCase = true)) {
            " (локальная сеть заблокирована: VPN/сотовая/LNP)"
        } else if (text.contains("timed out", ignoreCase = true) ||
            text.contains("timeout", ignoreCase = true)
        ) {
            " (робот спит или недоступен по Wi-Fi)"
        } else {
            ""
        }
        return text + hint
    }

    private companion object {
        const val POLL_MS = 5000L
    }
}
