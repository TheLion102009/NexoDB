package the.lion.nexoDB.util

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Caffeine-Cache fuer Datei-Hashes.
 *
 * Ohne Cache muesste jeder Push/Pull-Durchlauf alle ~50 MB neu durch SHA-256 schieben.
 * Der Cache speichert pro Key (mtime, size, hash); solange sich mtime und size nicht
 * geaendert haben, wird der Hash wiederverwendet. Damit kostet ein Durchlauf ohne
 * Aenderungen praktisch nur noch ein stat() pro Datei.
 */
class HashCache(maximumSize: Long) {

    private class Stamp(val lastModified: Long, val size: Long, val hash: String)

    private val cache: Cache<String, Stamp> = Caffeine.newBuilder()
        .maximumSize(maximumSize)
        .build<String, Stamp>()

    fun hashOf(key: String, file: Path): String {
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
        val lastModified = attributes.lastModifiedTime().toMillis()
        val size = attributes.size()

        val cached = cache.getIfPresent(key)
        if (cached != null && cached.lastModified == lastModified && cached.size == size) {
            return cached.hash
        }

        val hash = Hashing.sha256(file)
        cache.put(key, Stamp(lastModified, size, hash))
        return hash
    }

    /** Nach einem Download: Hash direkt merken, statt die Datei erneut zu lesen. */
    fun remember(key: String, file: Path, hash: String) {
        val attributes = runCatching { Files.readAttributes(file, BasicFileAttributes::class.java) }.getOrNull() ?: return
        cache.put(key, Stamp(attributes.lastModifiedTime().toMillis(), attributes.size(), hash))
    }

    fun invalidate(key: String) = cache.invalidate(key)

    fun invalidateAll() = cache.invalidateAll()

    fun size(): Long = cache.estimatedSize()
}
