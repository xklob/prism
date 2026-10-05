package com.caleb.prism

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SongOptions(val enabled: Boolean = false, val recognition: Boolean = false, val hasKey: Boolean = false, val phraseBars: Int = 16)

/** Android Keystore protects the user-supplied token. This preference file is excluded from backup. */
class SongPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("song_assist", Context.MODE_PRIVATE)
    fun requestTimes(): List<Double> = prefs.getString("requests", "").orEmpty().split(',').mapNotNull { it.toDoubleOrNull() }
    fun saveRequestTimes(times: List<Double>) { prefs.edit().putString("requests", times.joinToString(",")).commit() }
    fun options() = SongOptions(prefs.getBoolean("enabled", false), prefs.getBoolean("recognition", false), token() != null,
        prefs.getInt("phraseBars", 16).takeIf { it in listOf(8, 16, 32) } ?: 16)
    fun save(options: SongOptions) { prefs.edit().putBoolean("enabled", options.enabled).putBoolean("recognition", options.recognition).putInt("phraseBars", options.phraseBars).apply() }
    fun token(): String? = runCatching {
        val encoded = prefs.getString("token", null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }.getOrNull()
    fun setToken(value: String) {
        if (value.isBlank()) { prefs.edit().remove("token").apply(); return }
        require(value.length <= 256 && value.all { it.isLetterOrDigit() || it in "_-" }) { "Enter the AudD token without spaces." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray())
        check(prefs.edit().putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("PrismAudD", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("PrismAudD", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
