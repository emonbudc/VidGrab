package com.vidgrab.app.core.common

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Salted PBKDF2 hashing for the vault PIN, so the settings store never holds the PIN itself.
 * Four digits are brute-forceable offline whatever the KDF, so the salt and the unlock
 * throttling do the real work; this only stops the stored value from being read directly.
 */
object VaultPin {
    private const val ALGORITHM = "PBKDF2WithHmacSHA1"
    private const val PREFIX = "pbkdf2"
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    fun isHashed(stored: String): Boolean = stored.startsWith("$PREFIX\$")

    fun hash(pin: String, iterations: Int = ITERATIONS): String {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val key = derive(pin, salt, iterations)
        return "$PREFIX\$$iterations\$${salt.toHex()}\$${key.toHex()}"
    }

    /** Also accepts the plaintext values saved by older versions, so an existing PIN keeps working. */
    fun verify(pin: String, stored: String?): Boolean {
        if (stored.isNullOrBlank() || stored == "loading") return false
        if (!isHashed(stored)) return MessageDigest.isEqual(pin.toByteArray(), stored.toByteArray())
        val parts = stored.split('$')
        if (parts.size != 4) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 10_000..1_000_000 } ?: return false
        val salt = parts[2].hexToBytes() ?: return false
        val expected = parts[3].hexToBytes() ?: return false
        return MessageDigest.isEqual(derive(pin, salt, iterations), expected)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray? {
        if (length % 2 != 0) return null
        return runCatching { ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }
}
