package the.lion.nexoDB.db

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.WriteConcern
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.gridfs.GridFSBucket
import com.mongodb.client.gridfs.GridFSBuckets
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import org.bson.Document
import org.bson.conversions.Bson
import the.lion.nexoDB.config.NexoDBConfig
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Haelt den MongoClient, die Collections und den GridFS-Bucket.
 *
 * Der Treiber bringt seinen eigenen Connection-Pool mit und ist threadsafe - es wird
 * bewusst genau ein Client fuer die gesamte Plugin-Laufzeit erzeugt. Jeder Aufruf
 * passiert ausserhalb des Main-Threads (siehe SyncService).
 */
class MongoManager(private val config: NexoDBConfig, private val logger: Logger) {

    private var client: MongoClient? = null

    lateinit var database: MongoDatabase
        private set

    /** Metadaten-Collection (ein Dokument pro Datei). */
    lateinit var resources: MongoCollection<Document>
        private set

    /** Kleine Collection fuer Revisionszaehler und Master-Lock. */
    lateinit var state: MongoCollection<Document>
        private set

    lateinit var gridFs: GridFSBucket
        private set

    /** true, wenn das Cluster Change Streams unterstuetzt (Replica Set oder mongos). */
    var changeStreamsSupported: Boolean = false
        private set

    var connected: Boolean = false
        private set

    var topologyDescription: String = "unbekannt"
        private set

    fun connect() {
        val settings = MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(config.mongoUri))
            .applicationName("NexoDB/${config.serverId}")
            .retryWrites(config.retryWrites)
            .retryReads(config.retryReads)
            .applyToConnectionPoolSettings { pool ->
                pool.maxSize(config.maxPoolSize)
                    .minSize(config.minPoolSize)
                    .maxConnectionIdleTime(config.maxConnectionIdleMillis, TimeUnit.MILLISECONDS)
                    .maxWaitTime(config.serverSelectionTimeoutMillis, TimeUnit.MILLISECONDS)
            }
            .applyToSocketSettings { socket ->
                socket.connectTimeout(config.connectTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                    .readTimeout(config.readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            }
            .applyToClusterSettings { cluster ->
                cluster.serverSelectionTimeout(config.serverSelectionTimeoutMillis, TimeUnit.MILLISECONDS)
            }
            .also { builder ->
                // w:majority stellt sicher, dass ein Push erst als erledigt gilt, wenn er
                // die Mehrheit des Replica Sets erreicht hat - sonst koennte ein Failover
                // gerade gepushte Dateien verlieren.
                if (config.writeConcernMajority) builder.writeConcern(WriteConcern.MAJORITY)
            }
            .build()

        val created = MongoClients.create(settings)
        client = created

        database = created.getDatabase(config.databaseName)
        resources = database.getCollection(config.resourcesCollection)
        state = database.getCollection(config.stateCollection)
        gridFs = GridFSBuckets.create(database, config.gridFsBucket)
            .withChunkSizeBytes(config.gridFsChunkSizeBytes)

        detectTopology()
        ensureIndexes()
        connected = true
    }

    private fun detectTopology() {
        val hello: Document = try {
            database.runCommand(Document("hello", 1))
        } catch (_: Exception) {
            // Sehr alte Server kennen "hello" noch nicht.
            database.runCommand(Document("isMaster", 1))
        }

        val replicaSetName = hello.getString("setName")
        val isMongos = hello.getString("msg") == "isdbgrid"

        changeStreamsSupported = replicaSetName != null || isMongos
        topologyDescription = when {
            isMongos -> "sharded cluster (mongos)"
            replicaSetName != null -> "replica set '$replicaSetName'"
            else -> "standalone (keine Change Streams)"
        }
    }

    private fun ensureIndexes() {
        // _id ist bereits "<set>:<path>" und damit eindeutig. Der zusaetzliche Index
        // beschleunigt set-weise Abfragen und schuetzt gegen Fremd-Schreiber.
        createIndex(Indexes.ascending(ResourceRecord.F_SET, ResourceRecord.F_PATH), "nexodb_set_path", unique = true)

        // Polling-Fallback liest "was ist neuer als meine Revision".
        createIndex(Indexes.ascending(ResourceRecord.F_REVISION), "nexodb_rev")

        // Aufraeumen alter Tombstones.
        createIndex(Indexes.ascending(ResourceRecord.F_DELETED, ResourceRecord.F_UPDATED_AT), "nexodb_deleted_updated")

        // Diagnose: "wer hat zuletzt geschrieben".
        createIndex(Indexes.descending(ResourceRecord.F_UPDATED_AT), "nexodb_updated_at")

        // GridFS-Dateien werden ueber den Key gesucht, wenn verwaiste Chunks aufgeraeumt werden.
        try {
            database.getCollection("${config.gridFsBucket}.files")
                .createIndex(Indexes.ascending("metadata.key"), IndexOptions().name("nexodb_gridfs_key"))
        } catch (e: Exception) {
            logger.log(Level.FINE, "GridFS-Index konnte nicht angelegt werden: ${e.message}")
        }
    }

    private fun createIndex(keys: Bson, name: String, unique: Boolean = false) {
        try {
            resources.createIndex(keys, IndexOptions().name(name).unique(unique))
        } catch (e: Exception) {
            // Passiert z.B., wenn der Index bereits mit anderen Optionen existiert.
            logger.log(Level.FINE, "Index '$name' konnte nicht angelegt werden: ${e.message}")
        }
    }

    fun close() {
        connected = false
        runCatching { client?.close() }
        client = null
    }
}
