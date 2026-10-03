package com.braava.local.data.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * iRobot cloud client: Gigya login to obtain each robot's local password,
 * plus the AWS-signed data API used to fetch cloud maps.
 *
 * Local control does not need any of this after the first login; the cloud
 * part exists only to retrieve the map, which the robot never exposes over
 * the LAN.
 */
class IrobotCloud(
    private val http: OkHttpClient = defaultClient(),
) {

    data class CloudRobot(
        val blid: String,
        val password: String,
        val name: String?,
        val sku: String?,
        val softwareVer: String?,
    )

    /** Authenticated cloud session: robots plus temporary AWS credentials. */
    data class Session(
        val robots: List<CloudRobot>,
        val accessKeyId: String,
        val secretKey: String,
        val sessionToken: String,
        val region: String,
        val apiHosts: List<String>,
    )

    open class CloudException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    /** Gigya did not recognise the login on this site (wrong region). */
    class InvalidCredentialsException(message: String) : CloudException(message)

    suspend fun fetchRobots(
        username: String,
        password: String,
        countryCode: String? = null,
    ): List<CloudRobot> = login(username, password, countryCode).robots

    suspend fun login(
        username: String,
        password: String,
        countryCode: String? = null,
    ): Session = withContext(Dispatchers.IO) {
        val regions = countryCode?.let { listOf(it) } ?: DEFAULT_REGIONS
        var lastError: CloudException? = null
        for (region in regions) {
            val endpoints = try {
                discoverEndpoints(region)
            } catch (e: CloudException) {
                lastError = e
                continue
            }
            val session = try {
                gigyaLogin(endpoints, username, password)
            } catch (e: InvalidCredentialsException) {
                lastError = e
                continue
            }
            val body = irobotLogin(endpoints.httpBase, session)
            val credentials = body.optJSONObject("credentials")
                ?: throw CloudException("iRobot не вернул AWS-креденшелы")
            return@withContext Session(
                robots = parseRobots(body),
                accessKeyId = credentials.optString("AccessKeyId"),
                secretKey = credentials.optString("SecretKey"),
                sessionToken = credentials.optString("SessionToken"),
                region = endpoints.region,
                apiHosts = endpoints.apiHosts,
            )
        }
        throw lastError ?: CloudException("Не удалось войти в аккаунт iRobot")
    }

    // ---- cloud data API (AWS-signed) ----

    /** Persistent map list. The API returns a JSON array; kept as raw text. */
    suspend fun getPmaps(session: Session, blid: String): String =
        sessionGet(
            session,
            "/v1/$blid/pmaps",
            mapOf("visible" to "true", "activeDetails" to "2"),
        )

    suspend fun getUmf(
        session: Session,
        blid: String,
        pmapId: String,
        versionId: String,
    ): JSONObject = JSONObject(
        sessionGet(
            session,
            "/v1/$blid/pmaps/$pmapId/versions/$versionId/umf",
            mapOf("activeDetails" to "2"),
        ),
    )

    /** Raw mission-history JSON (a list of per-mission records). */
    suspend fun getMissionHistory(
        session: Session,
        blid: String,
        count: Int = 100,
    ): String = sessionGet(
        session,
        "/v1/$blid/missionhistory",
        mapOf(
            "app_id" to APP_ID,
            "filterType" to "omit_quickly_canceled_not_scheduled",
            "supportedDoneCodes" to "dndEnd,returnHomeEnd",
            "count" to count.toString(),
        ),
    )

    private suspend fun sessionGet(
        session: Session,
        path: String,
        params: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (host in session.apiHosts) {
            try {
                return@withContext doGet(session, host, path, params)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: CloudException("Нет доступного хоста API iRobot")
    }

    private fun doGet(
        session: Session,
        host: String,
        path: String,
        params: Map<String, String>,
    ): String {
        val signer = AwsSigV4(session.accessKeyId, session.secretKey, session.sessionToken)
        val headers = signer.headers(
            method = "GET",
            service = "execute-api",
            region = session.region,
            host = host,
            path = path,
            params = params,
        )
        val url = buildString {
            append("https://")
            append(host)
            append(path)
            if (params.isNotEmpty()) {
                append("?")
                append(
                    params.keys.sorted().joinToString("&") { key ->
                        "$key=${params[key]}"
                    },
                )
            }
        }
        val builder = Request.Builder().url(url.toHttpUrl()).get()
        headers.forEach { (key, value) ->
            if (key != "host") builder.header(key, value)
        }
        val response = try {
            http.newCall(builder.build()).execute()
        } catch (e: Exception) {
            throw CloudException("хост $host: ${e.message}", e)
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw CloudException("хост $host: HTTP ${it.code}")
            }
            return text
        }
    }

    // ------------------------------------------------------------------

    private data class Endpoints(
        val apiKey: String,
        val gigyaBase: String,
        val httpBase: String,
        val apiHosts: List<String>,
        val region: String,
    )

    private data class GigyaSession(
        val uid: String,
        val signature: String,
        val timestamp: String,
    )

    private fun discoverEndpoints(countryCode: String): Endpoints {
        val url = "https://disc-prod.iot.irobotapi.com/v1/discover/endpoints?country_code=$countryCode"
        val body = getJson(url)
            ?: throw CloudException("Не удалось получить эндпоинты iRobot")

        val gigya = body.optJSONObject("gigya")
            ?: throw CloudException("В ответе нет данных Gigya")
        val apiKey = gigya.optString("api_key").takeIf { it.isNotBlank() }
            ?: throw CloudException("Gigya не вернула API-ключ")

        val datacenter = gigya.optString("datacenter_domain").takeIf { it.isNotBlank() }
        val gigyaBase = if (datacenter != null) {
            if (datacenter.startsWith("http")) datacenter else "https://accounts.$datacenter"
        } else {
            "https://accounts.us1.gigya.com"
        }

        val deployments = body.optJSONObject("deployments")
        val current = body.optString("current_deployment")
        val deployment = deployments?.optJSONObject(current)
        val httpBase = deployment?.optString("httpBase")?.takeIf { it.isNotBlank() }
            ?: pickHttpBase(body)
            ?: "https://unauth2.prod.iot.irobotapi.com"
        val region = deployment?.optString("awsRegion")?.takeIf { it.isNotBlank() } ?: "us-east-1"

        // Some networks (notably VPN DNS) resolve only part of iRobot's
        // CloudFront hosts; collect every candidate so the request can fall
        // back to whichever the device can actually reach.
        val hosts = LinkedHashSet<String>()
        deployment?.optString("httpBaseAuth")?.takeIf { it.isNotBlank() }?.let { hosts += hostOf(it) }
        hosts += hostOf(httpBase)
        deployments?.keys()?.forEach { key ->
            deployments.optJSONObject(key)?.optString("httpBaseAuth")
                ?.takeIf { it.isNotBlank() }
                ?.let { hosts += hostOf(it) }
        }

        return Endpoints(
            apiKey = apiKey,
            gigyaBase = gigyaBase,
            httpBase = httpBase,
            apiHosts = hosts.toList(),
            region = region,
        )
    }

    private fun hostOf(url: String): String =
        url.removePrefix("https://").removePrefix("http://").substringBefore("/")

    private fun pickHttpBase(body: JSONObject): String? {
        val deployments = body.optJSONObject("deployments") ?: return null
        return deployments.keys().asSequence()
            .sortedDescending()
            .mapNotNull { deployments.optJSONObject(it)?.optString("httpBase") }
            .firstOrNull { it.isNotBlank() }
    }

    private fun gigyaLogin(
        endpoints: Endpoints,
        username: String,
        password: String,
    ): GigyaSession {
        val form = FormBody.Builder()
            .add("apiKey", endpoints.apiKey)
            .add("targetenv", "mobile")
            .add("targetEnv", "mobile")
            .add("loginID", username)
            .add("password", password)
            .add("format", "json")
            .build()
        val request = Request.Builder()
            .url("${endpoints.gigyaBase}/accounts.login")
            .post(form)
            .build()

        val body = execute(request, "Ошибка входа в аккаунт iRobot")
        if (body.optInt("errorCode", -1) != 0) {
            val detail = body.optString("errorDetails")
                .ifBlank { body.optString("errorMessage") }
            val errorCode = body.optInt("errorCode", -1)
            if (errorCode == INVALID_LOGIN_CODE) {
                throw InvalidCredentialsException("Неверный логин или пароль. $detail".trim())
            }
            throw CloudException("Ошибка входа в аккаунт iRobot. $detail".trim())
        }
        val uid = body.optString("UID").takeIf { it.isNotBlank() }
            ?: throw CloudException("Gigya не вернула UID (возможно, нужна 2FA)")
        val signature = body.optString("UIDSignature").takeIf { it.isNotBlank() }
            ?: throw CloudException("Gigya не вернула подпись сессии")
        val timestamp = body.optString("signatureTimestamp").takeIf { it.isNotBlank() }
            ?: throw CloudException("Gigya не вернула timestamp")
        return GigyaSession(uid, signature, timestamp)
    }

    private fun irobotLogin(httpBase: String, session: GigyaSession): JSONObject {
        val payload = JSONObject()
            .put("app_id", APP_ID)
            .put("assume_robot_ownership", 0)
            .put(
                "gigya",
                JSONObject()
                    .put("signature", session.signature)
                    .put("timestamp", session.timestamp)
                    .put("uid", session.uid),
            )
        val request = Request.Builder()
            .url("$httpBase/v2/login")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        return execute(request, "Ошибка авторизации в iRobot")
    }

    private fun parseRobots(body: JSONObject): List<CloudRobot> {
        val robots = body.optJSONObject("robots")
            ?: throw CloudException("Аккаунт не вернул список роботов")
        if (robots.length() == 0) throw CloudException("В аккаунте нет роботов")
        return robots.keys().asSequence().map { blid ->
            val robot = robots.getJSONObject(blid)
            CloudRobot(
                blid = blid,
                password = robot.optString("password"),
                name = robot.optString("name").takeIf { it.isNotBlank() },
                sku = robot.optString("sku").takeIf { it.isNotBlank() },
                softwareVer = robot.optString("softwareVer").takeIf { it.isNotBlank() },
            )
        }.filter { it.password.isNotBlank() }.toList()
    }

    // ------------------------------------------------------------------

    private fun getJson(url: String): JSONObject? =
        runCatching { execute(Request.Builder().url(url).get().build(), "Сеть недоступна") }
            .getOrNull()

    private fun execute(request: Request, errorPrefix: String): JSONObject {
        val response = try {
            http.newCall(request).execute()
        } catch (e: Exception) {
            throw CloudException("$errorPrefix: ${e.message}", e)
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw CloudException("$errorPrefix (HTTP ${it.code})")
            }
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                throw CloudException("$errorPrefix: некорректный ответ сервера", e)
            }
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val APP_ID = "ANDROID-C7FB240E-DF34-42D7-AE4E-A8C17079A294"

        /** Gigya "Invalid LoginID" — the account lives on another region's site. */
        const val INVALID_LOGIN_CODE = 403042

        /**
         * Regions to try, in order. Russia is served by its own Gigya site
         * (eu1.gigya.com) and key; everyone else uses the US site.
         */
        val DEFAULT_REGIONS = listOf("RU", "US")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
