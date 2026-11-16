package com.pantrykeeper.server.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Phones {
    private val mobile = Regex("^1[3-9]\\d{9}$")

    fun normalize(raw: String): String? {
        var s = raw.trim().replace(" ", "").replace("-", "")
        if (s.startsWith("+86")) s = s.removePrefix("+86")
        else if (s.startsWith("86") && s.length == 13) s = s.drop(2)
        return s.takeIf { mobile.matches(it) }
    }
}

class PhoneCrypto(encKey: ByteArray, hmacKey: ByteArray) {
    private val enc = SecretKeySpec(encKey.copyOf(), "AES")
    private val mac = SecretKeySpec(hmacKey.copyOf(), "HmacSHA256")
    private val random = SecureRandom()

    fun hmac(phone: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(this.mac)
        return mac.doFinal(phone.toByteArray(Charsets.UTF_8))
    }

    fun hmacHex(value: String): String = hmac(value).toHex()

    fun encrypt(phone: String): ByteArray {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, enc, GCMParameterSpec(128, iv))
        val body = cipher.doFinal(phone.toByteArray(Charsets.UTF_8))
        return iv + body
    }

    fun constantEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

fun constantTime(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

fun wipe(bytes: ByteArray) = Arrays.fill(bytes, 0)
