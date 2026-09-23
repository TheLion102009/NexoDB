package the.lion.nexoDB

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.event.HandlerList
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import org.bukkit.plugin.java.JavaPlugin
import the.lion.nexoDB.command.NexoSyncCommand
import the.lion.nexoDB.config.NexoDBConfig
import the.lion.nexoDB.db.MongoManager
import the.lion.nexoDB.db.ResourceRepository
import the.lion.nexoDB.nexo.NexoBridge
import the.lion.nexoDB.sync.SyncService
import java.nio.file.Path

@Suppress("UnstableApiUsage")
class NexoDB : JavaPlugin() {

    private var bridge: NexoBridge? = null
    private var sync: SyncService? = null

    /** Der Ordner, in dem `plugins/` liegt - Basis fuer alle relativen Pfade der config.yml. */
    val serverRoot: Path
        get() = dataFolder.absoluteFile.parentFile.parentFile.toPath()

    override fun onEnable() {
        saveDefaultConfig()
        registerPermissions()

        val problems = startServices()
        if (problems.isNotEmpty()) {
            logger.severe("NexoDB konnte nicht starten:")
            problems.forEach { logger.severe("  - $it") }
            logger.severe("Bitte plugins/NexoDB/config.yml korrigieren und den Server neu starten.")
            server.pluginManager.disablePlugin(this)
            return
        }

        registerCommands()
    }

    override fun onDisable() {
        stopServices()
    }

    fun syncOrNull(): SyncService? = sync

    /**
     * Stoppt alle Dienste, liest die config.yml neu und startet wieder.
     * Gibt die gefundenen Konfigurationsprobleme zurueck (leer = alles gut).
     *
     * Laeuft auf dem Main-Thread und wartet beim Stoppen bis zu 5 Sekunden auf einen
     * laufenden Sync - fuer einen Admin-Command ist das vertretbar.
     */
    fun reloadEverything(): List<String> {
        stopServices()
        reloadConfig()
        return startServices()
    }

    /**
     * Paper-Plugins registrieren Commands ueber Brigadier am COMMANDS-Lifecycle-Event;
     * einen `commands:`-Block wie in der alten plugin.yml gibt es nicht mehr.
     */
    private fun registerCommands() {
        val command = NexoSyncCommand(this)
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(
                command.node(),
                NexoSyncCommand.DESCRIPTION,
                NexoSyncCommand.ALIASES
            )
        }
    }

    /**
     * Die paper-plugin.yml kennt keinen verlaesslichen `permissions:`-Block, deshalb
     * werden die Rechte hier angelegt. Ergebnis ist dasselbe: Rechte-Plugins wie LuckPerms
     * sehen sie im Tab-Complete und die OP-Defaults greifen.
     */
    private fun registerPermissions() {
        val manager = server.pluginManager
        val children = LinkedHashMap<String, Boolean>()

        for ((name, description) in NexoSyncCommand.PERMISSION_DESCRIPTIONS) {
            children[name] = true
            if (manager.getPermission(name) == null) {
                manager.addPermission(Permission(name, description, PermissionDefault.OP))
            }
        }

        if (manager.getPermission(NexoSyncCommand.PERM_ADMIN) == null) {
            manager.addPermission(
                Permission(NexoSyncCommand.PERM_ADMIN, "Alle NexoDB-Rechte.", PermissionDefault.OP, children)
            )
        }
    }

    private fun startServices(): List<String> {
        val loaded = NexoDBConfig.load(config)
        val problems = loaded.validate()
        if (problems.isNotEmpty()) return problems

        val mongo = MongoManager(loaded, logger)
        val repository = ResourceRepository(mongo, loaded, logger)
        val nexoBridge = NexoBridge(this, loaded)
        val service = SyncService(this, loaded, serverRoot, mongo, repository, nexoBridge)

        nexoBridge.registerHooks { service.onNexoReloadDetected() }

        bridge = nexoBridge
        sync = service

        logger.info(
            "server-id '${loaded.serverId}' | Rolle: " +
                if (loaded.isMaster) "MASTER (schreibt in die Datenbank)" else "CONSUMER (liest nur)"
        )
        if (!nexoBridge.nexoInstalled) {
            logger.warning(
                "Nexo wurde nicht gefunden. Dateien werden zwar synchronisiert, " +
                    "aber '${loaded.reloadCommand}' kann nicht ausgefuehrt werden."
            )
        }

        service.start()
        return emptyList()
    }

    private fun stopServices() {
        bridge?.let { HandlerList.unregisterAll(it) }
        bridge = null
        sync?.shutdown()
        sync = null
    }
}
