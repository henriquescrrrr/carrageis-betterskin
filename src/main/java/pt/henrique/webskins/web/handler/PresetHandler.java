package pt.henrique.webskins.web.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import pt.henrique.webskins.WebSkins;
import pt.henrique.webskins.database.Database;
import pt.henrique.webskins.model.SkinData;
import pt.henrique.webskins.web.WebServer;

import java.io.IOException;
import java.util.List;

/**
 * Handles /api/presets/* endpoints.
 *
 * GET  /api/presets/global    — List server presets
 * GET  /api/presets           — List personal presets
 * POST /api/presets           — Save a personal preset
 * POST /api/presets/ID/apply  — Apply a preset
 * DELETE /api/presets/ID      — Delete a preset
 */
public class PresetHandler {

    private final WebSkins plugin;

    public PresetHandler(WebSkins plugin) {
        this.plugin = plugin;
    }

    public void handle(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            WebServer.sendJson(exchange, 200, "{}");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        try {
            // Global presets don't need auth
            if (path.equals("/api/presets/global") && "GET".equals(method)) {
                handleGetGlobal(exchange);
                return;
            }

            // Everything else needs auth
            String playerUuid = authenticate(exchange);
            if (playerUuid == null) {
                WebServer.sendJson(exchange, 401, "{\"error\":\"Unauthorized\"}");
                return;
            }

            if (path.equals("/api/presets") && "GET".equals(method)) {
                handleGetPersonal(exchange, playerUuid);
            } else if (path.equals("/api/presets") && "POST".equals(method)) {
                handleCreate(exchange, playerUuid);
            } else if (path.matches("/api/presets/\\d+/apply") && "POST".equals(method)) {
                handleApply(exchange, playerUuid, path);
            } else if (path.matches("/api/presets/\\d+") && "DELETE".equals(method)) {
                handleDelete(exchange, playerUuid, path);
            } else {
                WebServer.sendJson(exchange, 404, "{\"error\":\"Not found\"}");
            }
        } catch (WebServer.BodyTooLargeException e) {
            // 413 already sent by readBody; the response is committed, do not write again.
        } catch (Exception e) {
            plugin.getLogger().warning("Preset handler error: " + e.getMessage());
            WebServer.sendJson(exchange, 500, "{\"error\":\"Internal server error\"}");
        }
    }

    private void handleGetGlobal(HttpExchange exchange) throws Exception {
        List<Database.PresetRecord> presets = plugin.getDatabase().getGlobalPresets();
        WebServer.sendJson(exchange, 200, presetsToJson(presets));
    }

    private void handleGetPersonal(HttpExchange exchange, String playerUuid) throws Exception {
        List<Database.PresetRecord> presets = plugin.getDatabase().getPlayerPresets(playerUuid);
        WebServer.sendJson(exchange, 200, presetsToJson(presets));
    }

    private void handleCreate(HttpExchange exchange, String playerUuid) throws Exception {
        String body = WebServer.readBody(exchange);
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();

        String name = json.get("name").getAsString();
        int skinId = json.get("skinId").getAsInt();

        if (name == null || name.isBlank()) {
            WebServer.sendJson(exchange, 400, "{\"error\":\"Missing name\"}");
            return;
        }

        // Bound the length and strip control characters BEFORE storing. Without a cap a client
        // could persist arbitrarily large names (storage abuse); control chars (incl. §/newlines)
        // have no place in a display name. The name is HTML-escaped on render, so this is defence
        // in depth for storage hygiene, not the only XSS control.
        name = name.trim();
        if (name.length() > 48 || name.chars().anyMatch(Character::isISOControl)) {
            WebServer.sendJson(exchange, 400, "{\"error\":\"Invalid preset name.\"}");
            return;
        }

        // Only allow saving a preset that points at a skin the requester actually owns —
        // otherwise a player could create a preset referencing another player's skin id.
        if (!plugin.getDatabase().skinBelongsTo(skinId, playerUuid)) {
            WebServer.sendJson(exchange, 400, "{\"error\":\"Unknown skin.\"}");
            return;
        }

        int presetId = plugin.getDatabase().createPreset(name, skinId, playerUuid, false);

        JsonObject resp = new JsonObject();
        resp.addProperty("id", presetId);
        resp.addProperty("name", name);
        WebServer.sendJson(exchange, 200, resp.toString());
    }

    private void handleApply(HttpExchange exchange, String playerUuid, String path) throws Exception {
        // Extract preset ID from path: /api/presets/123/apply
        String[] parts = path.split("/");
        int presetId = Integer.parseInt(parts[3]);

        // Get preset to find skin ID
        List<Database.PresetRecord> allPresets = plugin.getDatabase().getGlobalPresets();
        allPresets.addAll(plugin.getDatabase().getPlayerPresets(playerUuid));

        Database.PresetRecord preset = allPresets.stream()
                .filter(p -> p.id() == presetId)
                .findFirst()
                .orElse(null);

        if (preset == null) {
            WebServer.sendJson(exchange, 404, "{\"error\":\"Preset not found\"}");
            return;
        }

        // Get the skin from the preset
        SkinData skin = plugin.getDatabase().getSkinById(preset.skinId());
        if (skin == null) {
            WebServer.sendJson(exchange, 404, "{\"error\":\"Preset skin not found\"}");
            return;
        }

        // Save as the player's active skin
        plugin.getDatabase().saveSkin(playerUuid, skin.textureValue(), skin.textureSignature(), skin.modelType(), "PRESET");

        // Apply instantly
        if ("INSTANT".equalsIgnoreCase(plugin.getConfigManager().getApplyMode())) {
            plugin.getSkinCache().put(java.util.UUID.fromString(playerUuid), skin);
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                var player = org.bukkit.Bukkit.getPlayer(java.util.UUID.fromString(playerUuid));
                if (player != null && player.isOnline()) {
                    plugin.getSkinApplier().applySkin(player, skin);
                }
            });
        }

        JsonObject resp = new JsonObject();
        resp.addProperty("ok", true);
        WebServer.sendJson(exchange, 200, resp.toString());
    }

    private void handleDelete(HttpExchange exchange, String playerUuid, String path) throws Exception {
        String[] parts = path.split("/");
        int presetId = Integer.parseInt(parts[3]);

        boolean deleted = plugin.getDatabase().deletePreset(presetId, playerUuid);
        if (!deleted) {
            WebServer.sendJson(exchange, 404, "{\"error\":\"Preset not found or access denied\"}");
            return;
        }

        WebServer.sendJson(exchange, 200, "{\"ok\":true}");
    }

    private String presetsToJson(List<Database.PresetRecord> presets) {
        JsonArray arr = new JsonArray();
        for (Database.PresetRecord p : presets) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", p.id());
            obj.addProperty("name", p.name());
            obj.addProperty("skinId", p.skinId());
            obj.addProperty("isGlobal", p.isGlobal());
            obj.addProperty("modelType", p.modelType());
            obj.addProperty("source", p.skinSource());
            arr.add(obj);
        }
        return arr.toString();
    }

    private String authenticate(HttpExchange exchange) throws Exception {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        String token = auth.substring(7);
        String[] result = plugin.getDatabase().verifyToken(token, WebServer.remoteIp(exchange));
        if (result == null) return null;
        return result[0];
    }
}

