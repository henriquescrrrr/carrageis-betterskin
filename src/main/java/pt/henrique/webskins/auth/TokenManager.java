package pt.henrique.webskins.auth;

import pt.henrique.webskins.WebSkins;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates authentication tokens for the web platform.
 * Tokens are created directly in the local SQLite database (no HTTP calls needed).
 */
public class TokenManager {

    private final WebSkins plugin;

    // Rate limit: track last token generation time per player
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 15_000; // 15 seconds between token generations

    public TokenManager(WebSkins plugin) {
        this.plugin = plugin;
    }

    /**
     * Check if a player is on cooldown for token generation.
     */
    public boolean isOnCooldown(UUID uuid) {
        Long last = cooldowns.get(uuid);
        if (last == null) return false;
        return (System.currentTimeMillis() - last) < COOLDOWN_MS;
    }

    /**
     * Generate a token and return the full browser link.
     * This goes directly to the local database — no network calls.
     */
    public String generateLink(UUID uuid, String username, String ip) {
        cooldowns.put(uuid, System.currentTimeMillis());

        try {
            int expiry = plugin.getConfigManager().getTokenExpirySeconds();
            String bindIp = plugin.getConfigManager().isTokenBindIp() ? ip : null;

            String token = plugin.getDatabase().createToken(uuid.toString(), username, expiry, bindIp);
            String publicUrl = plugin.getConfigManager().getPublicUrl();

            return publicUrl + "/skins?token=" + token;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to generate token: " + e.getMessage());
            return null;
        }
    }
}

