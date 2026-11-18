package com.boardresult.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Security {
    private val rnd = SecureRandom()
    private const val ITER = 60_000

    private fun pbkdf2(pw: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pw.toCharArray(), salt, ITER, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun hash(pw: String): String {
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val enc = Base64.getEncoder()
        return enc.encodeToString(salt) + '$' + enc.encodeToString(pbkdf2(pw, salt))
    }

    fun verify(pw: String, stored: String): Boolean {
        if (stored.isEmpty()) return false
        val parts = stored.split('$')
        if (parts.size != 2) return false
        return try {
            val dec = Base64.getDecoder()
            val salt = dec.decode(parts[0])
            val expected = dec.decode(parts[1])
            MessageDigest.isEqual(expected, pbkdf2(pw, salt))
        } catch (e: Exception) {
            false
        }
    }

    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"

    fun randomPassword(len: Int = 8): String =
        (1..len).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")

    fun token(): String {
        val b = ByteArray(24)
        rnd.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    fun randomInt(bound: Int): Int = rnd.nextInt(bound)
}
