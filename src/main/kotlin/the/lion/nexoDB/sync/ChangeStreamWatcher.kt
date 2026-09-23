package the.lion.nexoDB.sync

import com.mongodb.MongoCommandException
import com.mongodb.MongoInterruptedException
import com.mongodb.client.model.changestream.ChangeStreamDocument
import com.mongodb.client.model.changestream.FullDocument
import org.bson.BsonDocument
import org.bson.Document
import the.lion.nexoDB.db.MongoManager
import the.lion.nexoDB.db.ResourceRecord
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Lauscht per Change Stream auf Aenderungen an der Metadaten-Collection.
 *
 * Laeuft in einem eigenen Daemon-Thread, weil der Cursor blockiert. Der Callback
 * bekommt die `updatedBy`-server-id des Events (oder null, wenn sie unbekannt ist),
 * damit der SyncService eigene Schreibvorgaenge ignorieren kann.
 *
 * Voraussetzung: MongoDB laeuft als Replica Set oder Sharded Cluster. Auf einem
 * Standalone-mongod gibt es keine Oplog-basierten Change Streams.
 */
class ChangeStreamWatcher(
    private val mongo: MongoManager,
    private val logger: Logger,
    private val onChange: (String?) -> Unit
) : Runnable {

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(this, "NexoDB-ChangeStream").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    override fun run() {
        var resumeToken: BsonDocument? = null
        var backoffMillis = INITIAL_BACKOFF_MILLIS

        while (running) {
            try {
                var stream = mongo.resources.watch()
                    .fullDocument(FullDocument.UPDATE_LOOKUP)
                    .maxAwaitTime(MAX_AWAIT_SECONDS, TimeUnit.SECONDS)
                    .batchSize(64)

                val token = resumeToken
                if (token != null) stream = stream.resumeAfter(token)

                stream.cursor().use { cursor ->
                    backoffMillis = INITIAL_BACKOFF_MILLIS
                    logger.fine("Change Stream ist aktiv.")

                    while (running) {
                        val event: ChangeStreamDocument<Document>? = cursor.tryNext()
                        if (event == null) {
                            // tryNext() kehrt nach maxAwaitTime ohne Event zurueck. Der
                            // Post-Batch-Resume-Token haelt uns trotzdem aktuell, damit ein
                            // Reconnect nicht in altem Oplog wieder aufsetzt.
                            cursor.resumeToken?.let { resumeToken = it }
                            continue
                        }

                        resumeToken = event.resumeToken
                        onChange(event.fullDocument?.getString(ResourceRecord.F_UPDATED_BY))
                    }
                }
            } catch (_: MongoInterruptedException) {
                if (!running) break
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                if (!running) break
            } catch (e: MongoCommandException) {
                if (!running) break
                if (e.errorCode in RESUME_LOST_CODES) {
                    logger.warning(
                        "Change-Stream-Historie ist nicht mehr im Oplog (Code ${e.errorCode}). " +
                            "Es wird ohne Resume-Token neu aufgesetzt und einmal vollstaendig gepullt."
                    )
                    resumeToken = null
                    onChange(null)
                } else {
                    logger.log(Level.WARNING, "Change Stream abgebrochen: ${e.message}")
                }
            } catch (e: Exception) {
                if (!running) break
                logger.log(Level.WARNING, "Change Stream abgebrochen, neuer Versuch in ${backoffMillis / 1000}s.", e)
            }

            if (!running) break

            try {
                Thread.sleep(backoffMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
        }

        logger.fine("Change-Stream-Thread beendet.")
    }

    private companion object {
        const val INITIAL_BACKOFF_MILLIS = 2_000L
        const val MAX_BACKOFF_MILLIS = 60_000L
        const val MAX_AWAIT_SECONDS = 5L

        /** ChangeStreamFatalError, ChangeStreamHistoryLost, NonResumableChangeStreamError. */
        val RESUME_LOST_CODES = setOf(280, 286, 40573)
    }
}
