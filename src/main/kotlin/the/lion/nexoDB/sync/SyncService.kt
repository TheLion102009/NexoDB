package the.lion.nexoDB.sync

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import the.lion.nexoDB.config.NexoDBConfig
import the.lion.nexoDB.config.SyncMode
import the.lion.nexoDB.db.MasterClaim
import the.lion.nexoDB.db.MongoManager
import the.lion.nexoDB.db.ResourceRecord
import the.lion.nexoDB.db.ResourceRepository
import the.lion.nexoDB.nexo.NexoBridge
import the.lion.nexoDB.util.FileOps
import the.lion.nexoDB.util.HashCache
import the.lion.nexoDB.util.Hashing
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level

class SyncResult(
    val success: Boolean,
    val message: String,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deleted: Int = 0,
    val unchanged: Int = 0,
    val durationMillis: Long = 0L
)

class SyncStatus(
    val connected: Boolean,
    val topology: String,
    val role: String,
    val serverId: String,
    val masterAuthorized: Boolean,
    val masterHolder: String?,
    val mode: SyncMode,
    val revision: Long,
    val remoteResources: Long,
    val lastPushAt: Long,
    val lastPullAt: Long,
    val lastError: String?,
    val cachedHashes: Long
)

/**
 * Orchestriert den gesamten Abgleich.
 *
 * Threading-Modell:
 *  - Ein einzelner Worker-Thread fuehrt alle Push-/Pull-Vorgaenge aus. Dadurch koennen
 *    sich zwei Syncs niemals ueberlappen, ganz ohne zusaetzliches Locking.
 *  - Ein Scheduler-Thread uebernimmt Polling, Master-Heartbeat und Debouncing.
 *  - Ein Change-Stream-Thread blockiert auf dem Mongo-Cursor.
 *  - Der Main-Thread wird ausschliesslich fuer `nexo reload` und Chat-Ausgaben benutzt.
 */
