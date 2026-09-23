package the.lion.nexoDB.config

import org.bukkit.configuration.file.FileConfiguration
import the.lion.nexoDB.util.Glob

enum class SyncMode {
    /** Change Streams verwenden, wenn das Cluster sie unterstuetzt, sonst Polling. */
    AUTO,

    /** Change Streams erzwingen (braucht Replica Set oder Sharded Cluster). */
    CHANGE_STREAM,

    /** Immer pollen - funktioniert auch auf einem Standalone-mongod. */
    POLLING;

    companion object {
        fun parse(raw: String?): SyncMode = when (raw?.lowercase()?.replace('-', '_')?.trim()) {
            "change_stream", "changestream", "stream", "changestreams" -> CHANGE_STREAM
            "polling", "poll" -> POLLING
            else -> AUTO
        }
    }
}

/**
 * Ein Satz von Dateien, der zwischen Servern synchron gehalten wird.
 *
 * [sourcePath] ist das Verzeichnis, aus dem der Master liest.
 * [targetPath] ist das Verzeichnis, in das Consumer schreiben.
 * Beide sind relativ zum Server-Root (der Ordner, in dem `plugins/` liegt);
 * absolute Pfade werden ebenfalls akzeptiert.
 */
class SyncSet(
    val id: String,
    val sourcePath: String,
    val targetPath: String,
    val includePatterns: List<String>,
    val excludePatterns: List<String>,
    /**
     * Wenn true, loescht ein Pull lokale Dateien, die es in der Datenbank nicht (mehr) gibt.
     * Nur fuer Ordner aktivieren, die NexoDB exklusiv gehoeren (z.B. external_packs/<name>).
     */
    val purgeLocalOrphans: Boolean
) {
    val includes: List<Glob> = Glob.compileAll(includePatterns)
    val excludes: List<Glob> = Glob.compileAll(excludePatterns)
}

