package the.lion.nexoDB.util

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * SHA-256 Hilfsfunktionen.
 *
 * Der Abgleich zwischen Server und Datenbank laeuft bewusst ueber Content-Hashes und
 * nicht ueber Zeitstempel: Dateisystem-Timestamps ueberleben weder ein Git-Checkout
 * noch einen Docker-Image-Rebuild, und zwischen Servern laufen die Uhren auseinander.
 * Ein Hash-Vergleich ist die einzige Variante, die "gleicher Inhalt" zuverlaessig erkennt.
 */
object Hashing {

    private val HEX = "0123456789abcdef".toCharArray()
    private const val BUFFER_SIZE = 64 * 1024

    fun sha256(bytes: ByteArray): String =
        toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        Files.newInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return toHex(digest.digest())
    }

    private fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[value ushr 4]
            out[i * 2 + 1] = HEX[value and 0x0F]
        }
        return String(out)
    }
}
