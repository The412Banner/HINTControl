package com.banner.hintcontrol.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts gateway passwords with an AES-256-GCM key that never leaves the Android Keystore.
 * Returns null instead of throwing when the Keystore is unavailable; the app then asks for the
 * password again instead of storing it in clear text.
 */
class SecretCipher(private val alias: String = "gateway_passwords") {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(out, Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.w(TAG, "encrypt failed: ${e.javaClass.simpleName}")
        null
    }

    fun decrypt(encoded: String): String? = try {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_BYTES))
        String(cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.w(TAG, "decrypt failed: ${e.javaClass.simpleName}")
        null
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG = "SecretCipher"
    }
}
