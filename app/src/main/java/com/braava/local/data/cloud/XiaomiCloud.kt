package com.braava.local.data.cloud

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Xiaomi cloud client.
 *
 * Two jobs:
 *  - log into a Mi Home account (with the e-mail 2FA flow) and read miIO tokens;
 *  - send commands through the cloud (`/home/rpc/<did>`), which also reaches a
 *    robot that is asleep on its dock.
 *
 * Control over the LAN uses the separate [com.braava.local.data.xiaomi.MiioClient].
 */
class XiaomiCloud(
    private val http: OkHttpClient = defaultXiaomiClient(),
) {

    data class XiaomiDevice(
        val did: String,
        val name: String,
        val model: String,
        val ip: String?,
        val token: String,
        val mac: String?,
    )

    data class Session(
        val userId: String,
        val serviceToken: String,
        val ssecurity: String,
    )

    sealed interface LoginResult {
        data class Devices(
            val devices: List<XiaomiDevice>,
            val session: Session,
        ) : LoginResult

        data class NeedCode(val challenge: Challenge) : LoginResult
    }

    /**
     * A login that stopped at the e-mail 2FA step. It owns the OkHttp client
     * and cookie jar, so the code must be submitted through [submit].
     */
    class Challenge internal constructor(
        private val cloud: XiaomiCloud,
        internal val client: OkHttpClient,
        internal val jar: MemoryCookieJar,
        internal val notificationUrl: String,
        internal val context: String,
    ) {
        suspend fun submit(code: String): LoginResult = cloud.completeChallenge(this, code)
    }

    open class CloudException(message: String) : Exception(message)

    suspend fun startLogin(username: String, password: String): LoginResult =
        withContext(Dispatchers.IO) {
            val jar = MemoryCookieJar()
            val client = http.newBuilder().cookieJar(jar).build()

            val sign = loginStep1(client, username)
            val step2 = loginStep2(client, username, password, sign)

            if (step2.optString("ssecurity").isNotBlank() &&
                step2.optString("location").isNotBlank()
            ) {
                val session = finishSession(client, jar, step2)
                return@withContext LoginResult.Devices(listDevices(session), session)
            }

            val notification = step2.optString("notificationUrl")
            if (notification.isBlank()) {
                throw CloudException("Не удалось войти в Mi-аккаунт: ${step2.optString("desc")}")
            }
            val context = Uri.parse(notification).getQueryParameter("context")
                ?: throw CloudException("Mi-аккаунт не вернул context для 2FA")
            sendEmailTicket(client, jar, notification, context)
            LoginResult.NeedCode(Challenge(this@XiaomiCloud, client, jar, notification, context))
        }

    suspend fun completeChallenge(
        challenge: Challenge,
        code: String,
    ): LoginResult = withContext(Dispatchers.IO) {
        val client = challenge.client
        val jar = challenge.jar
        // Every 2FA hop answers with a Location header; following redirects
        // would hide it, so this chain is walked manually (like the extractor).
        val noRedirect = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val verifyUrl = "https://account.xiaomi.com/identity/auth/verifyEmail"
            .toHttpUrl().newBuilder()
            .addQueryParameter("_flag", "8")
            .addQueryParameter("_json", "true")
            .addQueryParameter("sid", "xiaomiio")
            .addQueryParameter("context", challenge.context)
            .addQueryParameter("mask", "0")
            .addQueryParameter("_locale", "en_US")
            .build()
        val verifyBody = FormBody.Builder()
            .add("_flag", "8")
            .add("ticket", code.trim())
            .add("trust", "false")
            .add("_json", "true")
            .add("ick", jar.find("ick")?.value.orEmpty())
            .build()
        val verify = noRedirect.newCall(
            Request.Builder().url(verifyUrl).post(verifyBody).build(),
        ).execute().use { response ->
            if (response.code != 200) {
                throw CloudException("Проверка кода: HTTP ${response.code}")
            }
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            json?.optString("location")?.takeIf { it.isNotBlank() }
                ?: response.header("Location")
                ?: findCheckUrl(text)
        }

        var finish = verify
        if (finish.isNullOrBlank()) {
            val fallbackUrl = "https://account.xiaomi.com/identity/result/check"
                .toHttpUrl().newBuilder()
                .addQueryParameter("sid", "xiaomiio")
                .addQueryParameter("context", challenge.context)
                .addQueryParameter("_locale", "en_US")
                .build()
            val hop = noRedirect.newCall(
                Request.Builder().url(fallbackUrl).build(),
            ).execute().use { it.header("Location") }
            finish = hop
        }
        if (finish.isNullOrBlank()) {
            throw CloudException("Код не принят (2FA): нет адреса завершения")
        }
        val finishUrl = finish

        val endUrl = if (finishUrl.contains("identity/result/check")) {
            noRedirect.newCall(Request.Builder().url(finishUrl).build()).execute().use {
                it.header("Location")
            }
        } else {
            finishUrl
        } ?: throw CloudException("2FA: нет Auth2/end")

        var end = noRedirect.newCall(
            Request.Builder().url(endUrl).build(),
        ).execute()
        var endText = end.use { it.body?.string().orEmpty() }
        var pragma = end.header("extension-pragma")
        if (end.code == 200 && endText.contains("Xiaomi Account - Tips")) {
            end = noRedirect.newCall(Request.Builder().url(endUrl).build()).execute()
            endText = end.use { it.body?.string().orEmpty() }
            pragma = end.header("extension-pragma")
        }
        val ssecurity = runCatching {
            JSONObject(pragma.orEmpty()).optString("ssecurity")
        }.getOrNull().orEmpty()
        if (ssecurity.isBlank()) throw CloudException("2FA: нет ssecurity")

        var sts = end.header("Location")
        if (sts.isNullOrBlank()) {
            val index = endText.indexOf("https://sts.api.io.mi.com/sts")
            if (index >= 0) {
                val stop = endText.indexOf('"', index)
                sts = endText.substring(index, if (stop > index) stop else minOf(endText.length, index + 300))
            }
        }
        if (sts.isNullOrBlank()) throw CloudException("2FA: нет STS-редиректа")
        val stsUrl = sts
        client.newCall(Request.Builder().url(stsUrl).build()).execute().close()

        val serviceToken = jar.all("serviceToken")
            .firstOrNull { it.domain.contains("sts") }?.value
            ?: jar.all("serviceToken").firstOrNull()?.value
            ?: throw CloudException("2FA: нет serviceToken")
        // The jar may hold several "userId" cookies (the e-mail from step 1
        // and the numeric id set by the identity flow); the API needs the
        // numeric one, wherever it is stored.
        val userId = jar.all("userId")
            .map { it.value.trim() }
            .firstOrNull { value -> value.isNotEmpty() && value.all(Char::isDigit) }
            ?: throw CloudException(
                "2FA: нет числового userId (cookie userId: " +
                    jar.all("userId").joinToString { "${it.domain}${it.path}" } + ")",
            )
        val session = Session(userId, serviceToken, ssecurity)
        LoginResult.Devices(listDevices(session), session)
    }

    suspend fun fetchDevices(session: Session): List<XiaomiDevice> =
        withContext(Dispatchers.IO) { listDevices(session) }

    /** Full cleaning through the cloud, works while the robot is on the dock. */
    suspend fun start(session: Session, did: String): JSONObject =
        sendCommand(session, did, "app_start", JSONArray())

    /** Room cleaning through the cloud: params like [{"segments":[16],"repeat":1}]. */
    suspend fun cleanSegments(
        session: Session,
        did: String,
        segments: List<Int>,
        repeat: Int = 1,
    ): JSONObject = withContext(Dispatchers.IO) {
        // The CN cloud RPC takes the segment ids as a plain array: [16, 17].
        // Wrapping them into [{"segments":[...],"repeat":1}] makes the robot
        // answer "data for segment is not a number".
        val params = JSONArray().apply { segments.forEach { put(it) } }
        sendCommand(session, did, "app_segment_clean", params)
    }

    suspend fun sendCommand(
        session: Session,
        did: String,
        method: String,
        params: JSONArray,
    ): JSONObject = sendCommandValue(session, did, method, params)

    private fun sendCommandValue(
        session: Session,
        did: String,
        method: String,
        params: Any,
    ): JSONObject {
        val data = JSONObject()
            .put("id", 1)
            .put("method", method)
            .put("params", params)
            .toString()
        return signedPost(session, "https://api.io.mi.com/app/home/rpc/$did", data)
    }

    suspend fun cleanSummary(session: Session, did: String): JSONObject =
        sendCommand(session, did, "get_clean_summary", JSONArray())

    suspend fun cleanRecord(session: Session, did: String, record: Long): JSONObject =
        sendCommand(session, did, "get_clean_record", JSONArray().put(record))

    suspend fun consumables(session: Session, did: String): JSONObject =
        sendCommand(session, did, "get_consumable", JSONArray())

    suspend fun soundVolume(session: Session, did: String): Int? =
        sendCommand(session, did, "get_sound_volume", JSONArray())
            .optJSONArray("result")
            ?.optInt(0)

    suspend fun setSoundVolume(session: Session, did: String, volume: Int): JSONObject =
        sendCommand(session, did, "change_sound_volume", JSONArray().put(volume))

    suspend fun spot(session: Session, did: String): JSONObject =
        sendCommand(session, did, "app_spot", JSONArray())

    suspend fun rcStart(session: Session, did: String): JSONObject =
        sendCommand(session, did, "app_rc_start", JSONArray())

    suspend fun rcMove(
        session: Session,
        did: String,
        velocity: Double,
        omega: Double,
        durationMs: Int,
        seq: Int,
    ): JSONObject = sendCommand(
        session,
        did,
        "app_rc_move",
        JSONArray().put(
            JSONObject()
                .put("velocity", velocity)
                .put("omega", omega)
                .put("duration", durationMs)
                .put("seqnum", seq),
        ),
    )

    suspend fun rcEnd(session: Session, did: String): JSONObject =
        sendCommand(session, did, "app_rc_end", JSONArray())

    suspend fun findDid(session: Session, token: String): String? = withContext(Dispatchers.IO) {
        listDevices(session).firstOrNull { it.token == token.trim() }?.did
    }

    // ------------------------------------------------------------------

    private fun loginStep1(client: OkHttpClient, username: String): String {
        val url = "https://account.xiaomi.com/pass/serviceLogin?sid=xiaomiio&_json=true"
        val request = Request.Builder()
            .url(url)
            .header("Cookie", "userId=$username")
            .build()
        val text = client.newCall(request).execute().use { it.body?.string().orEmpty() }
        return jsonAfterPrefix(text).optString("_sign")
    }

    private fun loginStep2(
        client: OkHttpClient,
        username: String,
        password: String,
        sign: String,
    ): JSONObject {
        val form = FormBody.Builder()
            .add("sid", "xiaomiio")
            .add("hash", md5Hex(password).uppercase(java.util.Locale.US))
            .add("callback", "https://sts.api.io.mi.com/sts")
            .add("qs", "?sid=xiaomiio&_json=true")
            .add("user", username)
            .add("_json", "true")
            .apply { if (sign.isNotBlank()) add("_sign", sign) }
            .build()
        val request = Request.Builder()
            .url("https://account.xiaomi.com/pass/serviceLoginAuth2")
            .post(form)
            .build()
        val text = client.newCall(request).execute().use { it.body?.string().orEmpty() }
        return jsonAfterPrefix(text)
    }

    private fun finishSession(
        client: OkHttpClient,
        jar: MemoryCookieJar,
        step2: JSONObject,
    ): Session {
        val location = step2.optString("location")
        client.newCall(Request.Builder().url(location).build()).execute().close()
        val serviceToken = jar.find("serviceToken")?.value
            ?: throw CloudException("Mi-аккаунт не выдал serviceToken")
        return Session(
            userId = step2.optString("userId"),
            serviceToken = serviceToken,
            ssecurity = step2.optString("ssecurity"),
        )
    }

    private fun sendEmailTicket(
        client: OkHttpClient,
        jar: MemoryCookieJar,
        notificationUrl: String,
        context: String,
    ) {
        client.newCall(Request.Builder().url(notificationUrl).build()).execute().close()

        val listUrl = "https://account.xiaomi.com/identity/list".toHttpUrl().newBuilder()
            .addQueryParameter("sid", "xiaomiio")
            .addQueryParameter("context", context)
            .addQueryParameter("_locale", "en_US")
            .build()
        client.newCall(Request.Builder().url(listUrl).build()).execute().close()

        val sendUrl = "https://account.xiaomi.com/identity/auth/sendEmailTicket".toHttpUrl().newBuilder()
            .addQueryParameter("_dc", System.currentTimeMillis().toString())
            .addQueryParameter("sid", "xiaomiio")
            .addQueryParameter("context", context)
            .addQueryParameter("mask", "0")
            .addQueryParameter("_locale", "en_US")
            .build()
        val body = FormBody.Builder()
            .add("retry", "0")
            .add("icode", "")
            .add("_json", "true")
            .add("ick", jar.find("ick")?.value.orEmpty())
            .build()
        client.newCall(Request.Builder().url(sendUrl).post(body).build()).execute().close()
    }

    private fun listDevices(session: Session): List<XiaomiDevice> {
        val data = """{"getVirtualModel":true,"getHuamiDevices":1,"get_split_device":false,"support_smart_home":true}"""
        val json = signedPost(session, "https://api.io.mi.com/app/home/device_list", data)
        val list = json.optJSONObject("result")?.optJSONArray("list")
            ?: throw CloudException("В ответе облака нет списка устройств")
        val devices = mutableListOf<XiaomiDevice>()
        for (index in 0 until list.length()) {
            val obj = list.optJSONObject(index) ?: continue
            val token = obj.optString("token")
            if (token.isBlank()) continue
            devices += XiaomiDevice(
                did = obj.optString("did"),
                name = obj.optString("name").ifBlank { obj.optString("model") },
                model = obj.optString("model"),
                ip = obj.optString("localip").takeIf { it.isNotBlank() },
                token = token,
                mac = obj.optString("mac").takeIf { it.isNotBlank() },
            )
        }
        return devices
    }

    private fun signedPost(session: Session, url: String, data: String): JSONObject {
        val nonce = genNonce()
        val signedNonce = signedNonce(session.ssecurity, nonce)
        val params = linkedMapOf("data" to data)
        params["rc4_hash__"] = signature(url, "POST", signedNonce, params)
        for (key in params.keys.toList()) {
            params[key] = rc4Encrypt(signedNonce, params.getValue(key))
        }
        params["signature"] = signature(url, "POST", signedNonce, params)
        params["ssecurity"] = session.ssecurity
        params["_nonce"] = nonce

        val body = FormBody.Builder().apply {
            params.forEach { (key, value) -> add(key, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept-Encoding", "identity")
            .header("x-xiaomi-protocal-flag-cli", "PROTOCAL-HTTP2")
            .header("MIOT-ENCRYPT-ALGORITHM", "ENCRYPT-RC4")
            .header("Cookie", apiCookies(session))
            .build()
        val client = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        val text = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val hint = if (response.code == 401) {
                    " (userId=${session.userId}, токен ${session.serviceToken.length} симв.)"
                } else {
                    ""
                }
                throw CloudException("Облако Xiaomi вернуло ${response.code}$hint")
            }
            response.body?.string().orEmpty().trim()
        }
        if (text.startsWith("{")) throw CloudException("Облако Xiaomi: $text")
        val json = JSONObject(rc4Decrypt(signedNonce, text))
        val error = json.optJSONObject("error")
        if (error != null) {
            throw CloudException("Облако Xiaomi: ${error.optString("message")}")
        }
        if (json.has("code") && json.optInt("code") != 0) {
            throw CloudException("Облако Xiaomi: код ${json.optInt("code")}")
        }
        return json
    }

    private fun findCheckUrl(text: String): String? =
        Regex("""https://account\.xiaomi\.com/identity/result/check\?[^"']+""")
            .find(text)?.value

    private fun apiCookies(session: Session): String = buildString {
        append("userId=").append(session.userId)
        append("; yetAnotherServiceToken=").append(session.serviceToken)
        append("; serviceToken=").append(session.serviceToken)
        append("; locale=en")
        append("; timezone=GMT+03:00")
        append("; is_daylight=0")
        append("; dst_offset=0")
        append("; channel=MI_APP_STORE")
    }

    private fun genNonce(): String {
        val random = ByteArray(8).also { SecureRandom().nextBytes(it) }
        var minutes = System.currentTimeMillis() / 60000
        val tail = mutableListOf<Byte>()
        if (minutes == 0L) {
            tail.add(0)
        } else {
            while (minutes > 0) {
                tail.add(0, (minutes and 0xFF).toByte())
                minutes = minutes shr 8
            }
        }
        return Base64.encodeToString(random + tail.toByteArray(), Base64.NO_WRAP)
    }

    private fun signedNonce(ssecurity: String, nonce: String): String {
        val secret = Base64.decode(ssecurity, Base64.DEFAULT)
        val nonceBytes = Base64.decode(nonce, Base64.DEFAULT)
        return Base64.encodeToString(sha256(secret + nonceBytes), Base64.NO_WRAP)
    }

    private fun signature(
        url: String,
        method: String,
        signedNonce: String,
        params: Map<String, String>,
    ): String {
        val parts = mutableListOf<String>()
        parts.add(method.uppercase())
        parts.add(url.substringAfter(".com").replace("/app/", "/"))
        params.forEach { (key, value) -> parts.add("$key=$value") }
        parts.add(signedNonce)
        return Base64.encodeToString(sha1(parts.joinToString("&").toByteArray()), Base64.NO_WRAP)
    }

    private fun rc4Encrypt(signedNonce: String, value: String): String {
        val engine = Rc4(Base64.decode(signedNonce, Base64.DEFAULT))
        engine.process(ByteArray(1024))
        return Base64.encodeToString(engine.process(value.toByteArray()), Base64.NO_WRAP)
    }

    private fun rc4Decrypt(signedNonce: String, value: String): String {
        val engine = Rc4(Base64.decode(signedNonce, Base64.DEFAULT))
        engine.process(ByteArray(1024))
        return String(engine.process(Base64.decode(value, Base64.DEFAULT)), Charsets.UTF_8)
    }

    private fun jsonAfterPrefix(text: String): JSONObject =
        JSONObject(text.substringAfter("&&&START&&&"))

    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun sha1(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(data)

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    internal class MemoryCookieJar : CookieJar {
        private val items = mutableListOf<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            items.removeAll { old ->
                cookies.any {
                    it.name == old.name && it.domain == old.domain && it.path == old.path
                }
            }
            items.addAll(cookies)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            items.filter { it.matches(url) }

        fun find(name: String, domain: String? = null): Cookie? =
            items.firstOrNull { it.name == name && (domain == null || it.domain == domain) }

        fun all(name: String): List<Cookie> = items.filter { it.name == name }
    }

    private class Rc4(key: ByteArray) {
        private val state = IntArray(256)
        private var i = 0
        private var j = 0

        init {
            for (k in 0..255) state[k] = k
            var jj = 0
            for (k in 0..255) {
                jj = (jj + state[k] + (key[k % key.size].toInt() and 0xFF)) and 0xFF
                val tmp = state[k]
                state[k] = state[jj]
                state[jj] = tmp
            }
        }

        fun process(data: ByteArray): ByteArray {
            val out = ByteArray(data.size)
            for (k in data.indices) {
                i = (i + 1) and 0xFF
                j = (j + state[i]) and 0xFF
                val tmp = state[i]
                state[i] = state[j]
                state[j] = tmp
                val keyByte = state[(state[i] + state[j]) and 0xFF]
                out[k] = (data[k].toInt() xor keyByte).toByte()
            }
            return out
        }
    }
}

private fun defaultXiaomiClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()
