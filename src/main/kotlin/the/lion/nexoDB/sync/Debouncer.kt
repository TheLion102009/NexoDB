package the.lion.nexoDB.sync

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Fuehrt [action] erst aus, wenn seit dem letzten [trigger] die eingestellte Zeit
 * vergangen ist.
 *
 * Ein Master-Push schreibt pro Datei ein Dokument - bei 800 geaenderten Texturen
 * kommen also 800 Change-Stream-Events an. Ohne Debouncing wuerde jeder Consumer
 * 800 Mal pullen und Nexo 800 Mal neu laden. Mit Debouncing wird genau einmal
 * synchronisiert, sobald der Push durch ist.
 */
class Debouncer(
    private val scheduler: ScheduledExecutorService,
    private val delayMillis: Long,
    private val action: () -> Unit
) {

    private val lock = Any()
    private var pending: ScheduledFuture<*>? = null

    fun trigger() {
        synchronized(lock) {
            pending?.cancel(false)
            pending = scheduler.schedule(
                Runnable {
                    synchronized(lock) { pending = null }
                    action()
                },
                delayMillis.coerceAtLeast(1L),
                TimeUnit.MILLISECONDS
            )
        }
    }

    fun cancel() {
        synchronized(lock) {
            pending?.cancel(false)
            pending = null
        }
    }
}
