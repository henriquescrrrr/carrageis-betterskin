package pt.henrique.webskins.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import pt.henrique.webskins.WebSkins;
import pt.henrique.webskins.model.SkinData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles player login & join: pre-loads the skin from DB on the async
 * pre-login thread, then injects it into the GameProfile on join
 * <b>before</b> the entity is sent to other clients.
 */
public class PlayerJoinListener implements Listener {

    private final WebSkins plugin;

    /** Pre-loaded skins waiting to be applied on join. */
    private final Map<UUID, SkinData> pendingSkins = new ConcurrentHashMap<>();

    /** Tracks first-join status for the FIRST_JOIN link mode. */
    private final Map<UUID, Boolean> firstJoinFlags = new ConcurrentHashMap<>();

    public PlayerJoinListener(WebSkins plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // ASYNC PRE-LOGIN — load skin from DB before the player object exists
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;

        UUID uuid = event.getUniqueId();
        try {
            // For FIRST_JOIN mode: check if this player is new
            String mode = plugin.getConfigManager().getJoinSendLinkMode();
            if ("FIRST_JOIN".equals(mode)) {
                firstJoinFlags.put(uuid, plugin.getDatabase().isNewPlayer(uuid.toString()));
            }

            // Pre-load skin: cache first, then DB
            SkinData cached = plugin.getSkinCache().get(uuid);
            if (cached != null && cached.isValid()) {
                pendingSkins.put(uuid, cached);
                return;
            }

            SkinData fromDb = plugin.getDatabase().getActiveSkin(uuid.toString());
            if (fromDb != null && fromDb.isValid()) {
                plugin.getSkinCache().put(uuid, fromDb);
                pendingSkins.put(uuid, fromDb);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to pre-load skin for " + event.getName() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // JOIN — apply the pre-loaded profile before clients see the entity
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Apply skin profile (no refresh needed — entity not yet sent)
        SkinData skin = pendingSkins.remove(uuid);
        if (skin != null && skin.isValid()) {
            plugin.getSkinApplier().applyProfileOnly(player, skin);
        }

        // Decide whether to send the browser link
        String linkMode = plugin.getConfigManager().getJoinSendLinkMode();
        boolean shouldSend = switch (linkMode) {
            case "ALWAYS" -> true;
            case "FIRST_JOIN" -> Boolean.TRUE.equals(firstJoinFlags.remove(uuid));
            default -> false; // NEVER
        };

        if (shouldSend) {
            int delay = plugin.getConfigManager().getJoinLinkDelayTicks();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    sendSkinLink(player);
                }
            }, delay);
        }
    }

    private void sendSkinLink(Player player) {
        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : null;

        String link = plugin.getTokenManager().generateLink(
                player.getUniqueId(),
                player.getName(),
                ip
        );

        if (link == null) return;

        String msgText = plugin.getLangManager().getPrefixed("join-message");
        Component message = LegacyComponentSerializer.legacySection().deserialize(msgText)
                .clickEvent(ClickEvent.openUrl(link))
                .hoverEvent(HoverEvent.showText(
                        LegacyComponentSerializer.legacySection().deserialize(
                                plugin.getLangManager().get("skin-link-hover")
                        )
                ));
        player.sendMessage(message);
    }
}

