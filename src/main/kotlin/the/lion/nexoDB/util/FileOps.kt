package the.lion.nexoDB.util

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

object FileOps {

    /** Endung der Temp-Dateien; wird beim Scannen ignoriert. */
    const val TEMP_SUFFIX = ".nexodb-tmp"

    /**
     * Sammelt alle Dateien unter [root], die zu [includes] passen und nicht von
     * [excludes] getroffen werden.
     *
     * @return Map von relativem Pfad (immer mit "/") auf den absoluten Pfad.
     */
    fun scan(root: Path, includes: List<Glob>, excludes: List<Glob>): Map<String, Path> {
        if (!Files.isDirectory(root)) return emptyMap()

        val normalizedRoot = root.normalize()
        val files = Files.walk(normalizedRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) }.toList()
        }

        val result = LinkedHashMap<String, Path>(files.size.coerceAtLeast(16))
        for (file in files) {
            val relative = relativize(normalizedRoot, file)
            if (relative.isEmpty() || relative.endsWith(TEMP_SUFFIX)) continue
            if (includes.isNotEmpty() && !Glob.matchesAny(includes, relative)) continue
            if (Glob.matchesAny(excludes, relative)) continue
            result[relative] = file
        }
        return result
    }

    fun relativize(root: Path, file: Path): String =
        root.relativize(file).toString().replace('\\', '/')

    /**
     * Loest einen aus der Datenbank gelesenen relativen Pfad sicher gegen [root] auf.
     *
     * Schutz gegen "Zip-Slip": ein manipuliertes Dokument mit dem Pfad
     * `../../../server.properties` darf niemals ausserhalb des Sync-Ordners landen.
     *
     * @return den Zielpfad, oder null wenn der Pfad unsicher/ungueltig ist.
     */
    fun safeResolve(root: Path, relative: String): Path? {
        val normalized = relative.replace('\\', '/').trim('/')
        if (normalized.isEmpty()) return null
        if (normalized.contains(':')) return null

        val segments = normalized.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return null

        val base = root.normalize().toAbsolutePath()
        val candidate = base.resolve(normalized).normalize()
        if (!candidate.startsWith(base)) return null
        return candidate
    }

    /**
     * Schreibt [bytes] atomar nach [target].
     *
     * Wichtig fuer den Resourcepack-Ordner: Nexo darf niemals eine halb geschriebene
     * PNG-Datei einlesen. Deshalb erst in eine Temp-Datei, dann per Move ersetzen.
     */
    fun atomicWrite(target: Path, bytes: ByteArray) {
        val parent = target.parent
        if (parent != null) Files.createDirectories(parent)

        val temp = target.resolveSibling(target.fileName.toString() + TEMP_SUFFIX)
        Files.write(
            temp,
            bytes,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )

        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(temp) }
            throw e
        }
    }

    fun deleteIfExists(target: Path): Boolean = try {
        Files.deleteIfExists(target)
    } catch (_: IOException) {
        false
    }

    /**
     * Entfernt leere Verzeichnisse zwischen [start] und [root], damit nach dem Loeschen
     * einer Textur keine leeren Ordner im Pack zurueckbleiben. [root] selbst bleibt immer.
     */
    fun pruneEmptyDirs(root: Path, start: Path) {
        val base = root.normalize().toAbsolutePath()
        var current: Path? = start.normalize().toAbsolutePath().parent

        while (true) {
            val directory = current ?: return
            if (directory == base || !directory.startsWith(base)) return

            val isEmpty = runCatching {
                Files.newDirectoryStream(directory).use { !it.iterator().hasNext() }
            }.getOrDefault(false)

            if (!isEmpty) return
            if (!deleteIfExists(directory)) return
            current = directory.parent
        }
    }
}
