package com.braava.local.data

import android.content.Context
import com.braava.local.data.discovery.RobotDiscovery
import com.braava.local.data.model.CloudMap
import com.braava.local.data.model.MissionEntry
import com.braava.local.data.model.RobotState
import com.braava.local.data.model.RobotStateParser
import com.braava.local.data.mqtt.RoombaMqttClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.MqttException
import org.json.JSONObject

sealed interface ConnectionStatus {
    data object Disconnected : ConnectionStatus
    data object Connecting : ConnectionStatus
    data object Connected : ConnectionStatus
    data class Error(val message: String) : ConnectionStatus
}

/** Single source of truth for config, connection and robot state. */
class RobotRepository(context: Context) {

    private val appContext = context.applicationContext
    private val store = ConfigStore(appContext)
    private val cloudMapStore = CloudMapStore(appContext)
    private val missionHistoryStore = MissionHistoryStore(appContext)
    private val nameStore = NameStore(appContext)

    private val _robots = MutableStateFlow<List<RobotConfig>>(emptyList())
    val robots: StateFlow<List<RobotConfig>> = _robots.asStateFlow()

    private val _selected = MutableStateFlow<RobotConfig?>(null)
    val selected: StateFlow<RobotConfig?> = _selected.asStateFlow()

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _state = MutableStateFlow<RobotState?>(null)
    val state: StateFlow<RobotState?> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events.asStateFlow()

    private val _trail = MutableStateFlow<List<Pair<Int, Int>>>(emptyList())
    val trail: StateFlow<List<Pair<Int, Int>>> = _trail.asStateFlow()

    private val _sensorDeltas = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val sensorDeltas: StateFlow<List<Pair<String, Int>>> = _sensorDeltas.asStateFlow()
    private var sensorBaseline: Map<String, Int>? = null
    private var lastPose: Pair<Int, Int>? = null
    private var lastMission: Int? = null
    private var missionStart: Long? = null

    private var client: RoombaMqttClient? = null
    private var master = JSONObject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var retryJob: Job? = null
    private var attempt = 0
    private var connecting = false

    init {
        val saved = store.loadRobots()
        _robots.value = saved
        val selectedBlid = store.loadSelected()
        _selected.value = saved.firstOrNull { it.blid == selectedBlid } ?: saved.firstOrNull()
    }

    /** Add a robot (or update one with the same BLID), select it and connect. */
    fun addRobot(config: RobotConfig) {
        val list = _robots.value.toMutableList()
        val index = list.indexOfFirst { it.blid == config.blid }
        if (index >= 0) list[index] = config else list.add(config)
        _robots.value = list
        store.saveRobots(list)
        selectRobot(config.blid, force = true)
    }

    fun removeRobot(blid: String) {
        val list = _robots.value.filterNot { it.blid == blid }
        _robots.value = list
        store.saveRobots(list)
        if (_selected.value?.blid == blid) {
            disconnect()
            val next = list.firstOrNull()
            setSelected(next)
            _state.value = null
            if (next != null) connect()
        }
    }

    /** Update a saved robot (name/ip), reconnecting if it is the active one. */
    fun updateRobot(config: RobotConfig) {
        val list = _robots.value.toMutableList()
        val index = list.indexOfFirst { it.blid == config.blid }
        if (index < 0) return
        list[index] = config
        _robots.value = list
        store.saveRobots(list)
        if (_selected.value?.blid == config.blid) {
            disconnect()
            setSelected(config)
            _state.value = null
            connect()
        }
    }

    fun selectRobot(blid: String, force: Boolean = false) {
        val config = _robots.value.firstOrNull { it.blid == blid } ?: return
        if (!force && _selected.value?.blid == blid && (connecting || client != null)) return
        disconnect()
        setSelected(config)
        _state.value = null
        master = JSONObject()
        connect()
    }

    fun connect() {
        if (_selected.value?.kind == RobotKind.XIAOMI) return
        if (client != null || connecting) return
        if (_selected.value == null) return
        connecting = true
        attempt = 0
        master = JSONObject()
        _status.value = ConnectionStatus.Connecting
        startConnect()
    }

    private fun setSelected(config: RobotConfig?) {
        _selected.value = config
        store.saveSelected(config?.blid)
    }

    private fun startConnect() {
        val config = _selected.value ?: return
        val mqtt = RoombaMqttClient(config, mqttListener)
        client = mqtt
        mqtt.connect()
    }

    fun disconnect() {
        connecting = false
        retryJob?.cancel()
        retryJob = null
        client?.disconnect()
        client = null
        _status.value = ConnectionStatus.Disconnected
    }

    fun sendCommand(command: String, params: JSONObject = JSONObject()) {
        val payload = JSONObject()
            .put("command", command)
            .put("time", System.currentTimeMillis() / 1000)
            .put("initiator", "localApp")
        for (key in params.keys()) payload.put(key, params.get(key))
        publish("cmd", payload)
    }