class SyncService(
    private val plugin: Plugin,
    private val config: NexoDBConfig,
    private val serverRoot: Path,
    private val mongo: MongoManager,
    private val repository: ResourceRepository,
    private val bridge: NexoBridge
) {

    private val logger = plugin.logger
    private val hashCache = HashCache(config.hashCacheSize)

    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor(threadFactory("NexoDB-Sync"))
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(threadFactory("NexoDB-Scheduler"))

    private val shuttingDown = AtomicBoolean(false)
    private val knownRevision = AtomicLong(0L)
    private val syncing = AtomicBoolean(false)

    @Volatile
    var ready: Boolean = false
        private set

    /** true, wenn dieser Server als Master schreiben darf (Flag gesetzt UND Lock gehalten). */
    @Volatile
    var masterAuthorized: Boolean = false
        private set

    @Volatile
    var masterHolder: String? = null
        private set

    @Volatile
    var activeMode: SyncMode = SyncMode.POLLING
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var lastPushAt: Long = 0L

    @Volatile
    private var lastPullAt: Long = 0L

    @Volatile
    private var remoteResourceCount: Long = 0L

    private var watcher: ChangeStreamWatcher? = null
    private var pollTask: ScheduledFuture<*>? = null
    private var heartbeatTask: ScheduledFuture<*>? = null
    private val pullDebouncer = Debouncer(scheduler, config.debounceSeconds.coerceAtLeast(0L) * 1000L) {
        submitInternal("Change Stream") { runPull(false, "change-stream") }
    }

    // ------------------------------------------------------------- Lifecycle

    fun start() {
        worker.execute { bootstrap() }
    }

    private fun bootstrap() {
        try {
            mongo.connect()
            ready = true
            lastError = null
            logger.info("MongoDB verbunden - ${mongo.topologyDescription}, Datenbank '${config.databaseName}'.")
        } catch (e: Exception) {
            ready = false
            lastError = e.message ?: e.javaClass.simpleName
            logger.log(Level.SEVERE, "Verbindung zu MongoDB fehlgeschlagen - neuer Versuch in ${RECONNECT_SECONDS}s.", e)
            scheduleReconnect()
            return
        }

        if (config.isMaster) {
            setupMaster()
        } else {
            masterAuthorized = false
            masterHolder = runCatching { repository.readMaster()?.serverId }.getOrNull()
        }

        try {
            when {
                config.isMaster && config.pushOnStart && masterAuthorized ->
                    logResult("Start-Push", runPush(false, "start"))

                !config.isMaster && config.pullOnStart ->
                    logResult("Start-Pull", runPull(false, "start"))

                else -> knownRevision.set(repository.currentRevision())
            }
        } catch (e: Exception) {
            lastError = e.message
            logger.log(Level.SEVERE, "Initialer Sync fehlgeschlagen.", e)
        }

        startWatchers()
    }

    private fun scheduleReconnect() {
        if (shuttingDown.get()) return
        runCatching {
            scheduler.schedule(
                Runnable { worker.execute { if (!shuttingDown.get() && !ready) bootstrap() } },
                RECONNECT_SECONDS,
                TimeUnit.SECONDS
            )
        }
    }

    private fun startWatchers() {
        if (shuttingDown.get()) return

        // Der Master pullt nicht - er ist die Quelle der Wahrheit. Damit gibt es
        // keinen Weg, auf dem DB-Inhalte lokale Aenderungen ueberschreiben koennten.
        if (config.isMaster && !config.allowMasterPull) {
            activeMode = SyncMode.POLLING
            logger.info("Master-Modus: es wird nicht gepullt, nur gepusht.")
            return
        }

        val useChangeStream = when (config.mode) {
            SyncMode.POLLING -> false
            SyncMode.AUTO -> mongo.changeStreamsSupported
            SyncMode.CHANGE_STREAM -> {
                if (mongo.changeStreamsSupported) {
                    true
                } else {
                    logger.warning(
                        "sync.mode ist 'change-stream', aber die Datenbank laeuft als ${mongo.topologyDescription}. " +
                            "Change Streams brauchen ein Replica Set - es wird auf Polling zurueckgefallen."
                    )
                    false
                }
            }
        }

        activeMode = if (useChangeStream) SyncMode.CHANGE_STREAM else SyncMode.POLLING

        if (useChangeStream) {
            watcher = ChangeStreamWatcher(mongo, logger) { updatedBy -> onRemoteChange(updatedBy) }
                .also { it.start() }
            logger.info("Echtzeit-Sync aktiv (Change Streams).")
        } else {
            logger.info("Sync aktiv (Polling alle ${config.pollIntervalSeconds}s).")
        }

        // Polling laeuft auch mit Change Streams mit - deutlich seltener, aber es faengt
        // den Fall ab, dass der Stream unbemerkt haengt.
        val intervalSeconds =
            if (useChangeStream) (config.pollIntervalSeconds * 6).coerceAtLeast(60L)
            else config.pollIntervalSeconds.coerceAtLeast(1L)

        pollTask = scheduler.scheduleWithFixedDelay(
            Runnable { poll() },
            intervalSeconds,
            intervalSeconds,
            TimeUnit.SECONDS
        )
    }

    fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return

        pullDebouncer.cancel()
        watcher?.stop()
        pollTask?.cancel(false)
        heartbeatTask?.cancel(false)

        if (config.isMaster && masterAuthorized) {
            runCatching { worker.execute { repository.releaseMaster(config.serverId) } }
        }

        scheduler.shutdownNow()
        worker.shutdown()
        runCatching {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow()
        }
        mongo.close()
        ready = false
    }

    // ------------------------------------------------------------ Master-Lock

    private fun setupMaster() {
        applyClaim(repository.claimMaster(config.serverId, config.masterLockTtlMillis), initial = true)

        val interval = config.masterHeartbeatSeconds.coerceAtLeast(5L)
        heartbeatTask = scheduler.scheduleWithFixedDelay(
            Runnable {
                if (!ready || shuttingDown.get()) return@Runnable
                worker.execute {
                    runCatching { applyClaim(repository.claimMaster(config.serverId, config.masterLockTtlMillis), initial = false) }
                }
            },
            interval,
            interval,
            TimeUnit.SECONDS
        )
    }

    private fun applyClaim(claim: MasterClaim, initial: Boolean) {
        when (claim) {
            is MasterClaim.Acquired -> {
                val wasAuthorized = masterAuthorized
                masterAuthorized = true
                masterHolder = config.serverId
                if (initial) {
                    logger.info("Master-Lock gehalten von '${config.serverId}' - dieser Server darf pushen.")
                } else if (!wasAuthorized) {
                    logger.info("Master-Lock (wieder) uebernommen - Pushes sind wieder erlaubt.")
                }
            }

            is MasterClaim.Conflict -> {
                val holder = claim.holder?.serverId
                masterHolder = holder
                masterAuthorized = !config.enforceSingleMaster
                logger.severe(
                    "=========================== NexoDB: DOPPELTER MASTER ===========================\n" +
                        " Dieser Server ('${config.serverId}') steht auf main-server: true,\n" +
                        " aber der Master-Lock gehoert bereits '${holder ?: "unbekannt"}'.\n" +
                        (if (config.enforceSingleMaster)
                            " -> Pushes werden auf diesem Server BLOCKIERT (server.enforce-single-master: true).\n"
                        else
                            " -> enforce-single-master ist aus: Pushes bleiben erlaubt. Das kann Daten ueberschreiben!\n") +
                        " Bitte auf genau EINEM Server main-server: true setzen.\n" +
                        "================================================================================"
                )
            }

            is MasterClaim.Failed -> {
                // Datenbank kurz nicht erreichbar: den bisherigen Zustand beibehalten,
                // damit ein Netzwerk-Hickser den Master nicht dauerhaft entmachtet.
                lastError = claim.error.message
                logger.log(Level.WARNING, "Master-Lock konnte nicht aktualisiert werden: ${claim.error.message}")
            }
        }
    }

    // -------------------------------------------------------------- Triggers

    private fun onRemoteChange(updatedBy: String?) {
        if (updatedBy != null && updatedBy == config.serverId) return
        pullDebouncer.trigger()
    }

    private fun poll() {
        if (!ready || shuttingDown.get()) return
        worker.execute {
            try {
                val revision = repository.currentRevision()
                if (revision > knownRevision.get()) {
                    logResult("Polling", runPull(false, "polling"))
                }
            } catch (e: Exception) {
                lastError = e.message
                logger.log(Level.WARNING, "Polling fehlgeschlagen: ${e.message}")
            }
        }
    }

    /** Wird vom NexoBridge aufgerufen, wenn jemand `nexo reload` ausgefuehrt hat. */
    fun onNexoReloadDetected() {
        if (!config.isMaster || !config.autoPushOnNexoReload) return
        submitInternal("Auto-Push nach nexo reload") { runPush(false, "nexo-reload") }
    }

    // ------------------------------------------------------------- Public API

    fun requestPush(force: Boolean, callback: (SyncResult) -> Unit) {
        submit(callback) { runPush(force, "command") }
    }

    fun requestPull(force: Boolean, callback: (SyncResult) -> Unit) {
        submit(callback) { runPull(force, "command") }
    }

    /** Grund, warum dieser Server nicht pushen darf - oder null, wenn er darf. */
    fun pushRejectionReason(): String? = when {
        !config.isMaster ->
            "Dieser Server ist kein Master (server.main-server: false). Push ist nur auf dem Master erlaubt."

        !ready -> "Keine Verbindung zur MongoDB${lastError?.let { " ($it)" } ?: ""}."

        !masterAuthorized ->
            "Der Master-Lock wird von '${masterHolder ?: "einem anderen Server"}' gehalten - Push blockiert."

        else -> null
    }

    /** Grund, warum dieser Server nicht pullen darf - oder null, wenn er darf. */
    fun pullRejectionReason(): String? = when {
        config.isMaster && !config.allowMasterPull ->
            "Dieser Server ist der Master - er ist die Quelle der Wahrheit und pullt nicht. " +
                "(Zum Erzwingen: sync.allow-master-pull: true)"

        !ready -> "Keine Verbindung zur MongoDB${lastError?.let { " ($it)" } ?: ""}."

        else -> null
    }

    fun status(): SyncStatus = SyncStatus(
        connected = ready,
        topology = if (ready) mongo.topologyDescription else "nicht verbunden",
        role = if (config.isMaster) "MASTER" else "CONSUMER",
        serverId = config.serverId,
        masterAuthorized = masterAuthorized,
        masterHolder = masterHolder,
        mode = activeMode,
        revision = knownRevision.get(),
        remoteResources = remoteResourceCount,
        lastPushAt = lastPushAt,
        lastPullAt = lastPullAt,
        lastError = lastError,
        cachedHashes = hashCache.size()
    )

    // ----------------------------------------------------------------- Push

    private fun runPush(force: Boolean, reason: String): SyncResult {
        val rejection = pushRejectionReason()
        if (rejection != null) return SyncResult(false, rejection)

        if (!syncing.compareAndSet(false, true)) {
            return SyncResult(false, "Es laeuft bereits ein Sync-Vorgang.")
        }

        val startedAt = System.currentTimeMillis()
        try {
            val remote = repository.loadIndex()
            val pending = ArrayList<PendingUpload>()
            val seen = HashSet<String>()
            var unchanged = 0
            var skipped = 0

            for (set in config.syncSets) {
                val root = resolvePath(set.sourcePath)
                if (!Files.isDirectory(root)) {
                    logger.warning("sync-set '${set.id}': Quellordner '$root' existiert nicht - wird uebersprungen.")
                    continue
                }

                for ((relative, file) in FileOps.scan(root, set.includes, set.excludes)) {
                    val key = ResourceRecord.key(set.id, relative)
                    seen += key

                    val size = Files.size(file)
                    if (size > config.maxFileSizeBytes) {
                        logger.warning("'$relative' ist ${size / 1024} KB gross und ueberschreitet performance.max-file-size-bytes - uebersprungen.")
                        skipped++
                        continue
                    }

                    val hash = hashCache.hashOf(key, file)
                    val existing = remote[key]
                    val identical = existing != null && !existing.deleted &&
                        existing.sha256 == hash && existing.size == size

                    if (!force && identical) {
                        unchanged++
                        continue
                    }
                    pending += PendingUpload(set.id, relative, file, existing)
                }
            }

            val setIds = config.syncSets.mapTo(HashSet()) { it.id }
            val tombstones = if (config.markRemoteOrphansDeleted) {
                remote.values.filter { !it.deleted && it.set in setIds && it.key !in seen }
            } else {
                emptyList()
            }

            if (pending.isEmpty() && tombstones.isEmpty()) {
                knownRevision.set(repository.currentRevision())
                remoteResourceCount = repository.countActiveResources()
                lastPushAt = System.currentTimeMillis()
                return SyncResult(
                    success = true,
                    message = "Nichts zu tun - alle $unchanged Dateien sind bereits aktuell.",
                    unchanged = unchanged,
                    durationMillis = System.currentTimeMillis() - startedAt
                )
            }

            // Alle Dokumente eines Pushes bekommen dieselbe Revision. Consumer erkennen
            // damit ueber eine einzige Zahl, ob sie etwas verpasst haben.
            val revision = repository.bumpRevision()

            var uploaded = 0
            for (upload in pending) {
                val bytes = Files.readAllBytes(upload.file)
                // Frisch hashen: die Datei koennte sich seit dem Scan geaendert haben.
                val hash = Hashing.sha256(bytes)
                repository.store(upload.set, upload.path, bytes, hash, revision, config.serverId, upload.previous)
                hashCache.remember(ResourceRecord.key(upload.set, upload.path), upload.file, hash)
                uploaded++
            }

            var deleted = 0
            for (record in tombstones) {
                repository.markDeleted(record, revision, config.serverId)
                hashCache.invalidate(record.key)
                deleted++
            }

            if (config.tombstoneRetentionDays > 0) {
                val purged = repository.purgeTombstones(config.tombstoneRetentionDays * MILLIS_PER_DAY)
                if (purged > 0) logger.info("$purged alte Loesch-Markierungen aufgeraeumt.")
            }

            knownRevision.set(revision)
            remoteResourceCount = repository.countActiveResources()
            lastPushAt = System.currentTimeMillis()
            lastError = null

            val skippedNote = if (skipped > 0) ", $skipped uebersprungen" else ""
            return SyncResult(
                success = true,
                message = "Push (rev $revision): $uploaded hochgeladen, $deleted geloescht, $unchanged unveraendert$skippedNote.",
                uploaded = uploaded,
                deleted = deleted,
                unchanged = unchanged,
                durationMillis = System.currentTimeMillis() - startedAt
            )
        } catch (e: Exception) {
            lastError = e.message
            logger.log(Level.SEVERE, "Push fehlgeschlagen (Ausloeser: $reason).", e)
            return SyncResult(false, "Push fehlgeschlagen: ${e.message}")
        } finally {
            syncing.set(false)
        }
    }

    // ----------------------------------------------------------------- Pull

    private fun runPull(force: Boolean, reason: String): SyncResult {
        val rejection = pullRejectionReason()
        if (rejection != null) return SyncResult(false, rejection)

        if (!syncing.compareAndSet(false, true)) {
            return SyncResult(false, "Es laeuft bereits ein Sync-Vorgang.")
        }

        val startedAt = System.currentTimeMillis()
        try {
            val remote = repository.loadIndex()
            var written = 0
            var removed = 0
            var unchanged = 0

            for (set in config.syncSets) {
                val root = resolvePath(set.targetPath)
                val expected = HashSet<String>()

                for (record in remote.values) {
                    if (record.set != set.id) continue

                    val target = FileOps.safeResolve(root, record.path)
                    if (target == null) {
                        logger.warning("Unsicherer Pfad in der Datenbank wird ignoriert: '${record.key}'.")
                        continue
                    }

                    if (record.deleted) {
                        if (Files.exists(target)) {
                            FileOps.deleteIfExists(target)
                            FileOps.pruneEmptyDirs(root, target)
                            hashCache.invalidate(record.key)
                            removed++
                        }
                        continue
                    }

                    expected += record.path

                    if (!force && Files.isRegularFile(target) &&
                        Files.size(target) == record.size &&
                        hashCache.hashOf(record.key, target) == record.sha256
                    ) {
                        unchanged++
                        continue
                    }

                    val bytes = repository.loadContent(record)
                    FileOps.atomicWrite(target, bytes)
                    hashCache.remember(record.key, target, record.sha256)
                    written++
                }

                // Lokale Dateien entfernen, die es in der DB nicht gibt. Nur fuer Ordner,
                // die NexoDB exklusiv gehoeren (purge-local-orphans in der config).
                if (set.purgeLocalOrphans && Files.isDirectory(root)) {
                    for ((relative, file) in FileOps.scan(root, set.includes, set.excludes)) {
                        if (relative in expected) continue
                        FileOps.deleteIfExists(file)
                        FileOps.pruneEmptyDirs(root, file)
                        hashCache.invalidate(ResourceRecord.key(set.id, relative))
                        removed++
                    }
                }
            }

            knownRevision.set(repository.currentRevision())
            remoteResourceCount = repository.countActiveResources()
            lastPullAt = System.currentTimeMillis()
            lastError = null

            val changed = written + removed
            if (changed > 0 && config.reloadAfterPull) {
                bridge.reloadNexo()
            }

            return SyncResult(
                success = true,
                message = if (changed == 0) {
                    "Nichts zu tun - alle $unchanged Dateien sind bereits aktuell."
                } else {
                    "Pull (rev ${knownRevision.get()}): $written geschrieben, $removed entfernt, $unchanged unveraendert."
                },
                downloaded = written,
                deleted = removed,
                unchanged = unchanged,
                durationMillis = System.currentTimeMillis() - startedAt
            )
        } catch (e: Exception) {
            lastError = e.message
            logger.log(Level.SEVERE, "Pull fehlgeschlagen (Ausloeser: $reason).", e)
            return SyncResult(false, "Pull fehlgeschlagen: ${e.message}")
        } finally {
            syncing.set(false)
        }
    }

    // ---------------------------------------------------------------- Intern

    private class PendingUpload(
        val set: String,
        val path: String,
        val file: Path,
        val previous: ResourceRecord?
    )

    private fun resolvePath(raw: String): Path {
        val candidate = Paths.get(raw.replace('\\', '/'))
        return if (candidate.isAbsolute) candidate.normalize()
        else serverRoot.resolve(candidate).normalize()
    }

    private fun submit(callback: (SyncResult) -> Unit, action: () -> SyncResult) {
        if (shuttingDown.get()) {
            callback(SyncResult(false, "Das Plugin faehrt gerade herunter."))
            return
        }

        try {
            worker.execute {
                val result = try {
                    action()
                } catch (e: Exception) {
                    logger.log(Level.SEVERE, "Sync-Vorgang abgebrochen.", e)
                    SyncResult(false, "Unerwarteter Fehler: ${e.message}")
                }
                deliver(callback, result)
            }
        } catch (_: RejectedExecutionException) {
            callback(SyncResult(false, "Der Sync-Worker nimmt keine Aufgaben mehr an."))
        }
    }

    private fun submitInternal(label: String, action: () -> SyncResult) {
        if (shuttingDown.get()) return
        try {
            worker.execute {
                val result = try {
                    action()
                } catch (e: Exception) {
                    logger.log(Level.SEVERE, "$label abgebrochen.", e)
                    SyncResult(false, "Unerwarteter Fehler: ${e.message}")
                }
                logResult(label, result)
            }
        } catch (_: RejectedExecutionException) {
            // Shutdown laeuft - nichts zu tun.
        }
    }

    private fun logResult(label: String, result: SyncResult) {
        val text = "$label: ${result.message} (${result.durationMillis} ms)"
        if (result.success) {
            val interesting = result.uploaded + result.downloaded + result.deleted > 0
            if (interesting || config.verbose) logger.info(text) else logger.fine(text)
        } else {
            logger.warning(text)
        }
    }

    private fun deliver(callback: (SyncResult) -> Unit, result: SyncResult) {
        if (!plugin.isEnabled) return
        runCatching {
            Bukkit.getScheduler().runTask(plugin, Runnable { callback(result) })
        }
    }

    private fun threadFactory(name: String) = ThreadFactory { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }

    private companion object {
        const val RECONNECT_SECONDS = 30L
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
