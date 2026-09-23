package the.lion.nexoDB.db

import com.mongodb.MongoWriteConcernException
import com.mongodb.MongoWriteException
import com.mongodb.client.gridfs.model.GridFSUploadOptions
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.Projections
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.Binary
import org.bson.types.ObjectId
import the.lion.nexoDB.config.NexoDBConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.logging.Level
import java.util.logging.Logger

class MasterLockInfo(
    val serverId: String,
    val heartbeatAt: Long,
    val expiresAt: Long
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = expiresAt <= now
}

sealed class MasterClaim {
    /** Dieser Server haelt den Master-Lock. */
    object Acquired : MasterClaim()

    /** Ein anderer Server haelt den Lock - hier liegt ein Split-Brain vor. */
    class Conflict(val holder: MasterLockInfo?) : MasterClaim()

    /** Die Datenbank war nicht erreichbar o.ae. */
    class Failed(val error: Throwable) : MasterClaim()
}

/**
 * Alle Lese-/Schreibzugriffe auf das NexoDB-Schema.
 *
 * Saemtliche Methoden blockieren und duerfen deshalb ausschliesslich vom
 * Sync-Worker-Thread (siehe SyncService) aufgerufen werden, nie vom Main-Thread.
 */
class ResourceRepository(
    private val mongo: MongoManager,
    private val config: NexoDBConfig,
    private val logger: Logger
) {

    // ------------------------------------------------------------------ Index

    /**
     * Laedt alle Metadaten (ohne Dateiinhalte) als Map "<set>:<path>" -> Record.
     * Bei ~50 MB Nutzdaten sind das typischerweise nur wenige hundert KB Traffic.
     */
    fun loadIndex(): MutableMap<String, ResourceRecord> {
        val index = LinkedHashMap<String, ResourceRecord>()
        val cursor = mongo.resources.find()
            .projection(Projections.exclude(ResourceRecord.F_DATA))
            .batchSize(500)

        for (document in cursor) {
            val record = ResourceRecord.from(document) ?: continue
            index[record.key] = record
        }
        return index
    }

    fun loadContent(record: ResourceRecord): ByteArray = when (record.storage) {
        StorageKind.INLINE -> {
            val document = mongo.resources
                .find(Filters.eq(ResourceRecord.F_ID, record.key))
                .projection(Projections.include(ResourceRecord.F_DATA))
                .first()
                ?: throw IllegalStateException("Dokument '${record.key}' existiert nicht mehr.")

            when (val data = document[ResourceRecord.F_DATA]) {
                is Binary -> data.data
                is ByteArray -> data
                else -> throw IllegalStateException("Dokument '${record.key}' enthaelt keine Inline-Daten.")
            }
        }

        StorageKind.GRIDFS -> {
            val id = record.gridFsId
                ?: throw IllegalStateException("Dokument '${record.key}' hat keine GridFS-Referenz.")

            val initialSize = if (record.size in 1..Int.MAX_VALUE.toLong()) record.size.toInt() else 8192
            val output = ByteArrayOutputStream(initialSize)
            mongo.gridFs.downloadToStream(id, output)
            output.toByteArray()
        }
    }

    // ----------------------------------------------------------------- Writes

    /**
     * Legt eine Datei an oder aktualisiert sie. Kleine Dateien landen inline im
     * Dokument, grosse in GridFS - das spart bei vielen kleinen YAMLs einen
     * kompletten Round-Trip pro Datei.
     */
    fun store(
        set: String,
        path: String,
        bytes: ByteArray,
        sha256: String,
        revision: Long,
        serverId: String,
        previous: ResourceRecord?
    ): ResourceRecord {
        val key = ResourceRecord.key(set, path)
        val useGridFs = bytes.size > config.inlineThresholdBytes
        var gridFsId: ObjectId? = null

        if (useGridFs) {
            val options = GridFSUploadOptions()
                .chunkSizeBytes(config.gridFsChunkSizeBytes)
                .metadata(
                    Document("key", key)
                        .append("set", set)
                        .append("path", path)
                        .append("sha256", sha256)
                )
            gridFsId = mongo.gridFs.uploadFromStream(key, ByteArrayInputStream(bytes), options)
        }

        val now = Date()
        val updates = ArrayList<Bson>(12)
        updates += Updates.set(ResourceRecord.F_SET, set)
        updates += Updates.set(ResourceRecord.F_PATH, path)
        updates += Updates.set(ResourceRecord.F_SHA, sha256)
        updates += Updates.set(ResourceRecord.F_SIZE, bytes.size.toLong())
        updates += Updates.set(ResourceRecord.F_STORAGE, if (useGridFs) "gridfs" else "inline")
        updates += Updates.set(ResourceRecord.F_DELETED, false)
        updates += Updates.set(ResourceRecord.F_REVISION, revision)
        updates += Updates.set(ResourceRecord.F_UPDATED_AT, now)
        updates += Updates.set(ResourceRecord.F_UPDATED_BY, serverId)

        if (useGridFs) {
            updates += Updates.set(ResourceRecord.F_GRIDFS, gridFsId)
            updates += Updates.unset(ResourceRecord.F_DATA)
        } else {
            updates += Updates.set(ResourceRecord.F_DATA, Binary(bytes))
            updates += Updates.unset(ResourceRecord.F_GRIDFS)
        }

        mongo.resources.updateOne(
            Filters.eq(ResourceRecord.F_ID, key),
            Updates.combine(updates),
            UpdateOptions().upsert(true)
        )

        // Alte GridFS-Datei erst nach erfolgreichem Update entfernen, sonst koennte ein
        // Consumer zwischendurch eine Referenz auf eine bereits geloeschte Datei lesen.
        val oldGridFsId = previous?.gridFsId
        if (oldGridFsId != null && oldGridFsId != gridFsId) deleteGridFs(oldGridFsId)

        return ResourceRecord(
            key = key,
            set = set,
            path = path,
            sha256 = sha256,
            size = bytes.size.toLong(),
            storage = if (useGridFs) StorageKind.GRIDFS else StorageKind.INLINE,
            gridFsId = gridFsId,
            deleted = false,
            revision = revision,
            updatedAt = now.time,
            updatedBy = serverId
        )
    }

    /**
     * Setzt einen Tombstone. Es wird bewusst nicht hart geloescht, damit Consumer,
     * die offline waren, die Loeschung noch sehen und lokal nachziehen koennen.
     */
    fun markDeleted(record: ResourceRecord, revision: Long, serverId: String) {
        mongo.resources.updateOne(
            Filters.eq(ResourceRecord.F_ID, record.key),
            Updates.combine(
                Updates.set(ResourceRecord.F_DELETED, true),
                Updates.set(ResourceRecord.F_SIZE, 0L),
                Updates.set(ResourceRecord.F_REVISION, revision),
                Updates.set(ResourceRecord.F_UPDATED_AT, Date()),
                Updates.set(ResourceRecord.F_UPDATED_BY, serverId),
                Updates.unset(ResourceRecord.F_DATA),
                Updates.unset(ResourceRecord.F_GRIDFS)
            )
        )
        record.gridFsId?.let { deleteGridFs(it) }
    }

    /** Entfernt Tombstones, die aelter als [olderThanMillis] sind. */
    fun purgeTombstones(olderThanMillis: Long): Long {
        if (olderThanMillis <= 0L) return 0L
        val cutoff = Date(System.currentTimeMillis() - olderThanMillis)
        return mongo.resources.deleteMany(
            Filters.and(
                Filters.eq(ResourceRecord.F_DELETED, true),
                Filters.lt(ResourceRecord.F_UPDATED_AT, cutoff)
            )
        ).deletedCount
    }

    private fun deleteGridFs(id: ObjectId) {
        try {
            mongo.gridFs.delete(id)
        } catch (e: Exception) {
            logger.log(Level.FINE, "GridFS-Datei $id konnte nicht geloescht werden: ${e.message}")
        }
    }

    // -------------------------------------------------------------- Revision

    /**
     * Erhoeht den globalen Revisionszaehler und gibt den neuen Wert zurueck.
     * Ein Push stempelt alle geaenderten Dokumente mit derselben Revision, damit
     * Consumer im Polling-Modus mit einer einzigen Zahl erkennen, ob es etwas Neues gibt.
     */
    fun bumpRevision(): Long {
        val updated = mongo.state.findOneAndUpdate(
            Filters.eq(ResourceRecord.F_ID, REVISION_ID),
            Updates.inc("value", 1L),
            FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)
        )
        return ResourceRecord.longOf(updated?.get("value"), 1L)
    }

    fun currentRevision(): Long {
        val document = mongo.state.find(Filters.eq(ResourceRecord.F_ID, REVISION_ID)).first() ?: return 0L
        return ResourceRecord.longOf(document["value"])
    }

    fun countActiveResources(): Long =
        mongo.resources.countDocuments(Filters.ne(ResourceRecord.F_DELETED, true))

    // ----------------------------------------------------------- Master-Lock

    /**
     * Versucht, den Master-Lock zu uebernehmen bzw. zu verlaengern.
     *
     * Der Lock ist ein einzelnes Dokument mit TTL-Feld `expiresAt`. Uebernommen wird er
     * nur, wenn er entweder uns gehoert oder abgelaufen ist. Damit kann ein zweiter
     * Server mit `main-server: true` nicht unbemerkt mitschreiben.
     */
    fun claimMaster(serverId: String, ttlMillis: Long): MasterClaim {
        return try {
            val now = Date()
            val expires = Date(now.time + ttlMillis)

            val updated = mongo.state.findOneAndUpdate(
                Filters.and(
                    Filters.eq(ResourceRecord.F_ID, MASTER_ID),
                    Filters.or(
                        Filters.eq("serverId", serverId),
                        Filters.lte("expiresAt", now)
                    )
                ),
                Updates.combine(
                    Updates.set("serverId", serverId),
                    Updates.set("heartbeatAt", now),
                    Updates.set("expiresAt", expires)
                ),
                FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
            )
            if (updated != null) return MasterClaim.Acquired

            // Kein passendes Dokument: entweder gibt es noch keinen Lock (dann gewinnt
            // der Insert) oder ein anderer Server haelt ihn (dann schlaegt er fehl).
            try {
                mongo.state.insertOne(
                    Document(ResourceRecord.F_ID, MASTER_ID)
                        .append("serverId", serverId)
                        .append("heartbeatAt", now)
                        .append("expiresAt", expires)
                )
                return MasterClaim.Acquired
            } catch (_: MongoWriteException) {
                // Duplicate Key -> ein anderer Server hat den Lock.
            } catch (_: MongoWriteConcernException) {
                // Ebenfalls als Konflikt behandeln.
            }

            MasterClaim.Conflict(readMaster())
        } catch (e: Exception) {
            MasterClaim.Failed(e)
        }
    }

    fun readMaster(): MasterLockInfo? {
        val document = mongo.state.find(Filters.eq(ResourceRecord.F_ID, MASTER_ID)).first() ?: return null
        val serverId = document.getString("serverId") ?: return null
        return MasterLockInfo(
            serverId = serverId,
            heartbeatAt = document.getDate("heartbeatAt")?.time ?: 0L,
            expiresAt = document.getDate("expiresAt")?.time ?: 0L
        )
    }

    /** Gibt den Lock frei, aber nur wenn er uns gehoert. */
    fun releaseMaster(serverId: String) {
        runCatching {
            mongo.state.deleteOne(
                Filters.and(
                    Filters.eq(ResourceRecord.F_ID, MASTER_ID),
                    Filters.eq("serverId", serverId)
                )
            )
        }
    }

    companion object {
        const val REVISION_ID = "revision"
        const val MASTER_ID = "master"
    }
}
