package the.lion.nexoDB.db

import org.bson.Document
import org.bson.types.ObjectId

enum class StorageKind {
    /** Datei liegt direkt als BinData im Metadaten-Dokument (schnell, fuer kleine Dateien). */
    INLINE,

    /** Datei liegt im GridFS-Bucket, das Dokument haelt nur die ObjectId. */
    GRIDFS;

    companion object {
        fun parse(raw: String?): StorageKind =
            if (raw.equals("gridfs", ignoreCase = true)) GRIDFS else INLINE
    }

    fun asString(): String = if (this == GRIDFS) "gridfs" else "inline"
}

/**
 * Ein Metadaten-Dokument aus der Collection `nexo_resources`.
 *
 * Schema:
 * ```
 * {
 *   _id:       "nexo-pack:assets/minecraft/textures/item/sword.png",  // "<set>:<path>"
 *   set:       "nexo-pack",
 *   path:      "assets/minecraft/textures/item/sword.png",
 *   sha256:    "9f86d081...",
 *   size:      NumberLong(20481),
 *   storage:   "gridfs" | "inline",
 *   gridfs:    ObjectId("..."),   // nur bei storage = "gridfs"
 *   data:      BinData(...),      // nur bei storage = "inline"
 *   deleted:   false,             // Tombstone-Flag
 *   rev:       NumberLong(42),    // globaler Revisionszaehler des Pushes
 *   updatedAt: ISODate("..."),
 *   updatedBy: "master-01"        // server-id des Schreibers
 * }
 * ```
 *
 * Die Content-Felder (`data`) werden beim Index-Laden bewusst wegprojiziert -
 * fuer den Abgleich reichen Hash und Groesse.
 */
class ResourceRecord(
    val key: String,
    val set: String,
    val path: String,
    val sha256: String,
    val size: Long,
    val storage: StorageKind,
    val gridFsId: ObjectId?,
    val deleted: Boolean,
    val revision: Long,
    val updatedAt: Long,
    val updatedBy: String
) {

    companion object {
        const val F_ID = "_id"
        const val F_SET = "set"
        const val F_PATH = "path"
        const val F_SHA = "sha256"
        const val F_SIZE = "size"
        const val F_STORAGE = "storage"
        const val F_GRIDFS = "gridfs"
        const val F_DATA = "data"
        const val F_DELETED = "deleted"
        const val F_REVISION = "rev"
        const val F_UPDATED_AT = "updatedAt"
        const val F_UPDATED_BY = "updatedBy"

        fun key(set: String, path: String): String = "$set:$path"

        fun from(document: Document): ResourceRecord? {
            val id = document[F_ID]?.toString() ?: return null
            val set = document.getString(F_SET) ?: return null
            val path = document.getString(F_PATH) ?: return null

            return ResourceRecord(
                key = id,
                set = set,
                path = path,
                sha256 = document.getString(F_SHA).orEmpty(),
                size = longOf(document[F_SIZE]),
                storage = StorageKind.parse(document.getString(F_STORAGE)),
                gridFsId = document[F_GRIDFS] as? ObjectId,
                deleted = document.getBoolean(F_DELETED, false),
                revision = longOf(document[F_REVISION]),
                updatedAt = document.getDate(F_UPDATED_AT)?.time ?: 0L,
                updatedBy = document.getString(F_UPDATED_BY).orEmpty()
            )
        }

        /** MongoDB liefert Zahlen je nach Schreibweg als Integer oder Long zurueck. */
        fun longOf(value: Any?, fallback: Long = 0L): Long =
            (value as? Number)?.toLong() ?: fallback
    }
}
