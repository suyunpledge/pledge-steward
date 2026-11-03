package com.forge.android.bridge

import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys at rest: AES-GCM with a non-exportable Android Keystore key.
 *
 * Sealed values look like "ks1:<base64(iv | ciphertext)>". Plain values written by older
 * builds are returned unchanged by [open] and get sealed the next time they are saved.
 */
object SecretBox {
    private const val PREFIX = "ks1:"
    private const val ALIAS = "steward-secrets-v1"

    fun isSealed(value: String) = value.startsWith(PREFIX)

    fun seal(plain: String): String {
        if (plain.isEmpty() || isSealed(plain)) return plain
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(data)
    }

    /** Plain text for a sealed value; legacy plain values pass through; undecryptable -> "". */
    fun open(value: String): String {
        if (!isSealed(value)) return value
        return runCatching {
            val data = Base64.getDecoder().decode(value.removePrefix(PREFIX))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
            String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
        }.getOrDefault("")   // key lost (e.g. app data restored elsewhere): ask the user again
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
}