    fun setPreferences(preferences: Map<String, Any>) {
        val state = JSONObject()
        preferences.forEach { (key, value) -> state.put(key, value) }
        publish("delta", JSONObject().put("state", state))
    }

    /** Fluid amount: 1 = eco, 2 = standard, 3 = ultra. */
    fun setPadWetness(level: Int) {
        val wetness = JSONObject().put("reusable", level).put("disposable", level)
        publish("delta", JSONObject().put("state", JSONObject().put("padWetness", wetness)))
    }

    /** Status-ring pattern: 0 = charge + dock, 1 = dock only, 2 = off. */
    fun setChargeLed(level: Int) {
        publish("delta", JSONObject().put("state", JSONObject().put("chrgLrPtrn", level)))
    }

    /** Mop-path overlap percentage: 25 = small, 67 = medium, 85 = large. */
    fun setRankOverlap(value: Int) {
        publish("delta", JSONObject().put("state", JSONObject().put("rankOverlap", value)))
    }

    suspend fun discover(timeoutMs: Long = 6000): List<RobotDiscovery.Found> =
        RobotDiscovery.discover(appContext, timeoutMs)

    /** TCP sweep of the local /24 on the robot's MQTT port (broadcast fallback). */
    suspend fun scanLocalNetwork(): List<String> = RobotDiscovery.scanSubnet(appContext)

