package pt.henrique.webskins.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import pt.henrique.webskins.WebSkins;

/**
 * Cleanup on player quit (currently no-op, cache TTL handles expiry).
 */
public class PlayerQuitListener implements Listener {

    private final WebSkins plugin;

    public PlayerQuitListener(WebSkins plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Skin cache uses TTL-based expiry, no explicit cleanup needed.
        // If we wanted to free memory aggressively:
        // plugin.getSkinCache().invalidate(event.getPlayer().getUniqueId());
    }
}

