package com.wildenglish.app.audiobook.data

import com.wildenglish.app.R
import com.wildenglish.app.i18n.Strings
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts `.jpd` containers (see PACKAGE_FORMAT.md). Container layout:
 *
 * ```
 * 0..3    magic "JPD1"
 * 4..19   salt (16 bytes, PBKDF2)
 * 20..31  IV (12 bytes, AES-GCM)
 * 32..    ciphertext of the ZIP bytes (GCM tag appended at the end)
 * ```
 *
 * Encryption is intentionally App-bound: third-party archive tools cannot open
 * the file. Note this deters casual extraction/copying; the key is embedded in
 * the client, so it is not DRM against a determined reverse engineer.
 *
 * A full `Cipher.doFinal` (rather than `CipherInputStream`) is used because the
 * latter has known AEAD/EOF quirks on older Android APIs. The decrypted ZIP is
 * returned as an in-memory stream and never written to disk in plaintext.
 */
object JpdCodec {

    private val MAGIC = byteArrayOf('J'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), '1'.code.toByte())
    private const val HEADER_SIZE = 4 + 16 + 12
    private const val ITERATIONS = 65_536
    private const val KEY_BITS = 256

    // Keep in sync with tools/PackJpd.java.
    private const val PASSPHRASE = "wild-english.audiobook.jpd.v1"

    /** Returns a stream over the decrypted ZIP bytes. Caller must close it. */
    fun decrypt(src: InputStream): InputStream {
        val all = src.use { it.readBytes() }
        if (all.size < HEADER_SIZE + 16) {
            throw IOException(Strings.get(R.string.jpd_short))
        }
        for (i in MAGIC.indices) {
            if (all[i] != MAGIC[i]) {
                throw IOException(Strings.get(R.string.jpd_invalid))
            }
        }
        val salt = all.copyOfRange(4, 20)
        val iv = all.copyOfRange(20, 32)
        val ciphertext = all.copyOfRange(32, all.size)
        val zipBytes = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(salt), GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw IOException(Strings.get(R.string.jpd_decrypt_failed, e.message ?: ""))
        }
        return ByteArrayInputStream(zipBytes)
    }

    private fun deriveKey(salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(PASSPHRASE.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}
