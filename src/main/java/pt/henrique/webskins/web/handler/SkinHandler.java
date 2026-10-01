package pt.henrique.webskins.web.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import pt.henrique.webskins.WebSkins;
import pt.henrique.webskins.database.Database;
import pt.henrique.webskins.model.SkinData;
import pt.henrique.webskins.web.WebServer;
import pt.henrique.webskins.web.service.ImageValidator;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Handles /api/skins/* endpoints.
 *
 * GET  /api/skins/UUID       — Get active skin
 * GET  /api/skins/UUID/all   — Get skin history
 * PUT  /api/skins/UUID       — Save from editor
 * POST /api/skins/UUID/upload — Upload PNG file
 * POST /api/skins/UUID/apply/ID — Re-apply from history
 */
public class SkinHandler {

    private final WebSkins plugin;

    public SkinHandler(WebSkins plugin) {
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
            // Authenticate
            String playerUuid = authenticate(exchange);
            if (playerUuid == null) {
                WebServer.sendJson(exchange, 401, "{\"error\":\"Unauthorized\"}");
                return;
            }

            // Route
            if (path.matches("/api/skins/[^/]+/all") && "GET".equals(method)) {
                handleGetHistory(exchange, playerUuid);
            } else if (path.matches("/api/skins/[^/]+/upload") && "POST".equals(method)) {
                handleUpload(exchange, playerUuid);
            } else if (path.matches("/api/skins/[^/]+/apply/\\d+") && "POST".equals(method)) {
                handleApply(exchange, playerUuid, path);
            } else if (path.matches("/api/skins/[^/]+") && "GET".equals(method)) {
                handleGetActive(exchange, playerUuid);
            } else if (path.matches("/api/skins/[^/]+") && "PUT".equals(method)) {
                handleSaveFromEditor(exchange, playerUuid);
            } else {
                WebServer.sendJson(exchange, 404, "{\"error\":\"Not found\"}");
            }
        } catch (WebServer.BodyTooLargeException e) {
            // 413 already sent by readBody; the response is committed, do not write again.
        } catch (Exception e) {
            plugin.getLogger().warning("Skin handler error: " + e.getMessage());
            WebServer.sendJson(exchange, 500, "{\"error\":\"Internal server error\"}");
        }
    }

    private void handleGetActive(HttpExchange exchange, String playerUuid) throws Exception {
        Database.SkinRecord skin = plugin.getDatabase().getActiveSkinRecord(playerUuid);
        if (skin == null) {
            WebServer.sendJson(exchange, 200, "{\"textureValue\":null,\"textureSignature\":null}");
            return;
        }

        JsonObject resp = new JsonObject();
        resp.addProperty("id", skin.id());
        resp.addProperty("textureValue", skin.textureValue());
        resp.addProperty("textureSignature", skin.textureSignature());
        resp.addProperty("modelType", skin.modelType());
        resp.addProperty("source", skin.source());
        resp.addProperty("createdAt", skin.createdAt());
        WebServer.sendJson(exchange, 200, resp.toString());
    }

    private void handleGetHistory(HttpExchange exchange, String playerUuid) throws Exception {
        List<Database.SkinRecord> skins = plugin.getDatabase().getSkinHistory(playerUuid);
        JsonArray arr = new JsonArray();
        for (Database.SkinRecord s : skins) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", s.id());
            obj.addProperty("modelType", s.modelType());
            obj.addProperty("source", s.source());
            obj.addProperty("active", s.active());
            obj.addProperty("createdAt", s.createdAt());
            arr.add(obj);
        }
        WebServer.sendJson(exchange, 200, arr.toString());
    }

    private void handleSaveFromEditor(HttpExchange exchange, String playerUuid) throws Exception {
        String body = WebServer.readBody(exchange);
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();

        String imageBase64 = json.get("imageBase64").getAsString();
        String model = json.has("model") ? json.get("model").getAsString() : "steve";

        // Decode and validate
        byte[] imageBytes = ImageValidator.decodeBase64(imageBase64);
        String error = ImageValidator.validate(imageBytes);
        if (error != null) {
            WebServer.sendJsonError(exchange, 400, error);
            return;
        }

        // Normalize to 64x64
        String normalized = ImageValidator.normalizeToBase64(imageBytes);

        // Sign via MineSkin
        String[] signed = plugin.getMineSkinService().signFromBase64(normalized, model.equals("slim") ? "slim" : "classic");
        if (signed == null) {
            WebServer.sendJson(exchange, 503, "{\"error\":\"Skin signing service unavailable. Is your MineSkin API key configured?\"}");
            return;
        }

        // Save to database
        String modelType = model.equals("slim") ? "SLIM" : "DEFAULT";
        int skinId = plugin.getDatabase().saveSkin(playerUuid, signed[0], signed[1], modelType, "EDITOR");

        // Update cache and apply instantly if configured
        applySkinInstantly(playerUuid, signed[0], signed[1], modelType);

        JsonObject resp = new JsonObject();
        resp.addProperty("id", skinId);
        resp.addProperty("textureValue", signed[0]);
        resp.addProperty("textureSignature", signed[1]);
        resp.addProperty("modelType", modelType);
        WebServer.sendJson(exchange, 200, resp.toString());
    }

    private void handleUpload(HttpExchange exchange, String playerUuid) throws Exception {
        // Read raw body as image bytes (for simplicity, expect base64 JSON body)
        String body = WebServer.readBody(exchange);
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();

        String imageBase64 = json.get("imageBase64").getAsString();
        String model = json.has("model") ? json.get("model").getAsString() : "steve";

        byte[] imageBytes = ImageValidator.decodeBase64(imageBase64);
        String error = ImageValidator.validate(imageBytes);
        if (error != null) {
            WebServer.sendJsonError(exchange, 400, error);
            return;
        }

        String normalized = ImageValidator.normalizeToBase64(imageBytes);
        String[] signed = plugin.getMineSkinService().signFromBase64(normalized, model.equals("slim") ? "slim" : "classic");
        if (signed == null) {
            WebServer.sendJson(exchange, 503, "{\"error\":\"Skin signing service unavailable.\"}");
            return;
        }

        String modelType = model.equals("slim") ? "SLIM" : "DEFAULT";
        int skinId = plugin.getDatabase().saveSkin(playerUuid, signed[0], signed[1], modelType, "UPLOAD");

        applySkinInstantly(playerUuid, signed[0], signed[1], modelType);

        JsonObject resp = new JsonObject();
        resp.addProperty("id", skinId);
        resp.addProperty("textureValue", signed[0]);
        resp.addProperty("textureSignature", signed[1]);
        resp.addProperty("modelType", modelType);
        WebServer.sendJson(exchange, 200, resp.toString());
    }

    private void handleApply(HttpExchange exchange, String playerUuid, String path) throws Exception {
        // Extract skin ID from path: /api/skins/UUID/apply/123
        String[] parts = path.split("/");
        int skinId = Integer.parseInt(parts[parts.length - 1]);

        boolean success = plugin.getDatabase().activateSkin(playerUuid, skinId);
        if (!success) {
            WebServer.sendJson(exchange, 404, "{\"error\":\"Skin not found\"}");
            return;
        }

        SkinData skin = plugin.getDatabase().getSkinById(skinId);
        if (skin != null) {
            applySkinInstantly(playerUuid, skin.textureValue(), skin.textureSignature(), skin.modelType());
        }

        JsonObject resp = new JsonObject();
        resp.addProperty("ok", true);
        WebServer.sendJson(exchange, 200, resp.toString());
    }

    /**
     * If INSTANT mode is enabled, apply the skin to the player right now.
     */
    private void applySkinInstantly(String playerUuid, String textureValue, String textureSignature, String modelType) {
        if (!"INSTANT".equalsIgnoreCase(plugin.getConfigManager().getApplyMode())) return;

        SkinData skinData = new SkinData(textureValue, textureSignature, modelType, "web", System.currentTimeMillis());
        plugin.getSkinCache().put(UUID.fromString(playerUuid), skinData);

        // Apply on the main Bukkit thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(UUID.fromString(playerUuid));
            if (player != null && player.isOnline()) {
                plugin.getSkinApplier().applySkin(player, skinData);
                player.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacySection().deserialize(
                                plugin.getLangManager().getPrefixed("skin-applied")
                        ));
            }
        });
    }

    /**
     * Authenticate request via Authorization header token.
     */
    private String authenticate(HttpExchange exchange) throws Exception {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return null;

        String token = auth.substring(7);
        String[] result = plugin.getDatabase().verifyToken(token, WebServer.remoteIp(exchange));
        if (result == null) return null;
        return result[0]; // playerUuid
    }
}

