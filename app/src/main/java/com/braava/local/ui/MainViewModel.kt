package com.braava.local.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.braava.local.BraavaApp
import com.braava.local.data.ConnectionDiagnostics
import com.braava.local.data.RobotConfig
import com.braava.local.data.cloud.IrobotCloud
import com.braava.local.data.cloud.LocalPasswordFetcher
import com.braava.local.data.discovery.RobotDiscovery
import com.braava.local.data.model.CloudMap
import com.braava.local.data.model.CloudRegion
import com.braava.local.data.model.CloudRoom
import com.braava.local.data.model.MissionEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as BraavaApp).repository

    val robots = repository.robots
    val config = repository.selected
    val status = repository.status
    val state = repository.state
    val events = repository.events
    val trail = repository.trail
    val sensorDeltas = repository.sensorDeltas

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _cloudRobots = MutableStateFlow<List<IrobotCloud.CloudRobot>>(emptyList())
    val cloudRobots = _cloudRobots.asStateFlow()

    private val _discovered = MutableStateFlow<List<RobotDiscovery.Found>>(emptyList())
    val discovered = _discovered.asStateFlow()

    private val _localPassword = MutableStateFlow<String?>(null)
    val localPassword = _localPassword.asStateFlow()

    private val _diagnostics = MutableStateFlow<List<String>?>(null)
    val diagnostics = _diagnostics.asStateFlow()

    private val _diagnosticsExport = MutableStateFlow<String?>(null)
    val diagnosticsExport = _diagnosticsExport.asStateFlow()

    private val cloud = IrobotCloud()
    private var cloudSession: IrobotCloud.Session? = null

    /** Cached maps by pmapId, for resolving history map/room names. */
    private val mapLookup = mutableMapOf<String, CloudMap>()

    private val _cloudMaps = MutableStateFlow<List<CloudMap>>(emptyList())
    val cloudMaps = _cloudMaps.asStateFlow()

    private val _selectedCloudMap = MutableStateFlow<CloudMap?>(null)
    val selectedCloudMap = _selectedCloudMap.asStateFlow()

    private val _loggedIn = MutableStateFlow(false)
    val loggedIn = _loggedIn.asStateFlow()

    private val _cloudMapBusy = MutableStateFlow(false)
    val cloudMapBusy = _cloudMapBusy.asStateFlow()

    private val _cloudMapStatus = MutableStateFlow<String?>(null)
    val cloudMapStatus = _cloudMapStatus.asStateFlow()

    private val _history = MutableStateFlow<List<MissionEntry>>(emptyList())
    val history = _history.asStateFlow()

    private val _selectedMission = MutableStateFlow<MissionEntry?>(null)
    val selectedMission = _selectedMission.asStateFlow()

    private val _historyStatus = MutableStateFlow<String?>(null)
    val historyStatus = _historyStatus.asStateFlow()

    private val _mapNames = MutableStateFlow(repository.mapNames())
    val mapNames = _mapNames.asStateFlow()

    private val _roomNames = MutableStateFlow(repository.roomNames())
    val roomNames = _roomNames.asStateFlow()

    fun mapTitle(map: CloudMap): String = _mapNames.value[map.pmapId] ?: map.name

    fun roomTitle(pmapId: String, region: CloudRegion): String =
        _roomNames.value["$pmapId|${region.id}"] ?: region.name

    /** Map name for a history record (local rename first, then cached map). */
    fun historyMapName(pmapId: String?): String? {
        if (pmapId == null) return null
        _mapNames.value[pmapId]?.let { return it }
        return mapLookup[pmapId]?.name
    }

    /** Room names for a history record, honouring local renames. */
    fun historyRoomNames(pmapId: String?, regionIds: List<String>): List<String> {
        if (pmapId == null || regionIds.isEmpty()) return emptyList()
        val regions = mapLookup[pmapId]?.regions.orEmpty().associateBy { it.id }
        return regionIds.map { id ->
            _roomNames.value["$pmapId|$id"] ?: regions[id]?.name ?: "Комната $id"
        }
    }

    private fun refreshMapLookup() {
        val blid = currentBlid() ?: repository.selected.value?.blid ?: return
        mapLookup.clear()
        repository.loadCloudMaps(blid).forEach { mapLookup[it.pmapId] = it }
    }

    /**
     * Geometry shown for a map in the "Maps" window, built from the locally
     * stored mission history (union of every cached mission that used this
     * plan). The cloud's active-version coverage is used only as a fallback
     * when nothing is stored yet — it can be a single partial run.
     */
    fun mapGeometryForDisplay(
        map: CloudMap,
    ): Pair<List<Pair<Float, Float>>, List<List<Pair<Float, Float>>>> {
        val entries = _history.value.filter { it.pmapId == map.pmapId && it.hasGeometry }
        if (entries.isNotEmpty()) {
            return entries.flatMap { it.points } to entries.flatMap { it.polygons }
        }
        return map.points to map.polygons
    }

    fun renameMap(pmapId: String, name: String) {
        if (name.isBlank()) return
        repository.setMapName(pmapId, name.trim())
        _mapNames.value = repository.mapNames()
    }

    fun renameRoom(pmapId: String, regionId: String, name: String) {
        if (name.isBlank()) return
        repository.setRoomName(pmapId, regionId, name.trim())
        _roomNames.value = repository.roomNames()
    }

    init {
        // Auto-connect once the repository (and its listener) is fully built.
        if (repository.selected.value != null) repository.connect()
    }

    // ----- configuration -----

    fun connect() = repository.connect()

    fun disconnect() = repository.disconnect()

    fun forget() {
        repository.selected.value?.let { repository.removeRobot(it.blid) }
    }

    fun selectRobot(blid: String) = repository.selectRobot(blid)

    fun removeRobot(blid: String) = repository.removeRobot(blid)

    fun updateRobot(config: RobotConfig) = repository.updateRobot(config)

    fun saveManual(blid: String, password: String, ip: String, name: String?): Boolean {
        if (blid.isBlank() || password.isBlank() || ip.isBlank()) {
            _error.value = "Заполните BLID, пароль и IP-адрес"
            return false
        }
        repository.addRobot(
            RobotConfig(blid.trim(), password.trim(), ip.trim(), name?.trim()),
        )
        return true
    }

    fun chooseCloudRobot(robot: IrobotCloud.CloudRobot, ip: String): Boolean {
        if (ip.isBlank()) {
            _error.value = "Укажите IP-адрес робота"
            return false
        }
        repository.addRobot(
            RobotConfig(robot.blid, robot.password, ip.trim(), robot.name, robot.sku),
        )
        return true
    }

    // ----- cloud -----

    fun cloudLogin(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _error.value = "Введите логин и пароль iRobot"
            return
        }
        _busy.value = true
        viewModelScope.launch {
            try {
                val robots = IrobotCloud().fetchRobots(username.trim(), password)
                _cloudRobots.value = robots
                if (robots.isEmpty()) _error.value = "В аккаунте не найдено роботов"
            } catch (e: Exception) {
                _error.value = e.message ?: "Ошибка входа в аккаунт iRobot"
            } finally {
                _busy.value = false
            }
        }
    }

    fun fetchLocalPassword(ip: String) {
        if (ip.isBlank()) {
            _error.value = "Сначала укажите IP-адрес робота"
            return
        }
        _busy.value = true
        viewModelScope.launch {
            val result = LocalPasswordFetcher.fetch(ip.trim())
            if (result != null) {
                _localPassword.value = result
            } else {
                _error.value =
                    "Робот не отдал пароль. Поставьте на базу, зажмите HOME до звукового " +
                        "сигнала и повторите."
            }
            _busy.value = false
        }
    }

    // ----- discovery -----

    fun discover() {
        _busy.value = true
        viewModelScope.launch {
            try {
                _discovered.value = repository.discover()
                if (_discovered.value.isEmpty()) {
                    _error.value = "Робот не найден в локальной сети"
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Ошибка поиска"
            } finally {
                _busy.value = false
            }
        }
    }

    // ----- control -----

    fun command(command: String) = repository.sendCommand(command)

    fun setPreferences(preferences: Map<String, Any>) =
        repository.setPreferences(preferences)

    fun setPadWetness(level: Int) = repository.setPadWetness(level)

    fun setChargeLed(level: Int) = repository.setChargeLed(level)

    fun setRankOverlap(value: Int) = repository.setRankOverlap(value)

    fun clearError() {
        _error.value = null
    }

    fun clearLocalPassword() {
        _localPassword.value = null
    }

    fun runDiagnostics() {
        val ip = repository.selected.value?.ip ?: return
        _busy.value = true
        _diagnostics.value = listOf("Проверка…")
        viewModelScope.launch {
            val lastError = repository.lastError.value
            // Free the robot's single local connection so the test is clean.
            repository.disconnect()
            kotlinx.coroutines.delay(700)
            val result = ConnectionDiagnostics.run(ip)
            _diagnostics.value = buildList {
                lastError?.let { add("Ошибка MQTT: $it") }
                addAll(result)
            }
            _busy.value = false
            repository.connect()
        }
    }

    fun clearDiagnostics() {
        _diagnostics.value = null
    }

    fun clearDiagnosticsExport() {
        _diagnosticsExport.value = null
    }

    /**
     * Build one text report for troubleshooting: the robot's raw local map
     * report (`reported.pmaps`) plus the raw cloud `pmaps`/`missionhistory`
     * (when logged in). Used to tell whether a `train` run changes the map.
     */
    fun exportDiagnostics() {
        viewModelScope.launch {
            val config = repository.selected.value
            val blid = currentBlid()
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            val sb = StringBuilder()
            sb.appendLine("BraavaLocal diagnostics")
            sb.appendLine("time=$stamp")
            sb.appendLine("robot=${config?.name} blid=$blid")
            sb.appendLine()
            sb.appendLine("=== LOCAL reported: pmaps + mssnNavStats ===")
            sb.appendLine(repository.localMapDiagnostics())
            sb.appendLine()
            val session = cloudSession
            if (session == null || blid == null) {
                sb.appendLine("=== CLOUD ===")
                sb.appendLine("(нет входа в облако — откройте «Карты» и нажмите «Обновить из облака»)")
            } else {
                val pmapsRaw = runCatching { cloud.getPmaps(session, blid) }
                    .getOrElse { "ошибка: ${it.message}" }
                sb.appendLine("=== CLOUD pmaps ===")
                sb.appendLine(pmapsRaw)
                sb.appendLine()
                appendUmfComparison(sb, session, blid, pmapsRaw)
                sb.appendLine()
                sb.appendLine("=== CLOUD missionhistory ===")
                sb.appendLine(
                    runCatching { cloud.getMissionHistory(session, blid) }
                        .getOrElse { "ошибка: ${it.message}" },
                )
            }
            _diagnosticsExport.value = sb.toString()
        }
    }

    /**
     * For every plan whose latest robot version differs from the active one
     * (e.g. right after `train`), fetch both UMFs and report their geometry so
     * we can tell whether the trained version actually contains a map.
     */
    private suspend fun appendUmfComparison(
        sb: StringBuilder,
        session: IrobotCloud.Session,
        blid: String,
        pmapsRaw: String,
    ) {
        val array = runCatching { JSONArray(pmapsRaw) }.getOrNull() ?: return
        sb.appendLine("=== CLOUD UMF: active vs robot version ===")
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val pmapId = obj.optString("pmap_id")
            val active = obj.optString("active_pmapv_id")
            val robot = obj.optString("robot_pmapv_id")
            if (pmapId.isBlank() || robot.isBlank() || robot == active) continue
            sb.appendLine("pmap=$pmapId active=$active robot=$robot")
            listOf("active" to active, "robot" to robot).forEach { (label, version) ->
                if (version.isBlank()) return@forEach
                val line = runCatching {
                    val umf = cloud.getUmf(session, blid, pmapId, version)
                    val geo = parseUmf(umf)
                    "$label $version -> точек=${geo.points.size} " +
                        "полигонов=${geo.polygons.size} комнат=${geo.rooms.size} " +
                        "символов=${umf.toString().length}"
                }.getOrElse { "$label $version -> ошибка: ${it.message}" }
                sb.appendLine("  $line")
            }
        }
    }

    // ----- cloud map -----

    private fun currentBlid(): String? {
        val selected = repository.selected.value?.blid
        val session = cloudSession ?: return selected
        val robots = session.robots
        return if (selected != null && robots.any { it.blid == selected }) {
            selected
        } else {
            robots.firstOrNull()?.blid ?: selected
        }
    }

    /** Populate the map list from the on-device cache (no login needed). */
    fun openCloudMaps() {
        val blid = currentBlid() ?: repository.selected.value?.blid
        val cached = blid?.let { repository.loadCloudMaps(it) } ?: emptyList()
        _cloudMaps.value = cached
        // Load history too, so the map view can show the union of coverage.
        _history.value = blid?.let { repository.loadMissionHistory(it) } ?: emptyList()
        refreshMapLookup()
        _selectedCloudMap.value = null
        _cloudMapStatus.value = when {
            cached.isEmpty() -> null
            else -> "Из кэша: ${cached.size} карт(ы). Можно смотреть без входа."
        }
    }

    fun resetCloudMap() {
        _cloudMaps.value = emptyList()
        _selectedCloudMap.value = null
        _cloudMapStatus.value = null
    }

    fun clearSelectedCloudMap() {
        _selectedCloudMap.value = null
    }

    /** Log in to iRobot and refresh the map list, keeping cached geometry. */
    fun refreshCloudMaps(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _cloudMapStatus.value = "Введите логин и пароль iRobot"
            return
        }
        _cloudMapBusy.value = true
        _cloudMapStatus.value = "Вход в аккаунт iRobot…"
        viewModelScope.launch {
            try {
                val session = cloud.login(username.trim(), password)
                cloudSession = session
                _loggedIn.value = true
                val blid = currentBlid()
                    ?: throw IrobotCloud.CloudException("Нет сохранённого робота")
                val raw = cloud.getPmaps(session, blid)
                val previous = repository.loadCloudMaps(blid)
                val cached = previous.associateBy { it.versionId }
                val merged = parseCloudMaps(raw).map { map ->
                    val old = cached[map.versionId]
                    if (old != null && old.hasGeometry) {
                        map.copy(points = old.points, polygons = old.polygons)
                    } else {
                        map
                    }
                }
                _cloudMaps.value = merged
                repository.saveCloudMaps(blid, merged)
                if (merged.isEmpty()) {
                    _cloudMapStatus.value = "Облако не вернуло карт. Ответ: ${raw.take(300)}"
                } else if (previous.isEmpty()) {
                    _cloudMapStatus.value = "Облако: ${merged.size} карт(ы). Выберите карту."
                } else {
                    val known = cached.keys
                    val fresh = merged.filter { it.versionId !in known }
                    _cloudMapStatus.value = if (fresh.isEmpty()) {
                        "Облако: ${merged.size} карт(ы). Новых версий нет."
                    } else {
                        val list = fresh.joinToString("; ") { "${it.name} (${it.created.ifBlank { "без даты" }})" }
                        "Появилась новая версия карты: $list"
                    }
                }
            } catch (e: Exception) {
                _cloudMapStatus.value = e.message ?: "Ошибка загрузки карт"
            } finally {
                _cloudMapBusy.value = false
            }
        }
    }

    /** Show a map's cached geometry, or fetch it from the cloud if missing. */
    fun selectCloudMap(map: CloudMap) {
        // "Complete" means the UMF was parsed with the current code (walls,
        // rooms, dock). Older caches only had coverage points, so re-fetch.
        val complete = map.rawUmf.isNotBlank()
        if (complete) {
            _selectedCloudMap.value = map
            _cloudMapStatus.value = geometryStatus(map)
            return
        }
        if (map.hasGeometry) {
            // Show what we have while/till the fresh data arrives.
            _selectedCloudMap.value = map
            _cloudMapStatus.value = "Показано из кэша. Подтягиваю стены, комнаты и базу…"
        }
        val session = cloudSession
        if (session == null) {
            if (map.hasGeometry) return
            _selectedCloudMap.value = null
            _cloudMapStatus.value =
                "Геометрия не сохранена. Войдите и нажмите «Обновить», чтобы скачать."
            return
        }
        val blid = currentBlid() ?: return
        _cloudMapBusy.value = true
        _cloudMapStatus.value = "Загрузка карты…"
        viewModelScope.launch {
            try {
                val umf = cloud.getUmf(session, blid, map.pmapId, map.versionId)
                val geo = parseUmf(umf)
                val updated = map.copy(
                    points = geo.points,
                    polygons = geo.polygons,
                    rooms = geo.rooms,
                    walls = geo.walls,
                    clutter = geo.clutter,
                    dock = geo.dock,
                    startPose = geo.startPose,
                    rawUmf = umf.toString(),
                )
                _selectedCloudMap.value = updated
                _cloudMapStatus.value = geometryStatus(updated)
                val list = _cloudMaps.value.map {
                    if (it.versionId == map.versionId) updated else it
                }
                _cloudMaps.value = list
                repository.saveCloudMaps(blid, list)
            } catch (e: Exception) {
                _cloudMapStatus.value = e.message ?: "Ошибка загрузки карты"
            } finally {
                _cloudMapBusy.value = false
            }
        }
    }

    /** Re-fetch UMF for the selected map (forces a cloud call, stores raw). */
    fun reloadSelectedMap() {
        val map = _selectedCloudMap.value ?: return
        val session = cloudSession
        if (session == null) {
            _cloudMapStatus.value = "Нужен вход: нажмите «Обновить из облака» в списке карт"
            return
        }
        val blid = currentBlid() ?: return
        _cloudMapBusy.value = true
        _cloudMapStatus.value = "Обновление карты…"
        viewModelScope.launch {
            try {
                val umf = cloud.getUmf(session, blid, map.pmapId, map.versionId)
                val geo = parseUmf(umf)
                val updated = map.copy(
                    points = geo.points,
                    polygons = geo.polygons,
                    rooms = geo.rooms,
                    walls = geo.walls,
                    clutter = geo.clutter,
                    dock = geo.dock,
                    startPose = geo.startPose,
                    rawUmf = umf.toString(),
                )
                _selectedCloudMap.value = updated
                val list = _cloudMaps.value.map {
                    if (it.versionId == map.versionId) updated else it
                }
                _cloudMaps.value = list
                repository.saveCloudMaps(blid, list)
                _cloudMapStatus.value = "Обновлено. UMF: ${umf.toString().length} символов"
            } catch (e: Exception) {
                _cloudMapStatus.value = e.message ?: "Ошибка обновления карты"
            } finally {
                _cloudMapBusy.value = false
            }
        }
    }

    fun cleanRooms(regionIds: List<String>) {        if (regionIds.isEmpty()) {
            _cloudMapStatus.value = "Выберите хотя бы одну комнату"
            return
        }
        repository.startRooms(regionIds)
        _cloudMapStatus.value = "Отправлена уборка по комнатам: ${regionIds.size}"
    }

    // ----- mission history -----

    fun openHistory() {
        val blid = currentBlid() ?: repository.selected.value?.blid
        val cached = blid?.let { repository.loadMissionHistory(it) } ?: emptyList()
        _history.value = cached
        // Load the plan too, so mission maps can show walls/rooms/dock.
        if (_cloudMaps.value.isEmpty()) {
            _cloudMaps.value = blid?.let { repository.loadCloudMaps(it) } ?: emptyList()
        }
        refreshMapLookup()
        _selectedMission.value = null
        _historyStatus.value = if (cached.isEmpty()) null else "Из кэша: ${cached.size} записей."
    }

    fun resetHistory() {
        _history.value = emptyList()
        _selectedMission.value = null
        _historyStatus.value = null
    }

    fun clearSelectedMission() {
        _selectedMission.value = null
    }

    fun refreshHistory(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _historyStatus.value = "Введите логин и пароль iRobot"
            return
        }
        _cloudMapBusy.value = true
        _historyStatus.value = "Вход и загрузка истории…"
        viewModelScope.launch {
            try {
                val session = cloud.login(username.trim(), password)
                cloudSession = session
                _loggedIn.value = true
                val blid = currentBlid()
                    ?: throw IrobotCloud.CloudException("Нет сохранённого робота")
                val raw = cloud.getMissionHistory(session, blid)
                val cached = repository.loadMissionHistory(blid).associateBy { it.nMssn }
                val merged = parseHistory(raw).map { entry ->
                    val old = cached[entry.nMssn]
                    if (old != null && old.hasGeometry) {
                        entry.copy(points = old.points, polygons = old.polygons)
                    } else {
                        entry
                    }
                }
                _history.value = merged
                repository.saveMissionHistory(blid, merged)
                _historyStatus.value = if (merged.isEmpty()) {
                    "История пуста или формат не распознан"
                } else {
                    "Записей: ${merged.size}. Выберите уборку."
                }
            } catch (e: Exception) {
                _historyStatus.value = e.message ?: "Ошибка загрузки истории"
            } finally {
                _cloudMapBusy.value = false
            }
        }
    }

    fun selectMission(entry: MissionEntry) {
        // Consider it complete only when both coverage points and the cleaned
        // outline are present; older caches stored points only, so re-fetch.
        if (entry.points.isNotEmpty() && entry.polygons.isNotEmpty()) {
            _selectedMission.value = entry
            _historyStatus.value = missionStatus(entry)
            return
        }
        if (entry.hasGeometry) {
            _selectedMission.value = entry
            _historyStatus.value = "Показано из кэша. Догружаю контур уборки…"
        }
        val session = cloudSession
        val pmapId = entry.pmapId
        val pmapvId = entry.pmapvId
        if (session == null || pmapId == null || pmapvId == null) {
            _selectedMission.value = null
            _historyStatus.value = if (pmapId == null) {
                "У этой записи нет привязки к карте"
            } else {
                "Геометрия не сохранена. Войдите и нажмите «Обновить»."
            }
            return
        }
        val blid = currentBlid() ?: return
        _cloudMapBusy.value = true
        _historyStatus.value = "Загрузка карты уборки…"
        viewModelScope.launch {
            try {
                val umf = cloud.getUmf(session, blid, pmapId, pmapvId)
                val geo = parseUmf(umf)
                val updated = entry.copy(points = geo.points, polygons = geo.polygons)
                _selectedMission.value = updated
                _historyStatus.value = missionStatus(updated)
                val list = _history.value.map {
                    if (it.nMssn == entry.nMssn) updated else it
                }
                _history.value = list
                repository.saveMissionHistory(blid, list)
            } catch (e: Exception) {
                _historyStatus.value = e.message ?: "Ошибка загрузки карты уборки"
            } finally {
                _cloudMapBusy.value = false
            }
        }
    }

    private fun missionStatus(entry: MissionEntry): String {
        if (!entry.hasGeometry) return "Нет геометрии"
        val all = entry.points + entry.polygons.flatten()
        val width = (all.maxOf { it.first } - all.minOf { it.first }) / 1000f
        val height = (all.maxOf { it.second } - all.minOf { it.second }) / 1000f
        return "Точек: ${entry.points.size}  •  ≈ %.1f×%.1f м".format(width, height)
    }

    private fun parseHistory(raw: String): List<MissionEntry> {
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: runCatching {
                val obj = org.json.JSONObject(raw)
                obj.optJSONArray("missions") ?: JSONArray().put(obj)
            }.getOrNull()
            ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val record = array.optJSONObject(index) ?: return@mapNotNull null
            val nMssn = record.optInt("nMssn", -1)
            if (nMssn < 0) return@mapNotNull null
            val start = record.optLong("startTime").takeIf { it > 0 }
                ?: record.optLong("timestamp").takeIf { it > 0 }
            val durationMin = record.optInt("duration").takeIf { it > 0 }?.let { it / 60 }
                ?: record.optInt("runM").takeIf { it > 0 }
            val (result, ok) = classifyMission(record)
            val first = record.optJSONArray("pmaps_info")?.optJSONObject(0)
            MissionEntry(
                nMssn = nMssn,
                startTime = start,
                durationMin = durationMin,
                sqft = record.optInt("sqft").takeIf { it > 0 },
                result = result,
                ok = ok,
                initiator = record.optString("initiator").takeIf { it.isNotBlank() },
                pmapId = first?.optString("pmap_id")?.takeIf { it.isNotBlank() },
                pmapvId = first?.optString("pmapv_id")?.takeIf { it.isNotBlank() },
                regionIds = parseRegionIds(record),
            )
        }.sortedByDescending { it.startTime ?: 0L }
    }

    private fun parseRegionIds(record: org.json.JSONObject): List<String> {
        val out = LinkedHashSet<String>()
        fun add(item: Any?) {
            when (item) {
                is Number -> out.add(item.toString())
                is String -> if (item.isNotBlank()) out.add(item)
                is org.json.JSONObject -> {
                    val id = item.optString("region_id").ifBlank { item.optString("rid") }
                    if (id.isNotBlank()) out.add(id)
                }
            }
        }
        for (key in listOf("regions", "region_ids")) {
            val array = record.optJSONArray(key) ?: continue
            for (i in 0 until array.length()) add(array.opt(i))
        }
        val single = record.optString("region_id").ifBlank { record.optString("rid") }
        if (single.isNotBlank()) out.add(single)
        val pmaps = record.optJSONArray("pmaps_info")
        if (pmaps != null) {
            for (i in 0 until pmaps.length()) {
                val regions = pmaps.optJSONObject(i)?.optJSONArray("regions") ?: continue
                for (j in 0 until regions.length()) add(regions.opt(j))
            }
        }
        return out.toList()
    }

    private fun classifyMission(record: org.json.JSONObject): Pair<String, Boolean> {
        val done = record.optString("done")
        val raw = record.optString("done_raw")
        return when {
            done == "done" || done == "ok" -> "Завершено" to true
            raw == "usrEnd" || done == "cncl" -> "Отменено" to false
            done == "full" -> "Контейнер полон" to false
            done == "bat" -> "Разрядился" to false
            done == "stuck" -> "Застрял" to false
            done == "schErr" -> "Ошибка расписания" to false
            done == "inc" -> "Не завершено" to false
            done.isBlank() -> "—" to false
            else -> done to false
        }
    }

    private fun geometryStatus(map: CloudMap): String {
        if (!map.hasGeometry) return "В карте нет геометрии"
        val all = map.points + map.polygons.flatten()
        val width = (all.maxOf { it.first } - all.minOf { it.first }) / 1000f
        val height = (all.maxOf { it.second } - all.minOf { it.second }) / 1000f
        return "Точек: ${map.points.size}  •  комнат: ${map.regions.size}  •  ≈ %.1f×%.1f м"
            .format(width, height)
    }

    private fun parseCloudMaps(raw: String): List<CloudMap> {
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: runCatching {
                val obj = org.json.JSONObject(raw)
                obj.optJSONArray("pmaps") ?: JSONArray().put(obj)
            }.getOrNull()
            ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val pmapId = obj.optString("pmap_id").ifBlank { obj.optString("id") }
            val versionId = obj.optString("active_pmapv_id")
                .ifBlank { obj.optString("pmapv_id") }
            if (pmapId.isBlank() || versionId.isBlank()) return@mapNotNull null
            val details = obj.optJSONObject("active_pmapv_details")
            val name = obj.optString("name").ifBlank {
                details?.optString("name").orEmpty()
            }.ifBlank { "Карта ${index + 1}" }
            val created = details?.optString("create_time").orEmpty()
                .ifBlank { obj.optString("create_time") }
            CloudMap(
                pmapId = pmapId,
                versionId = versionId,
                name = name,
                created = formatMapTime(created),
                regions = parseRegions(details),
            )
        }
    }

    private fun parseRegions(details: org.json.JSONObject?): List<CloudRegion> {
        val array = details?.optJSONArray("regions") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val region = array.optJSONObject(i) ?: return@mapNotNull null
            val id = region.optString("region_id").ifBlank { region.optString("id") }
            if (id.isBlank()) return@mapNotNull null
            val name = region.optString("name").ifBlank { "Комната $id" }
            CloudRegion(id, name)
        }
    }

    private fun formatMapTime(raw: String): String {
        if (raw.isBlank()) return ""
        raw.toLongOrNull()?.let { value ->
            val millis = if (value < 100_000_000_000L) value * 1000 else value
            return runCatching {
                java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.US)
                    .format(java.util.Date(millis))
            }.getOrElse { raw }
        }
        return runCatching {
            val parser = java.text.SimpleDateFormat("yyMMdd'T'HHmmss", java.util.Locale.US)
            val out = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.US)
            out.format(parser.parse(raw)!!)
        }.getOrElse { raw }
    }

    /** Full geometry parsed from a UMF response (all coordinates in millimetres). */
    private data class UmfGeometry(
        val points: List<Pair<Float, Float>>,
        val polygons: List<List<Pair<Float, Float>>>,
        val rooms: List<CloudRoom>,
        val walls: List<List<Pair<Float, Float>>>,
        val clutter: List<List<Pair<Float, Float>>>,
        val dock: Pair<Float, Float>?,
        val startPose: Pair<Float, Float>?,
    )

    private fun parseUmf(umf: org.json.JSONObject): UmfGeometry {
        var points: List<Pair<Float, Float>> = emptyList()
        var polygons: List<List<Pair<Float, Float>>> = emptyList()
        val rooms = ArrayList<CloudRoom>()
        val walls = ArrayList<List<Pair<Float, Float>>>()
        var clutter: List<List<Pair<Float, Float>>> = emptyList()
        var dock: Pair<Float, Float>? = null
        var start: Pair<Float, Float>? = null

        val maps = umf.optJSONArray("maps")
        if (maps != null) for (mi in 0 until maps.length()) {
            val map = maps.optJSONObject(mi) ?: continue

            val point2d = HashMap<String, Pair<Float, Float>>()
            map.optJSONArray("points2d")?.let { array ->
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    val coord = obj.optJSONArray("coordinates") ?: continue
                    if (id.isNotBlank() && coord.length() >= 2) {
                        point2d[id] = (coord.optDouble(0) * 1000.0).toFloat() to
                            (coord.optDouble(1) * 1000.0).toFloat()
                    }
                }
            }

            val poses = HashMap<String, Pair<Float, Float>>()
            map.optJSONArray("poses2d")?.let { array ->
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    val coord = obj.optJSONArray("coordinates") ?: continue
                    if (id.isNotBlank() && coord.length() >= 2) {
                        poses[id] = (coord.optDouble(0) * 1000.0).toFloat() to
                            (coord.optDouble(1) * 1000.0).toFloat()
                    }
                }
            }

            val suggested = HashMap<String, String>()
            map.optJSONArray("region_suggestions")?.let { array ->
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val rid = obj.optString("region_id")
                    val top = obj.optJSONArray("suggested_types")
                        ?.optJSONObject(0)?.optString("region_type")
                    if (rid.isNotBlank() && !top.isNullOrBlank()) suggested[rid] = top
                }
            }

            map.optJSONArray("regions")?.let { array ->
                for (i in 0 until array.length()) {
                    val region = array.optJSONObject(i) ?: continue
                    val rid = region.optString("id")
                    val ring = resolveRing(region.optJSONObject("geometry")?.optJSONArray("ids"), point2d)
                    if (rid.isNotBlank() && ring.size >= 3) {
                        rooms.add(CloudRoom(rid, ring, suggested[rid]))
                    }
                }
            }

            map.optJSONArray("borders")?.let { array ->
                for (i in 0 until array.length()) {
                    val border = array.optJSONObject(i) ?: continue
                    val ring = resolveRing(border.optJSONObject("geometry")?.optJSONArray("ids"), point2d)
                    if (ring.size >= 3) walls.add(ring)
                }
            }

            val typed = map.optJSONObject("typed_poses")
            dock = posFromIds(typed?.optJSONObject("dock_poses"), poses) ?: dock
            start = posFromIds(typed?.optJSONObject("start_pose"), poses) ?: start

            map.optJSONArray("layers")?.let { layers ->
                for (i in 0 until layers.length()) {
                    val layer = layers.optJSONObject(i) ?: continue
                    val coordinates = layer.optJSONObject("geometry")?.optJSONArray("coordinates")
                        ?: continue
                    when (layer.optString("layer_type")) {
                        "coverage" -> points = parsePointArray(coordinates)
                        "coverage_poly" -> polygons = parseRings(coordinates)
                        "clutter" -> clutter = parseRings(coordinates)
                    }
                }
            }
        }
        return UmfGeometry(points, polygons, rooms, walls, clutter, dock, start)
    }

    /** Resolve a ring given as lists of point ids against the points2d table. */
    private fun resolveRing(
        rings: JSONArray?,
        point2d: Map<String, Pair<Float, Float>>,
    ): List<Pair<Float, Float>> {
        if (rings == null) return emptyList()
        var best: List<Pair<Float, Float>> = emptyList()
        for (i in 0 until rings.length()) {
            val ring = rings.optJSONArray(i) ?: continue
            val pts = ArrayList<Pair<Float, Float>>(ring.length())
            for (j in 0 until ring.length()) {
                point2d[ring.optString(j)]?.let { pts.add(it) }
            }
            if (pts.size > best.size) best = pts
        }
        return best
    }

    private fun posFromIds(
        obj: org.json.JSONObject?,
        poses: Map<String, Pair<Float, Float>>,
    ): Pair<Float, Float>? {
        val ids = obj?.optJSONObject("geometry")?.optJSONArray("ids") ?: return null
        if (ids.length() == 0) return null
        return poses[ids.optString(0)]
    }

    private fun parsePointArray(coords: JSONArray): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>(coords.length())
        for (j in 0 until coords.length()) {
            val point = coords.optJSONArray(j) ?: continue
            if (point.length() < 2) continue
            val x = point.optDouble(0, Double.NaN)
            val y = point.optDouble(1, Double.NaN)
            if (x.isNaN() || y.isNaN()) continue
            out.add((x * 1000.0).toFloat() to (y * 1000.0).toFloat())
        }
        return out
    }

    /** `coordinates` may be a ring, a list of rings, or a multipolygon. */
    private fun parseRings(coords: JSONArray): List<List<Pair<Float, Float>>> {
        val out = ArrayList<List<Pair<Float, Float>>>()
        for (i in 0 until coords.length()) {
            val inner = coords.optJSONArray(i) ?: continue
            val first = inner.optJSONArray(0)
            if (first != null && first.length() >= 2 && first.opt(0) is Number) {
                val ring = parsePointArray(inner)
                if (ring.size >= 3) out.add(ring)
            } else {
                for (k in 0 until inner.length()) {
                    val ring = parsePointArray(inner.optJSONArray(k) ?: continue)
                    if (ring.size >= 3) out.add(ring)
                }
            }
        }
        return out
    }
}
