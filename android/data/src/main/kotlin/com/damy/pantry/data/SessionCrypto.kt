package com.damy.pantry.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM token wrapper. Production keys come from the Android Keystore. */
class SessionCrypto(private val key: SecretKey) {
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + body)
    }

    fun decrypt(stored: String): String {
        val raw = Base64.getDecoder().decode(stored)
        val iv = raw.copyOfRange(0, IV_BYTES)
        val body = raw.copyOfRange(IV_BYTES, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(body).toString(Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}

object AndroidKeystoreSession {
    const val ALIAS = "pantry-session"
    private const val PROVIDER = "AndroidKeyStore"

    fun crypto(): SessionCrypto {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            generator.init(spec)
            generator.generateKey()
        }
        val key = (ks.getEntry(ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        return SessionCrypto(key)
    }

    fun deleteAlias() {
        runCatching {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        }
    }
}

data class OpenedSession(val session: SessionStore, val message: String?)

/** Try the keystore twice. If it still fails, drop the saved session and ask for a fresh login. */
fun openSession(load: () -> SessionStore, deleteAlias: () -> Unit = { AndroidKeystoreSession.deleteAlias() }): OpenedSession {
    return try {
        OpenedSession(load(), null)
    } catch (_: Exception) {
        runCatching { deleteAlias() }
        try {
            OpenedSession(load(), null)
        } catch (_: Exception) {
            OpenedSession(MemorySession(), "安全存储不可用，请重新登录")
        }
    }
}
