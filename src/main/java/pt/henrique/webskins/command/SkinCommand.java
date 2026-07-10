package pt.henrique.webskins.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pt.henrique.webskins.WebSkins;
import pt.henrique.webskins.model.SkinData;

import java.util.ArrayList;
import java.util.List;

/**
 * Main /skin command handler.
 * <p>
 * /skin         — Generate and send browser link
 * /skin status  — Show current skin info
 * /skin reload  — Reload config (admin only)
 */
public class SkinCommand implements CommandExecutor, TabCompleter {

    private final WebSkins plugin;

    public SkinCommand(WebSkins plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        if (args.length == 0) {
            handleLink(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "status" -> handleStatus(player);
            case "reload" -> handleReload(player);
            default -> handleLink(player);
        }

        return true;
    }

    private void handleLink(Player player) {
        if (plugin.getTokenManager().isOnCooldown(player.getUniqueId())) {
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(
                    plugin.getLangManager().getPrefixed("cooldown")
            ));
            return;
        }

        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : null;

        String link = plugin.getTokenManager().generateLink(
                player.getUniqueId(),
                player.getName(),
                ip
        );

        if (link == null) {
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(
                    plugin.getLangManager().getPrefixed("token-error")
            ));
            return;
        }

        String msgText = plugin.getLangManager().getPrefixed("skin-link", "%link%", link);
        Component message = LegacyComponentSerializer.legacySection().deserialize(msgText)
                .clickEvent(ClickEvent.openUrl(link))
                .hoverEvent(HoverEvent.showText(
                        LegacyComponentSerializer.legacySection().deserialize(
                                plugin.getLangManager().get("skin-link-hover")
                        )
                ));
        player.sendMessage(message);

        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(
                plugin.getLangManager().getPrefixed("token-generated")
        ));
    }

    private void handleStatus(Player player) {
        SkinData skin = plugin.getSkinCache().get(player.getUniqueId());

        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(
                plugin.getLangManager().get("status-header")
        ));
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(
                plugin.getLangManager().get("status-player", "%player%", player.getName())
        ));

        if (skin != null && skin.isValid()) {
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(
                    plugin.getLangManager().get("status-model", "%model%", skin.modelType())
            ));
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(
                    plugin.getLangManager().get("status-source", "%source%", skin.source())
            ));
        } else {
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(
                    plugin.getLangManager().getPrefixed("skin-status-none")
            ));
        }

        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(
                plugin.getLangManager().get("status-footer")
        ));
    }

    private void handleReload(Player player) {
        if (!player.hasPermission("betterskin.admin")) {
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(
                    plugin.getLangManager().getPrefixed("reload-no-permission")
            ));
            return;
        }

        plugin.getConfigManager().reload();
        plugin.getLangManager().reload();
        plugin.getSkinCache().invalidateAll();

        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(
                plugin.getLangManager().getPrefixed("reload-success")
        ));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("status");
            // Must match the node declared in plugin.yml and enforced in handleReload
            // ("betterskin.admin"); the old "webskins.admin" node never existed, so the
            // "reload" suggestion was shown to nobody (harmless, but wrong).
            if (sender.hasPermission("betterskin.admin")) {
                completions.add("reload");
            }
            return completions.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}