    /** Map id -> the version currently stored on the robot (`reported.pmaps`). */
    fun localMapVersions(): Map<String, String> {
        val reported = master.optJSONObject("state")?.optJSONObject("reported")
            ?: return emptyMap()
        val pmaps = reported.optJSONArray("pmaps") ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (i in 0 until pmaps.length()) {
            val obj = pmaps.optJSONObject(i) ?: continue
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.optString(key)
                if (key.isNotBlank() && value.isNotBlank()) result[key] = value
            }
        }
        return result
    }

    /** Start a mission scoped to the given cloud room ids. */
    fun startRooms(regionIds: List<String>) {
        if (regionIds.isEmpty()) {
            sendCommand("start")
            return
        }
        val regions = org.json.JSONArray()
        regionIds.forEach { id ->
            regions.put(JSONObject().put("region_id", id).put("type", "rid"))
        }
        sendCommand("start", JSONObject().put("regions", regions))
    }

    /**
     * Start a mission pinned to a known map version, like the native app's
     * favourite: explicit [pmapId] + [versionId], optional rooms and the
     * current cleaning settings. This avoids the robot picking an unrelated
     * or uncommitted map version on its own.
     */
    fun startMap(
        pmapId: String,
        versionId: String,
        regionIds: List<String> = emptyList(),
        settings: JSONObject = JSONObject(),
    ) {
        if (pmapId.isBlank() || versionId.isBlank()) {
            startRooms(regionIds)
            return
        }
        val params = JSONObject()
            .put("pmap_id", pmapId)
            .put("user_pmapv_id", versionId)
        if (regionIds.isNotEmpty()) {
            val regions = org.json.JSONArray()
            regionIds.forEach { id ->
                regions.put(JSONObject().put("region_id", id).put("type", "rid"))
            }
            params.put("regions", regions)
        }
        for (key in settings.keys()) params.put(key, settings.get(key))
        sendCommand("start", params)
    }


    fun loadCloudMaps(blid: String): List<CloudMap> = cloudMapStore.load(blid)

    fun saveCloudMaps(blid: String, maps: List<CloudMap>) = cloudMapStore.save(blid, maps)

    /** Raw local map report (`reported.pmaps` + nav stats) for diagnostics. */
    fun localMapDiagnostics(): String {
        val reported = master.optJSONObject("state")?.optJSONObject("reported")
            ?: return "(телеметрия ещё не получена)"
        val pmaps = reported.optJSONArray("pmaps")
        val nav = reported.optJSONObject("mssnNavStats")
        return JSONObject()
            .apply {
                if (pmaps != null) put("pmaps", pmaps)
                if (nav != null) put("mssnNavStats", nav)
            }
            .toString(2)
    }

    fun loadMissionHistory(blid: String): List<MissionEntry> = missionHistoryStore.load(blid)

    fun saveMissionHistory(blid: String, entries: List<MissionEntry>) =
        missionHistoryStore.save(blid, entries)

    fun mapNames(): Map<String, String> = nameStore.mapNames()

    fun roomNames(): Map<String, String> = nameStore.roomNames()

    fun setMapName(pmapId: String, name: String) = nameStore.setMapName(pmapId, name)

    fun setRoomName(pmapId: String, regionId: String, name: String) =
        nameStore.setRoomName(pmapId, regionId, name)

    // ------------------------------------------------------------------

    private fun publish(topic: String, payload: JSONObject) {
        val mqtt = client
        if (mqtt == null || !mqtt.isConnected) {
            logEvent("✗ нет соединения, $topic не отправлен")
            return
        }
        val text = payload.toString()
        logEvent("↑ $topic: $text")
        mqtt.publish(topic, text) { error ->
            logEvent(
                if (error == null) {
                    "✓ $topic отправлено"
                } else {
                    "✗ $topic: ${describe(error)}"
                },
            )
        }
    }

    private fun logEvent(line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        _events.value = (_events.value + "$stamp  $line").takeLast(60)
    }

    private val mqttListener = object : RoombaMqttClient.Listener {
        override fun onConnected() {
            connecting = false
            attempt = 0
            _lastError.value = null
            _status.value = ConnectionStatus.Connected
            logEvent("✓ подключено")
        }

        override fun onConnectionLost(cause: Throwable?) {
            val message = cause?.let { describe(it) } ?: "Соединение потеряно"
            _lastError.value = message
            _status.value = ConnectionStatus.Error(message)
            logEvent("✗ связь потеряна: $message")
        }

        override fun onConnectFailed(error: Throwable) {
            val failed = client
            client = null
            scope.launch { runCatching { failed?.disconnect() } }
            val detail = describe(error)
            val message = connectErrorMessage(error)
            _lastError.value = message
            attempt++
            logEvent("✗ попытка $attempt: $detail")
            if (!connecting) return
            if (isAuthFailure(error)) {
                // Wrong local password: retrying forever cannot help. Ask the
                // user to refresh it from the cloud or from the robot itself.
                connecting = false
                _status.value = ConnectionStatus.Error(message)
                return
            }
            // The robot drops off Wi-Fi for short periods and accepts only one
            // local session, so a failed attempt is not final: keep retrying
            // until the user disconnects. Attempts are spaced by backoff and
            // every few of them re-run discovery in case DHCP moved the robot.
            _status.value = ConnectionStatus.Connecting
            scheduleRetry()
        }

        override fun onMessage(topic: String, payload: String) {
            handleMessage(topic, payload)
        }
    }

    private fun backoffMillis(attempt: Int): Long =
        (1500L * attempt).coerceIn(1500L, MAX_BACKOFF_MS)

    /** Waits, then either retries the saved IP or re-runs discovery. */
    private fun scheduleRetry() {
        retryJob?.cancel()
        val rediscover = attempt == REDISCOVER_ON_ATTEMPT ||
            (attempt > REDISCOVER_ON_ATTEMPT && attempt % REDISCOVER_EVERY == 0)
        retryJob = scope.launch {
            delay(backoffMillis(attempt))
            if (!connecting) return@launch
            if (rediscover) rediscoverAndReconnect() else startConnect()
        }
    }

    private suspend fun rediscoverAndReconnect() {
        if (!connecting) return
        _status.value = ConnectionStatus.Connecting
        logEvent("↻ ищу робота в сети (IP мог измениться)")
        val config = _selected.value
        if (config == null) {
            connecting = false
            _status.value = ConnectionStatus.Error("Робот не выбран")
            return
        }
        val found = runCatching { RobotDiscovery.discover(appContext) }.getOrDefault(emptyList())
        var match = found.firstOrNull { it.blid == config.blid }
        if (match == null) {
            // Broadcast may be filtered by the AP. Sweep the LAN for hosts with
            // the robot's MQTT port open and confirm each one via unicast
            // discovery, so a DHCP address change is recovered automatically.
            val candidates = runCatching { RobotDiscovery.scanSubnet(appContext) }
                .getOrDefault(emptyList())
                .filter { it != config.ip }
            for (candidate in candidates) {
                if (!connecting) return
                val probe = runCatching { RobotDiscovery.probe(appContext, candidate) }.getOrNull()
                if (probe != null && probe.blid == config.blid) {
                    match = probe
                    break
                }
            }
        }
        if (!connecting) return
        when {
            match != null && match.ip != config.ip -> {
                val updated = config.copy(ip = match.ip)
                val list = _robots.value.map { if (it.blid == updated.blid) updated else it }
                _robots.value = list
                store.saveRobots(list)
                setSelected(updated)
                logEvent("✓ новый IP: ${match.ip}")
                master = JSONObject()
                startConnect()
            }
            match != null -> startConnect()
            else -> {
                // Not found anywhere: the robot has most likely dropped off
                // Wi-Fi. Keep waiting for it instead of giving up.
                logEvent("… робот не найден в сети, жду")
                retryJob = scope.launch {
                    delay(backoffMillis(attempt))
                    if (connecting) startConnect()
                }
            }
        }
    }

    private fun updateSensorDeltas(state: RobotState, running: Boolean) {
        val health = state.health ?: return
        val current = linkedMapOf(
            "Бамперы" to health.bumperPresses,
            "Застревания" to health.stuck,
            "Паники" to health.panics,
            "Датчик края" to health.cliffFront,
        ).mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

        if (!running) return
        val baseline = sensorBaseline
        if (baseline == null) {
            sensorBaseline = current
            _sensorDeltas.value = emptyList()
            return
        }
        _sensorDeltas.value = current.mapNotNull { (key, value) ->
            val base = baseline[key] ?: return@mapNotNull null
            val delta = value - base
            if (delta > 0) key to delta else null
        }
    }

    private fun handleMessage(topic: String, payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val reported = json.optJSONObject("state")?.optJSONObject("reported")
        if (reported != null && reported.length() > 0) {
            logEvent("↓ $topic: ${reported.keys().asSequence().take(6).joinToString(",")}")
        } else {
            logEvent("↓ $topic")
        }
        RobotStateParser.merge(master, json)
        val parsed = RobotStateParser.parse(master)
        val previous = _state.value
        if (parsed.errorCode != null && parsed.errorCode != 0 &&
            parsed.errorCode != previous?.errorCode
        ) {
            logEvent("⚠ ошибка ${parsed.errorCode}: ${parsed.errorMessage ?: ""}")
        }
        if (parsed.readiness != null && parsed.readiness != previous?.readiness) {
            logEvent("⚠ не готов: ${parsed.readiness}")
        }
        val running = parsed.phase == "run" || parsed.phase == "resume"
        val wasRunning = previous?.phase == "run" || previous?.phase == "resume"
        if (parsed.missionNumber != lastMission) missionStart = null
        if (running && !wasRunning) missionStart = System.currentTimeMillis() / 1000
        updateSensorDeltas(parsed, running)
        val effective = if (running && parsed.missionStartEpoch == null && missionStart != null) {
            parsed.copy(missionStartEpoch = missionStart)
        } else {
            parsed
        }
        trackPose(effective)
        _state.value = effective
    }

    private fun trackPose(state: RobotState) {
        val x = state.poseX
        val y = state.poseY
        if (state.missionNumber != lastMission) {
            lastMission = state.missionNumber
            lastPose = null
            _trail.value = emptyList()
            sensorBaseline = null
            _sensorDeltas.value = emptyList()
        }
        if (x == null || y == null) return
        val point = x to y
        if (point == lastPose) return
        lastPose = point
        _trail.value = (_trail.value + point).takeLast(2000)
    }

    private fun connectErrorMessage(error: Throwable): String = when {
        isAuthFailure(error) ->
            "Неверный BLID или пароль. Обновите пароль из облака или с робота."
        isNoRoute(error) ->
            "Робот пропал из Wi-Fi (нет ответа в сети). Продолжаю попытки."
        isTimeout(error) ->
            "Робот не отвечает (таймаут). Продолжаю попытки."
        else -> describe(error)
    }

    private fun isAuthFailure(error: Throwable): Boolean =
        (error is MqttException && error.reasonCode in AUTH_REASON_CODES) ||
            error.message?.contains("not authorized", ignoreCase = true) == true ||
            error.message?.contains("bad user", ignoreCase = true) == true

    /** Android reports a failed ARP as EHOSTUNREACH / "No route to host". */
    private fun isNoRoute(error: Throwable): Boolean =
        generateSequence(error as Throwable?) { it.cause }.any {
            it is java.net.NoRouteToHostException ||
                it.message?.contains("EHOSTUNREACH", ignoreCase = true) == true ||
                it.message?.contains("No route to host", ignoreCase = true) == true
        }

    private fun isTimeout(error: Throwable): Boolean =
        generateSequence(error as Throwable?) { it.cause }.any {
            it is java.net.SocketTimeoutException ||
                it.message?.contains("timed out", ignoreCase = true) == true
        }

    /** Flatten an exception chain into one readable line. */
    private fun describe(error: Throwable): String {
        val messages = generateSequence(error as Throwable?) { it.cause }
            .mapNotNull { it.message?.takeIf { m -> m.isNotBlank() } }
            .distinct()
            .toList()
        return if (messages.isEmpty()) {
            error.javaClass.simpleName
        } else {
            messages.joinToString(" → ")
        }
    }

    private companion object {
        /** After this many failed attempts, run discovery again. */
        const val REDISCOVER_ON_ATTEMPT = 3

        /** Then re-run discovery every N attempts, in case DHCP moved the robot. */
        const val REDISCOVER_EVERY = 8

        const val MAX_BACKOFF_MS = 15_000L

        val AUTH_REASON_CODES = setOf(
            MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt(),
            MqttException.REASON_CODE_NOT_AUTHORIZED.toInt(),
        )
    }
}
