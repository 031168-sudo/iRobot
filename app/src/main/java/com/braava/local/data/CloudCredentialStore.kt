package com.braava.local.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Remembers the iRobot account e-mail and password so the cloud login does
 * not have to be typed every time.
 *
 * The payload is encrypted with an AES key that lives in Android Keystore;
 * only the ciphertext is kept in app-private SharedPreferences. The key is
 * device-bound and is not backed up, so a restored backup simply cannot be
 * decrypted and is discarded.
 */
class CloudCredentialStore(context: Context) {

    data class Credentials(val username: String, val password: String)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): Credentials? {
        val payload = prefs.getString(KEY_PAYLOAD, null) ?: return null
        val result = runCatching {
            val bytes = Base64.decode(payload, Base64.NO_WRAP)
            if (bytes.size <= IV_LEN) return@runCatching null
            val iv = bytes.copyOfRange(0, IV_LEN)
            val ciphertext = bytes.copyOfRange(IV_LEN, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            val username = json.optString("username")
            val password = json.optString("password")
            if (username.isBlank() || password.isBlank()) null
            else Credentials(username, password)
        }.getOrNull()

        // A key can disappear after a backup restore or a lock-screen change.
        // Stale ciphertext would only fail again, so drop it.
        if (result == null) prefs.edit().remove(KEY_PAYLOAD).apply()
        return result
    }

    fun save(username: String, password: String) {
        runCatching {
            val json = JSONObject()
                .put("username", username)
                .put("password", password)
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
        const val KEY_ALIAS = "braavalocal.cloud.credentials"
        const val PREFS = "cloud_credentials"
        const val KEY_PAYLOAD = "payload"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val TAG_BITS = 128
    }
}
