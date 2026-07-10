package pt.henrique.webskins.skin;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import pt.henrique.webskins.WebSkins;
import pt.henrique.webskins.model.SkinData;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies skin textures to a player's GameProfile and refreshes their
 * appearance for all online players.
 * <p>
 * Compatible with Paper 1.21.x using the Paper Profile API.
 * <p>
 * Two modes of operation:
 * 1) {@link #applyProfileOnly(Player, SkinData)} — sets the GameProfile
 *    textures without refreshing. Used during login, before the player
 *    entity has been sent to any client, so no refresh is needed.
 * 2) {@link #applySkin(Player, SkinData)} — sets the GameProfile AND
 *    refreshes the player entity for all observers. Used for instant
 *    apply while the player is already online.
 */
public class SkinApplier {

    private final WebSkins plugin;

    public SkinApplier(WebSkins plugin) {
        this.plugin = plugin;
    }

    /**
     * Set the skin textures on the player's GameProfile WITHOUT refreshing.
     * <p>
     * Call this during {@code PlayerLoginEvent} (before the join packet is
     * broadcast) so every client sees the correct skin from the start.
     * <p>
     * <b>Must be called on the main thread.</b>
     */
    public void applyProfileOnly(Player player, SkinData skinData) {
        if (skinData == null || !skinData.isValid()) return;
        try {
            setProfileTextures(player, skinData);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to set profile for " + player.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Apply a skin to an already-online player and refresh their appearance
     * for every other online player (and themselves).
     * <p>
     * If already on the main thread this runs immediately, otherwise it
     * schedules onto the main thread.
     */
    public void applySkin(Player player, SkinData skinData) {
        if (skinData == null || !skinData.isValid()) return;

        Runnable task = () -> {
            try {
                setProfileTextures(player, skinData);
                refreshPlayer(player);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to apply skin to " + player.getName() + ": " + e.getMessage());
            }
        };

        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    // ------------------------------------------------------------------ //
    //  Internal helpers                                                    //
    // ------------------------------------------------------------------ //

    /**
     * Replace the "textures" property on the player's Paper profile.
     */
    private void setProfileTextures(Player player, SkinData skinData) {
        PlayerProfile profile = player.getPlayerProfile();
        profile.getProperties().removeIf(p -> p.getName().equals("textures"));
        profile.getProperties().add(
                new ProfileProperty("textures", skinData.textureValue(), skinData.textureSignature())
        );
        player.setPlayerProfile(profile);
    }

    /**
     * Refresh the player's appearance for all online players without kicking.
     * <p>
     * Strategy:
     * <ol>
     *   <li>Collect every observer that can currently see the player.</li>
     *   <li>Hide the player from all of them (destroys the entity on their client).</li>
     *   <li>After a short delay (3 ticks ≈ 150 ms) show the player again.
     *       Because the GameProfile has been updated, the client will re-create
     *       the entity with the new skin textures.</li>
     *   <li>For the player themselves, perform a safe "respawn" via
     *       world change trick to force the client to re-download their own skin.</li>
     * </ol>
     */
    private void refreshPlayer(Player player) {
        // --- 1. Refresh for OTHER players (hide → delay → show) ----------
        List<Player> observers = new ArrayList<>();
        for (Player observer : Bukkit.getOnlinePlayers()) {
            if (observer.equals(player)) continue;
            if (observer.canSee(player)) {
                observers.add(observer);
                observer.hidePlayer(plugin, player);
            }
        }

        // Show again after 3 ticks — enough time for the destroy packet to
        // be processed on the client before a new spawn packet arrives.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player observer : observers) {
                if (observer.isOnline() && player.isOnline()) {
                    observer.showPlayer(plugin, player);
                }
            }
        }, 3L);

        // --- 2. Refresh for the player THEMSELVES -------------------------
        // Save all state we need to restore after the re-spawn.
        Location loc = player.getLocation().clone();
        boolean wasFlying = player.isFlying();
        boolean allowFlight = player.getAllowFlight();
        float walkSpeed = player.getWalkSpeed();
        float flySpeed = player.getFlySpeed();
        int foodLevel = player.getFoodLevel();
        float saturation = player.getSaturation();
        float exhaustion = player.getExhaustion();
        int fireTicks = player.getFireTicks();
        int totalExp = player.getTotalExperience();
        float exp = player.getExp();
        int level = player.getLevel();

        // The most reliable way to make the client re-render the player's OWN
        // skin is to briefly send them to a different world and back.  This
        // forces a full respawn sequence on the client side which re-reads the
        // GameProfile (including the new textures).
        //
        // If there is only one world we fall back to a simple teleport (which
        // at least fixes the position; the player may need to relog to see
        // their own skin, but other players will see it correctly).
        if (Bukkit.getWorlds().size() > 1) {
            org.bukkit.World otherWorld = null;
            for (org.bukkit.World w : Bukkit.getWorlds()) {
                if (!w.equals(player.getWorld())) {
                    otherWorld = w;
                    break;
                }
            }
            if (otherWorld != null) {
                player.teleport(new Location(otherWorld, loc.getX(), 500, loc.getZ()));
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        player.teleport(loc);
                        restoreState(player, wasFlying, allowFlight, walkSpeed, flySpeed,
                                foodLevel, saturation, exhaustion, fireTicks, totalExp, exp, level);
                    }
                }, 3L);
            }
        } else {
            // Single world fallback — just teleport in-place
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.teleport(loc);
                    restoreState(player, wasFlying, allowFlight, walkSpeed, flySpeed,
                            foodLevel, saturation, exhaustion, fireTicks, totalExp, exp, level);
                }
            }, 4L);
        }
    }

    private void restoreState(Player player, boolean wasFlying, boolean allowFlight,
                               float walkSpeed, float flySpeed, int foodLevel,
                               float saturation, float exhaustion, int fireTicks,
                               int totalExp, float exp, int level) {
        player.setAllowFlight(allowFlight);
        player.setFlying(wasFlying);
        player.setWalkSpeed(walkSpeed);
        player.setFlySpeed(flySpeed);
        player.setFoodLevel(foodLevel);
        player.setSaturation(saturation);
        player.setExhaustion(exhaustion);
        player.setFireTicks(fireTicks);
        player.setTotalExperience(totalExp);
        player.setExp(exp);
        player.setLevel(level);
    }
}