class NexoDBConfig private constructor(
    // --- Server-Identitaet -------------------------------------------------
    val serverId: String,
    val isMaster: Boolean,
    val enforceSingleMaster: Boolean,
    val masterLockTtlSeconds: Long,
    val masterHeartbeatSeconds: Long,

    // --- MongoDB -----------------------------------------------------------
    val mongoUri: String,
    val databaseName: String,
    val resourcesCollection: String,
    val stateCollection: String,
    val gridFsBucket: String,
    val maxPoolSize: Int,
    val minPoolSize: Int,
    val maxConnectionIdleMillis: Long,
    val connectTimeoutMillis: Int,
    val readTimeoutMillis: Int,
    val serverSelectionTimeoutMillis: Long,
    val retryWrites: Boolean,
    val retryReads: Boolean,
    val writeConcernMajority: Boolean,

    // --- Sync --------------------------------------------------------------
    val mode: SyncMode,
    val pollIntervalSeconds: Long,
    val debounceSeconds: Long,
    val pullOnStart: Boolean,
    val pushOnStart: Boolean,
    val autoPushOnNexoReload: Boolean,
    val autoPushDelayTicks: Long,
    val reloadAfterPull: Boolean,
    val reloadCommand: String,
    val reloadTriggers: List<String>,
    val reloadSuppressSeconds: Long,
    val nexoEventClasses: List<String>,
    val markRemoteOrphansDeleted: Boolean,
    val allowMasterPull: Boolean,
    val tombstoneRetentionDays: Long,

    // --- Performance / Limits ---------------------------------------------
    val inlineThresholdBytes: Int,
    val gridFsChunkSizeBytes: Int,
    val maxFileSizeBytes: Long,
    val hashCacheSize: Long,
    val verbose: Boolean,

    val syncSets: List<SyncSet>
) {

    val masterLockTtlMillis: Long get() = masterLockTtlSeconds * 1000L
    val reloadSuppressMillis: Long get() = reloadSuppressSeconds * 1000L

    /** Gibt eine Liste von Problemen zurueck; leer bedeutet "Konfiguration ist brauchbar". */
    fun validate(): List<String> {
        val problems = ArrayList<String>()

        if (serverId.isBlank()) {
            problems += "server.server-id darf nicht leer sein."
        }
        if (serverId.equals("CHANGE-ME", ignoreCase = true)) {
            problems += "server.server-id steht noch auf dem Platzhalter 'CHANGE-ME'."
        }
        if (mongoUri.isBlank()) {
            problems += "mongodb.uri darf nicht leer sein."
        }
        if (databaseName.isBlank()) {
            problems += "mongodb.database darf nicht leer sein."
        }
        if (syncSets.isEmpty()) {
            problems += "Es ist kein einziges sync-sets-Element konfiguriert - es gibt nichts zu synchronisieren."
        }

        val duplicates = syncSets.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            problems += "Doppelte sync-sets-IDs: ${duplicates.joinToString(", ")}"
        }
        syncSets.filter { it.id.contains(':') }.forEach {
            problems += "sync-set-ID '${it.id}' darf keinen Doppelpunkt enthalten (wird als Key-Trenner benutzt)."
        }

        if (inlineThresholdBytes > 15 * 1024 * 1024) {
            problems += "sync.inline-threshold-bytes ist groesser als 15 MB - BSON-Dokumente sind auf 16 MB begrenzt."
        }
        if (pollIntervalSeconds < 1) {
            problems += "sync.poll-interval-seconds muss mindestens 1 sein."
        }

        return problems
    }

    companion object {

        fun load(config: FileConfiguration): NexoDBConfig {
            return NexoDBConfig(
                serverId = config.getString("server.server-id", "CHANGE-ME")!!.trim(),
                isMaster = config.getBoolean("server.main-server", false),
                enforceSingleMaster = config.getBoolean("server.enforce-single-master", true),
                masterLockTtlSeconds = config.getLong("server.master-lock-ttl-seconds", 90L),
                masterHeartbeatSeconds = config.getLong("server.master-heartbeat-seconds", 30L),

                mongoUri = config.getString("mongodb.uri", "")!!.trim(),
                databaseName = config.getString("mongodb.database", "nexodb")!!.trim(),
                resourcesCollection = config.getString("mongodb.resources-collection", "nexo_resources")!!.trim(),
                stateCollection = config.getString("mongodb.state-collection", "nexo_sync_state")!!.trim(),
                gridFsBucket = config.getString("mongodb.gridfs-bucket", "nexo_files")!!.trim(),
                maxPoolSize = config.getInt("mongodb.pool.max-size", 20),
                minPoolSize = config.getInt("mongodb.pool.min-size", 2),
                maxConnectionIdleMillis = config.getLong("mongodb.pool.max-idle-millis", 60_000L),
                connectTimeoutMillis = config.getInt("mongodb.timeouts.connect-millis", 8_000),
                readTimeoutMillis = config.getInt("mongodb.timeouts.read-millis", 30_000),
                serverSelectionTimeoutMillis = config.getLong("mongodb.timeouts.server-selection-millis", 10_000L),
                retryWrites = config.getBoolean("mongodb.retry-writes", true),
                retryReads = config.getBoolean("mongodb.retry-reads", true),
                writeConcernMajority = config.getBoolean("mongodb.write-concern-majority", true),

                mode = SyncMode.parse(config.getString("sync.mode", "auto")),
                pollIntervalSeconds = config.getLong("sync.poll-interval-seconds", 20L),
                debounceSeconds = config.getLong("sync.reload-debounce-seconds", 5L),
                pullOnStart = config.getBoolean("sync.pull-on-start", true),
                pushOnStart = config.getBoolean("sync.push-on-start", false),
                autoPushOnNexoReload = config.getBoolean("sync.auto-push-on-nexo-reload", true),
                autoPushDelayTicks = config.getLong("sync.auto-push-delay-ticks", 100L),
                reloadAfterPull = config.getBoolean("sync.reload-after-pull", true),
                reloadCommand = config.getString("sync.reload-command", "nexo reload all")!!.trim(),
                reloadTriggers = nonEmptyStringList(config.getStringList("sync.reload-triggers"), listOf("nexo reload")),
                reloadSuppressSeconds = config.getLong("sync.reload-suppress-seconds", 20L),
                nexoEventClasses = nonEmptyStringList(
                    config.getStringList("sync.nexo-event-classes"),
                    listOf("com.nexomc.nexo.api.events.NexoItemsLoadedEvent")
                ),
                markRemoteOrphansDeleted = config.getBoolean("sync.mark-remote-orphans-deleted", true),
                allowMasterPull = config.getBoolean("sync.allow-master-pull", false),
                tombstoneRetentionDays = config.getLong("sync.tombstone-retention-days", 14L),

                inlineThresholdBytes = config.getInt("performance.inline-threshold-bytes", 262_144),
                gridFsChunkSizeBytes = config.getInt("performance.gridfs-chunk-size-bytes", 1_048_576),
                maxFileSizeBytes = config.getLong("performance.max-file-size-bytes", 64L * 1024 * 1024),
                hashCacheSize = config.getLong("performance.hash-cache-size", 50_000L),
                verbose = config.getBoolean("performance.verbose-logging", false),

                syncSets = readSyncSets(config)
            )
        }

        private fun readSyncSets(config: FileConfiguration): List<SyncSet> {
            val result = ArrayList<SyncSet>()
            for (raw in config.getMapList("sync-sets")) {
                // getMapList() liefert star-projizierte Maps; deshalb erst auf
                // String-Keys normalisieren, bevor darauf zugegriffen wird.
                val entry = HashMap<String, Any?>()
                for ((key, value) in raw) {
                    if (key != null) entry[key.toString()] = value
                }

                val id = entry["id"]?.toString()?.trim().orEmpty()
                val source = entry["source-path"]?.toString()?.trim().orEmpty()
                if (id.isEmpty() || source.isEmpty()) continue

                val target = entry["target-path"]?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: source
                result += SyncSet(
                    id = id,
                    sourcePath = source,
                    targetPath = target,
                    includePatterns = stringList(entry["includes"]),
                    excludePatterns = stringList(entry["excludes"]),
                    purgeLocalOrphans = entry["purge-local-orphans"] as? Boolean ?: false
                )
            }
            return result
        }

        private fun stringList(value: Any?): List<String> = when (value) {
            null -> emptyList()
            is String -> listOf(value)
            is List<*> -> value.filterNotNull().map { it.toString() }
            else -> emptyList()
        }

        private fun nonEmptyStringList(value: List<String>, fallback: List<String>): List<String> =
            value.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { fallback }
    }
}
