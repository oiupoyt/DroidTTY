package com.asnidev.sysreadout.cover

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** A PIN kept only as a salted PBKDF2 hash. */
data class PinHash(val salt: ByteArray, val hash: ByteArray, val iterations: Int) {

    fun matches(pin: String): Boolean = MessageDigest.isEqual(hash, derive(pin, salt, iterations))

    fun encode(): String = listOf(iterations.toString(), b64(salt), b64(hash)).joinToString(":")

    override fun equals(other: Any?) = other is PinHash && encode() == other.encode()
    override fun hashCode() = encode().hashCode()

    companion object {
        const val ITERATIONS = 120_000
        const val MIN_LENGTH = 4
        const val MAX_LENGTH = 16

        fun valid(pin: String) = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

        fun of(pin: String, random: SecureRandom = SecureRandom(), iterations: Int = ITERATIONS): PinHash {
            require(valid(pin)) { "a PIN is $MIN_LENGTH to $MAX_LENGTH digits" }
            val salt = ByteArray(16).also(random::nextBytes)
            return PinHash(salt, derive(pin, salt, iterations), iterations)
        }

        fun decode(s: String?): PinHash? {
            val parts = s?.trim()?.split(':')?.takeIf { it.size == 3 } ?: return null
            return runCatching { PinHash(unb64(parts[1]), unb64(parts[2]), parts[0].toInt()) }.getOrNull()
        }

        private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(PBEKeySpec(pin.toCharArray(), salt, iterations, 256)).encoded

        private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
        private fun unb64(s: String) = Base64.getDecoder().decode(s)
    }
}

/** After [FREE_TRIES] wrong PINs, each further one waits longer: 30 s doubling up to 15 min. */
object Lockout {
    const val FREE_TRIES = 5
    private const val FIRST_MS = 30_000L
    private const val MAX_MS = 15 * 60_000L

    fun delayAfter(failures: Int): Long {
        if (failures < FREE_TRIES) return 0L
        val steps = (failures - FREE_TRIES).coerceAtMost(10)
        return (FIRST_MS shl steps).coerceAtMost(MAX_MS)
    }
}

/**
 * The own lock's PIN and failure count, in no-backup storage so a backup or a
 * transfer to another phone never carries them.
 */
class PinStore(context: Context) {
    private val dir = context.noBackupFilesDir
    private val pinFile = File(dir, "lock_pin")
    private val failFile = File(dir, "lock_failures")

    val isSet: Boolean get() = read() != null

    fun read(): PinHash? = runCatching { PinHash.decode(pinFile.readText()) }.getOrNull()

    fun set(pin: String) {
        write(pinFile, PinHash.of(pin).encode())
        clearFailures()
    }

    fun clear() {
        pinFile.delete()
        clearFailures()
    }

    /** Failures so far and when the last one happened (wall clock, survives restarts). */
    fun failures(): Pair<Int, Long> = runCatching {
        val (n, at) = failFile.readText().trim().split(':')
        n.toInt() to at.toLong()
    }.getOrDefault(0 to 0L)

    fun recordFailure(now: Long) {
        write(failFile, "${failures().first + 1}:$now")
    }

    fun clearFailures() {
        failFile.delete()
    }

    /** Milliseconds until the next try is allowed; 0 when it is. */
    fun waitMs(now: Long): Long {
        val (n, at) = failures()
        return (at + Lockout.delayAfter(n) - now).coerceAtLeast(0L)
    }

    private fun write(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }
}
