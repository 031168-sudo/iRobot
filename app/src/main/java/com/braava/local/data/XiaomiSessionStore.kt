package com.braava.local.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.braava.local.data.cloud.XiaomiCloud
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted storage for the Mi-account cloud session (needed for cloud runs). */
class XiaomiSessionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): XiaomiCloud.Session? {
        val payload = prefs.getString(KEY_PAYLOAD, null) ?: return null
        val result = runCatching {
            val bytes = Base64.decode(payload, Base64.NO_WRAP)
            if (bytes.size <= IV_LEN) return@runCatching null
            val iv = bytes.copyOfRange(0, IV_LEN)
            val ciphertext = bytes.copyOfRange(IV_LEN, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            val userId = json.optString("userId")
            val serviceToken = json.optString("serviceToken")
            val ssecurity = json.optString("ssecurity")
            if (userId.isBlank() || serviceToken.isBlank() || ssecurity.isBlank()) {
                null
            } else {
                XiaomiCloud.Session(userId, serviceToken, ssecurity)
            }
        }.getOrNull()
        if (result == null) prefs.edit().remove(KEY_PAYLOAD).apply()
        return result
    }

    fun save(session: XiaomiCloud.Session) {
        runCatching {
            val json = JSONObject()
                .put("userId", session.userId)
                .put("serviceToken", session.serviceToken)
                .put("ssecurity", session.ssecurity)
                .toString()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
            val payload = Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
            prefs.edit().putString(KEY_PAYLOAD, payload).apply()
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_PAYLOAD).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "braavalocal.xiaomi.session"
        const val PREFS = "xiaomi_session"
        const val KEY_PAYLOAD = "payload"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val TAG_BITS = 128
    }
}
