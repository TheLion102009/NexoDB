package the.lion.nexoDB.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import the.lion.nexoDB.NexoDB
import the.lion.nexoDB.sync.SyncResult
import java.time.Duration

/**
 * Der /nexosync-Command als Brigadier-Baum.
 *
 * Paper-Plugins haben keinen `commands:`-Block in der paper-plugin.yml - Commands werden
 * ueber die Brigadier-API am COMMANDS-Lifecycle-Event registriert (siehe [NexoDB]).
 * Vorteil gegenueber dem alten CommandExecutor: Tab-Completion, Rechtepruefung und
 * Fehlermeldungen bei Tippfehlern kommen vom Server, nicht aus eigenem Code.
 *
 * Die API ist zwischen Paper 1.21.4 und 26.2 unveraendert, deshalb laeuft derselbe
 * Command-Baum auf der gesamten unterstuetzten Versionsspanne.
 */
@Suppress("UnstableApiUsage")
class NexoSyncCommand(private val plugin: NexoDB) {

    fun node(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(ROOT)
            // Wer keines der Rechte hat, sieht den Command gar nicht erst.
            .requires { source -> ALL_PERMISSIONS.any { source.sender.hasPermission(it) } }
            .executes { context ->
                sendHelp(context.source.sender)
                Command.SINGLE_SUCCESS
            }
            .then(toggleable("push", PERM_PUSH) { sender, force -> handlePush(sender, force) })
            .then(toggleable("pull", PERM_PULL) { sender, force -> handlePull(sender, force) })
            .then(
                Commands.literal("status")
                    .requires { it.sender.hasPermission(PERM_STATUS) }
                    .executes { context ->
                        handleStatus(context.source.sender)
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("reload")
                    .requires { it.sender.hasPermission(PERM_RELOAD) }
                    .executes { context ->
                        handleReload(context.source.sender)
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()

    /** push und pull unterscheiden sich nur im Handler; beide koennen optional --force. */
    private fun toggleable(
        name: String,
        permission: String,
        handler: (CommandSender, Boolean) -> Unit
    ): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(name)
            .requires { it.sender.hasPermission(permission) }
            .executes { context ->
                handler(context.source.sender, false)
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.literal(FORCE_FLAG)
                    .executes { context ->
                        handler(context.source.sender, true)
                        Command.SINGLE_SUCCESS
                    }
            )

    // ------------------------------------------------------------------ Push

    private fun handlePush(sender: CommandSender, force: Boolean) {
        val service = plugin.syncOrNull()
        if (service == null) {
            error(sender, "Der Sync-Dienst laeuft nicht.")
            return
        }

        // Der Master-Check sitzt zusaetzlich im SyncService - hier nur fuer schnelles Feedback.
        val rejection = service.pushRejectionReason()
        if (rejection != null) {
            error(sender, rejection)
            return
        }

        info(sender, if (force) "Push gestartet (alle Dateien werden neu geschrieben)..." else "Push gestartet...")
        service.requestPush(force) { result -> report(sender, result) }
    }

    // ------------------------------------------------------------------ Pull

    private fun handlePull(sender: CommandSender, force: Boolean) {
        val service = plugin.syncOrNull()
        if (service == null) {
            error(sender, "Der Sync-Dienst laeuft nicht.")
            return
        }

        val rejection = service.pullRejectionReason()
        if (rejection != null) {
            error(sender, rejection)
            return
        }

        info(sender, if (force) "Pull gestartet (alle Dateien werden neu geschrieben)..." else "Pull gestartet...")
        service.requestPull(force) { result -> report(sender, result) }
    }

    // ---------------------------------------------------------------- Status

    private fun handleStatus(sender: CommandSender) {
        val service = plugin.syncOrNull()
        if (service == null) {
            error(sender, "Der Sync-Dienst laeuft nicht.")
            return
        }

        val status = service.status()
        val masterBroken = status.role == "MASTER" && !status.masterAuthorized

        sender.sendMessage(Component.text("---- NexoDB ----", NamedTextColor.AQUA))
        line(sender, "Server-ID", status.serverId)
        line(
            sender,
            "Rolle",
            when {
                status.role != "MASTER" -> "CONSUMER"
                masterBroken -> "MASTER (Lock BLOCKIERT!)"
                else -> "MASTER (Lock gehalten)"
            },
            if (masterBroken) NamedTextColor.RED else NamedTextColor.WHITE
        )
        line(sender, "Master laut DB", status.masterHolder ?: "keiner eingetragen")
        line(
            sender,
            "MongoDB",
            if (status.connected) "verbunden - ${status.topology}" else "NICHT verbunden",
            if (status.connected) NamedTextColor.GREEN else NamedTextColor.RED
        )
        line(sender, "Sync-Modus", status.mode.name.lowercase().replace('_', '-'))
        line(sender, "Revision", status.revision.toString())
        line(sender, "Dateien in der DB", status.remoteResources.toString())
        line(sender, "Hash-Cache", "${status.cachedHashes} Eintraege")
        line(sender, "Letzter Push", ago(status.lastPushAt))
        line(sender, "Letzter Pull", ago(status.lastPullAt))
        status.lastError?.let { line(sender, "Letzter Fehler", it, NamedTextColor.RED) }
    }

    // ---------------------------------------------------------------- Reload

    private fun handleReload(sender: CommandSender) {
        info(sender, "Lade NexoDB-Konfiguration neu...")
        val problems = plugin.reloadEverything()
        if (problems.isEmpty()) {
            success(sender, "Konfiguration neu geladen.")
        } else {
            error(sender, "Konfiguration ist fehlerhaft:")
            problems.forEach { error(sender, "  - $it") }
        }
    }

    // --------------------------------------------------------------- Ausgabe

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage(Component.text("---- NexoDB ----", NamedTextColor.AQUA))
        help(sender, "/$ROOT push [$FORCE_FLAG]", "lokale Dateien in die Datenbank schreiben (nur Master)")
        help(sender, "/$ROOT pull [$FORCE_FLAG]", "Dateien aus der Datenbank holen und Nexo neu laden")
        help(sender, "/$ROOT status", "Rolle, Verbindung und letzte Syncs anzeigen")
        help(sender, "/$ROOT reload", "config.yml neu einlesen")
    }

    private fun help(sender: CommandSender, usage: String, description: String) {
        sender.sendMessage(
            Component.text(usage, NamedTextColor.YELLOW)
                .append(Component.text(" - $description", NamedTextColor.GRAY))
        )
    }

    private fun report(sender: CommandSender, result: SyncResult) {
        if (result.success) success(sender, result.message) else error(sender, result.message)
    }

    private fun line(sender: CommandSender, key: String, value: String, color: NamedTextColor = NamedTextColor.WHITE) {
        sender.sendMessage(
            Component.text("$key: ", NamedTextColor.GRAY).append(Component.text(value, color))
        )
    }

    private fun info(sender: CommandSender, message: String) =
        sender.sendMessage(Component.text(PREFIX, NamedTextColor.AQUA).append(Component.text(message, NamedTextColor.GRAY)))

    private fun success(sender: CommandSender, message: String) =
        sender.sendMessage(Component.text(PREFIX, NamedTextColor.AQUA).append(Component.text(message, NamedTextColor.GREEN)))

    private fun error(sender: CommandSender, message: String) =
        sender.sendMessage(Component.text(PREFIX, NamedTextColor.AQUA).append(Component.text(message, NamedTextColor.RED)))

    private fun ago(timestamp: Long): String {
        if (timestamp <= 0L) return "noch nie"
        val duration = Duration.ofMillis(System.currentTimeMillis() - timestamp)
        return when {
            duration.toMinutes() < 1 -> "vor ${duration.seconds}s"
            duration.toHours() < 1 -> "vor ${duration.toMinutes()}min"
            duration.toDays() < 1 -> "vor ${duration.toHours()}h"
            else -> "vor ${duration.toDays()}d"
        }
    }

    companion object {
        const val ROOT = "nexosync"
        const val FORCE_FLAG = "--force"
        const val PREFIX = "[NexoDB] "

        const val PERM_USE = "nexodb.use"
        const val PERM_PUSH = "nexodb.push"
        const val PERM_PULL = "nexodb.pull"
        const val PERM_STATUS = "nexodb.status"
        const val PERM_RELOAD = "nexodb.reload"
        const val PERM_ADMIN = "nexodb.admin"

        val ALL_PERMISSIONS = listOf(PERM_USE, PERM_PUSH, PERM_PULL, PERM_STATUS, PERM_RELOAD, PERM_ADMIN)

        /**
         * Paper-Plugins deklarieren Rechte nicht in der paper-plugin.yml, sondern
         * registrieren sie beim Start - siehe [NexoDB.registerPermissions].
         */
        val PERMISSION_DESCRIPTIONS = linkedMapOf(
            PERM_USE to "Darf /nexosync benutzen.",
            PERM_PUSH to "Darf lokale Dateien in die Datenbank pushen (nur auf dem Master moeglich).",
            PERM_PULL to "Darf Dateien aus der Datenbank holen.",
            PERM_STATUS to "Darf den Sync-Status abfragen.",
            PERM_RELOAD to "Darf die NexoDB-Konfiguration neu laden."
        )

        const val DESCRIPTION = "Synchronisiert die Nexo-Ressourcen ueber eine zentrale MongoDB."
        val ALIASES = listOf("nexodb", "nsync")
    }
}
