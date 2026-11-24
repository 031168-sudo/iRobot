package com.braava.local.data.cloud

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Xiaomi cloud client: logs into a Mi Home account and returns the local miIO
 * tokens of its devices. Everything else (control) happens locally over UDP.
 *
 * The CN server (api.io.mi.com) is used because the robot is bound to a
 * Chinese account. The login and the signed/RC4-encrypted device list follow
 * the same flow as the `micloud` library.
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

    open class CloudException(message: String) : Exception(message)

    class TwoFactorException(message: String) : CloudException(message)

    suspend fun fetchDevices(username: String, password: String): List<XiaomiDevice> =
        withContext(Dispatchers.IO) {
            val jar = MemoryCookieJar()
            val client = http.newBuilder().cookieJar(jar).build()
            val session = login(client, jar, username, password)
            listDevices(session)
        }

    // ------------------------------------------------------------------

    private data class Session(
        val userId: String,
        val serviceToken: String,
        val ssecurity: String,
    )

    private fun login(
        client: OkHttpClient,
        jar: MemoryCookieJar,
        username: String,
        password: String,
    ): Session {
        val account = "https://account.xiaomi.com"

        val step1 = Request.Builder()
            .url("$account/pass/serviceLogin?sid=xiaomiio&_json=true")
            .header("Cookie", "userId=$username")
            .build()
        val step1Text = client.newCall(step1).execute().use { it.body?.string().orEmpty() }
        val sign = jsonAfterPrefix(step1Text).optString("_sign")

        val form = FormBody.Builder()
            .add("sid", "xiaomiio")
            .add("hash", md5Hex(password).uppercase(java.util.Locale.US))
            .add("callback", "https://sts.api.io.mi.com/sts")
            .add("qs", "?sid=xiaomiio&_json=true")
            .add("user", username)
            .add("_json", "true")
            .apply { if (sign.isNotBlank()) add("_sign", sign) }
            .build()
        val step2 = Request.Builder()
            .url("$account/pass/serviceLoginAuth2")
            .post(form)
            .build()
        val step2Text = client.newCall(step2).execute().use { it.body?.string().orEmpty() }
        val step2Json = jsonAfterPrefix(step2Text)
        if (step2Json.optString("result") != "ok") {
            val code = step2Json.optInt("code")
            val desc = step2Json.optString("desc")
                .ifBlank { step2Json.optString("tip") }
                .ifBlank { "код $code" }
            if (code == 70016 || code == 70022) {
                throw TwoFactorException(
                    "Mi-аккаунт требует подтверждения (2FA/капча). " +
                        "Войдите в Mi Home на телефоне и повторите.",
                )
            }
            throw CloudException("Не удалось войти в Mi-аккаунт: $desc")
        }
        val userId = step2Json.optString("userId")
        val ssecurity = step2Json.optString("ssecurity")
        val location = step2Json.optString("location")
        if (userId.isBlank() || ssecurity.isBlank() || location.isBlank()) {
            throw CloudException("Mi-аккаунт вернул неполный ответ")
        }

        val step3 = Request.Builder().url(location).get().build()
        client.newCall(step3).execute().use { }
        val serviceToken = jar.find("serviceToken")?.value
            ?: throw CloudException("Mi-аккаунт не выдал serviceToken")

        return Session(userId, serviceToken, ssecurity)
    }

    private fun listDevices(session: Session): List<XiaomiDevice> {
        val url = "https://api.io.mi.com/app/home/device_list"
        val data = """{"getVirtualModel":true,"getHuamiDevices":1,"get_split_device":false,"support_smart_home":true}"""

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
                throw CloudException("Облако Xiaomi вернуло ${response.code}")
            }
            response.body?.string().orEmpty().trim()
        }
        if (text.startsWith("{")) {
            throw CloudException("Облако Xiaomi отклонило запрос: $text")
        }

        val json = JSONObject(rc4Decrypt(signedNonce, text))
        val list = json.optJSONObject("result")?.optJSONArray("list")
            ?: throw CloudException("В ответе облака нет списка устройств")

        val devices = mutableListOf<XiaomiDevice>()
        for (index in 0 until list.length()) {
            val obj = list.optJSONObject(index) ?: continue
            val token = obj.optString("token")
            if (token.isBlank()) continue
            devices.add(
                XiaomiDevice(
                    did = obj.optString("did"),
                    name = obj.optString("name").ifBlank { obj.optString("model") },
                    model = obj.optString("model"),
                    ip = obj.optString("localip").takeIf { it.isNotBlank() },
                    token = token,
                    mac = obj.optString("mac").takeIf { it.isNotBlank() },
                ),
            )
        }
        return devices
    }

    // ------------------------------------------------------------------

    private class MemoryCookieJar : CookieJar {
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

        fun find(name: String): Cookie? = items.firstOrNull { it.name == name }
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
        val text = parts.joinToString("&")
        return Base64.encodeToString(sha1(text.toByteArray()), Base64.NO_WRAP)
    }

    private fun rc4Encrypt(signedNonce: String, value: String): String {
        val engine = Rc4(Base64.decode(signedNonce, Base64.DEFAULT))
        engine.process(ByteArray(1024))
        return Base64.encodeToString(engine.process(value.toByteArray()), Base64.NO_WRAP)
    }

    private fun rc4Decrypt(signedNonce: String, value: String): String {
        val engine = Rc4(Base64.decode(signedNonce, Base64.DEFAULT))
        engine.process(ByteArray(1024))
        val bytes = Base64.decode(value, Base64.DEFAULT)
        return String(engine.process(bytes), Charsets.UTF_8)
    }

    private fun jsonAfterPrefix(text: String): JSONObject {
        val body = text.substringAfter("&&&START&&&")
        return JSONObject(body)
    }

    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun sha1(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(data)

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}

private fun defaultXiaomiClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()
