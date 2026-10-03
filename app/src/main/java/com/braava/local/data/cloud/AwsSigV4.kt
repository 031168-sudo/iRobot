package com.braava.local.data.cloud

import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal AWS Signature Version 4 signer, ported from the reference
 * implementations used by Home Assistant's iRobot cloud clients.
 *
 * iRobot's data API is an AWS API Gateway (service `execute-api`) fronted
 * by `httpBaseAuth`. The temporary credentials come from `/v2/login`.
 */
class AwsSigV4(
    private val accessKeyId: String,
    private val secretKey: String,
    private val sessionToken: String,
) {

    fun headers(
        method: String,
        service: String,
        region: String,
        host: String,
        path: String,
        params: Map<String, String> = emptyMap(),
        payload: String = "",
    ): Map<String, String> {
        val now = Date()
        val amzDate = utc(now, "yyyyMMdd'T'HHmmss'Z'")
        val dateStamp = utc(now, "yyyyMMdd")

        val canonicalUri = encodePath(path)
        val canonicalQuery = params.keys.sorted().joinToString("&") { key ->
            "${encode(key)}=${encode(params[key].orEmpty())}"
        }

        val baseHeaders = linkedMapOf(
            "accept" to "application/json",
            "content-type" to "application/json",
            "host" to host,
            "user-agent" to USER_AGENT,
            "x-amz-date" to amzDate,
        )
        val sortedKeys = baseHeaders.keys.sorted()
        val canonicalHeaders = sortedKeys.joinToString("") { "$it:${baseHeaders[it]}\n" }
        val signedHeaders = sortedKeys.joinToString(";")

        val canonicalRequest = listOf(
            method.uppercase(Locale.US),
            canonicalUri,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            sha256Hex(payload),
        ).joinToString("\n")

        val scope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = listOf(
            ALGORITHM,
            amzDate,
            scope,
            sha256Hex(canonicalRequest),
        ).joinToString("\n")

        val signature = hmacHex(signingKey(dateStamp, region, service), stringToSign)
        val authorization = "$ALGORITHM Credential=$accessKeyId/$scope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"

        return baseHeaders + mapOf(
            "Authorization" to authorization,
            "x-amz-security-token" to sessionToken,
        )
    }

    private fun signingKey(dateStamp: String, region: String, service: String): ByteArray {
        var key = hmac(("AWS4$secretKey").toByteArray(), dateStamp)
        key = hmac(key, region)
        key = hmac(key, service)
        return hmac(key, "aws4_request")
    }

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacHex(key: ByteArray, data: String): String =
        hmac(key, data).joinToString("") { "%02x".format(it) }

    private fun sha256Hex(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

    private fun encodePath(path: String): String =
        path.split("/").joinToString("/") { encode(it) }

    private fun utc(date: Date, pattern: String): String =
        SimpleDateFormat(pattern, Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(date)

    private companion object {
        const val ALGORITHM = "AWS4-HMAC-SHA256"
        const val USER_AGENT = "aws-sdk-iOS/2.27.6 iOS/18.0.1 en_US"
    }
}
