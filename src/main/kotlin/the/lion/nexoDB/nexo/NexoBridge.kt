package the.lion.nexoDB.nexo

import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.server.ServerCommandEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import the.lion.nexoDB.config.NexoDBConfig
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level

/**
 * Anbindung an Nexo.
 *
 * Bewusst komplett ohne Compile-Time-Abhaengigkeit zu Nexo:
 *  - Der Reload wird als Konsolen-Command abgesetzt (`nexo reload all`).
 *  - Die Erkennung eines fremden Reloads laeuft ueber [ServerCommandEvent] /
 *    [PlayerCommandPreprocessEvent] plus - falls vorhanden - ueber die Nexo-API-Events,
 *    die per Reflection registriert werden.
 *
 * Dadurch baut und laedt das Plugin auch dann, wenn Nexo seine API umbenennt.
 */
class NexoBridge(private val plugin: Plugin, private val config: NexoDBConfig) : Listener {

    /** Bis zu diesem Zeitpunkt werden Reload-Events ignoriert (unser eigener Reload). */
    private val suppressUntil = AtomicLong(0L)

    @Volatile
    private var onExternalReload: (() -> Unit)? = null

    val nexoInstalled: Boolean
        get() = Bukkit.getPluginManager().getPlugin(NEXO_PLUGIN_NAME) != null

    fun registerHooks(callback: () -> Unit) {
        onExternalReload = callback
        Bukkit.getPluginManager().registerEvents(this, plugin)
        registerNexoApiEvents()
    }

    /**
     * Startet `nexo reload` auf dem Main-Thread. Waehrend und kurz nach dem Reload
     * werden eigene Reload-Events unterdrueckt, damit ein Pull auf einem Consumer
     * nicht als "Nexo wurde bearbeitet" interpretiert wird.
     */
    fun reloadNexo() {
        val command = config.reloadCommand
        if (command.isBlank()) return

        if (!nexoInstalled) {
            plugin.logger.warning("Nexo ist nicht installiert - '$command' wird uebersprungen.")
            return
        }

        val task = Runnable {
            suppressUntil.set(System.currentTimeMillis() + config.reloadSuppressMillis)
            try {
                plugin.logger.info("Fuehre '$command' aus...")
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "Nexo-Reload ('$command') ist fehlgeschlagen.", e)
            }
        }

        if (Bukkit.isPrimaryThread()) task.run() else runOnMain(task)
    }

    /** Verhindert, dass ein gerade laufender eigener Reload einen Push ausloest. */
    fun suppressBriefly() {
        suppressUntil.set(System.currentTimeMillis() + config.reloadSuppressMillis)
    }

    // ------------------------------------------------------------- Listener

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onServerCommand(event: ServerCommandEvent) {
        handleCommand(event.command)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        handleCommand(event.message)
    }

    private fun handleCommand(raw: String) {
        val normalized = raw.removePrefix("/").trim().replace(WHITESPACE, " ").lowercase()
        if (normalized.isEmpty()) return
        if (config.reloadTriggers.none { normalized.startsWith(it.lowercase()) }) return
        notifyReload()
    }

    private fun notifyReload() {
        if (!config.autoPushOnNexoReload) return
        if (System.currentTimeMillis() < suppressUntil.get()) return

        val callback = onExternalReload ?: return
        // Der Command wurde noch nicht ausgefuehrt (MONITOR laeuft vor dem Dispatch),
        // deshalb erst nach einer kurzen Verzoegerung pushen - sonst liest der Push
        // noch den alten Dateistand.
        runOnMainLater(Runnable { callback() }, config.autoPushDelayTicks.coerceAtLeast(1L))
    }

    /**
     * Registriert - falls vorhanden - die Nexo-eigenen API-Events per Reflection.
     * Klassen, die es in der installierten Nexo-Version nicht gibt, werden still ignoriert.
     */
    private fun registerNexoApiEvents() {
        val nexo = Bukkit.getPluginManager().getPlugin(NEXO_PLUGIN_NAME) ?: return
        val classLoader = nexo.javaClass.classLoader
        val executor = EventExecutor { _, _ -> notifyReload() }

        var registered = 0
        for (className in config.nexoEventClasses) {
            val candidate = runCatching { Class.forName(className, false, classLoader) }.getOrNull() ?: continue
            if (!Event::class.java.isAssignableFrom(candidate)) continue

            @Suppress("UNCHECKED_CAST")
            val eventClass = candidate as Class<out Event>

            val success = runCatching {
                Bukkit.getPluginManager().registerEvent(
                    eventClass,
                    this,
                    EventPriority.MONITOR,
                    executor,
                    plugin,
                    true
                )
            }.isSuccess

            if (success) {
                registered++
                plugin.logger.fine("Nexo-API-Event registriert: $className")
            }
        }

        if (registered == 0 && config.nexoEventClasses.isNotEmpty()) {
            plugin.logger.info(
                "Keines der konfigurierten Nexo-API-Events wurde gefunden - " +
                    "der Auto-Push haengt damit allein am Command-Hook ('${config.reloadTriggers.joinToString("', '")}')."
            )
        }
    }

    private fun runOnMain(task: Runnable) {
        if (!plugin.isEnabled) return
        runCatching { Bukkit.getScheduler().runTask(plugin, task) }
    }

    private fun runOnMainLater(task: Runnable, delayTicks: Long) {
        if (!plugin.isEnabled) return
        runCatching { Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks) }
    }

    private companion object {
        const val NEXO_PLUGIN_NAME = "Nexo"
        val WHITESPACE = Regex("\\s+")
    }
}
