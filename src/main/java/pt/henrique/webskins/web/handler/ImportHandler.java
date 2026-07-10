package pt.henrique.webskins.web.handler;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import pt.henrique.webskins.WebSkins;
import pt.henrique.webskins.web.WebServer;
import pt.henrique.webskins.web.service.ImageValidator;
import pt.henrique.webskins.web.service.ImportService;

import java.io.IOException;

/**
 * Handles /api/import/* endpoints.
 *
 * POST /api/import/username — Import skin from Minecraft username (Mojang API)
 * POST /api/import/url      — Import skin from an image URL
 */
public class ImportHandler {

    private final WebSkins plugin;

    public ImportHandler(WebSkins plugin) {
        this.plugin = plugin;
    }

    public void handle(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            WebServer.sendJson(exchange, 200, "{}");
            return;
        }

        String path = exchange.getRequestURI().getPath();

        try {
            // Authenticate
            String playerUuid = authenticate(exchange);
            if (playerUuid == null) {
                WebServer.sendJson(exchange, 401, "{\"error\":\"Unauthorized\"}");
                return;
            }

            if (path.equals("/api/import/username") && "POST".equals(exchange.getRequestMethod())) {
                handleImportUsername(exchange, playerUuid);
            } else if (path.equals("/api/import/url") && "POST".equals(exchange.getRequestMethod())) {
                handleImportUrl(exchange, playerUuid);
            } else {
                WebServer.sendJson(exchange, 404, "{\"error\":\"Not found\"}");
            }
        } catch (WebServer.BodyTooLargeException e) {
            // 413 already sent by readBody; the response is committed, do not write again.
        } catch (Exception e) {
            plugin.getLogger().warning("Import handler error: " + e.getMessage());
            WebServer.sendJson(exchange, 500, "{\"error\":\"Internal server error\"}");
        }
    }

    private void handleImportUsername(HttpExchange exchange, String playerUuid) throws Exception {
        String body = WebServer.readBody(exchange);
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();

        String username = json.get("username").getAsString();
        String model = json.has("model") ? json.get("model").getAsString() : "steve";

        if (username == null || username.isBlank()) {
            WebServer.sendJson(exchange, 400, "{\"error\":\"Missing username\"}");
            return;
        }

        username = username.trim();
        // Minecraft usernames are [A-Za-z0-9_], up to 16 chars. Enforcing this rejects
        // input that could alter the Mojang request path or inject into logs.
        if (!username.matches("[A-Za-z0-9_]{1,16}")) {
            WebServer.sendJson(exchange, 400, "{\"error\":\"Invalid username.\"}");
            return;
        }

        // Fetch from Mojang
        String[] imported = ImportService.importFromUsername(username, plugin.getLogger());
        if (imported == null) {
            // Build via Gson so the (attacker-controlled) username is properly escaped
            // instead of being concatenated raw into the JSON response.
            JsonObject err = new JsonObject();
            err.addProperty("error", "Could not find skin for '" + username + "'");
            WebServer.sendJson(exchange, 404, err.toString());
            return;
        }

        String textureValue = imported[0];
        String textureSignature = imported[1];
        String skinUrl = imported[2];

        // If we have a Mojang-signed texture, use it directly (no MineSkin needed!)
        if (textureSignature != null && !textureSignature.isBlank()) {
            String modelType = model.equals("slim") ? "SLIM" : "DEFAULT";
            int skinId = plugin.getDatabase().saveSkin(playerUuid, textureValue, textureSignature, modelType, "IMPORT_USERNAME");

            // Apply instantly
            applySkinInstantly(playerUuid, textureValue, textureSignature, modelType);

            JsonObject resp = new JsonObject();
            resp.addProperty("id", skinId);
            resp.addProperty("textureValue", textureValue);
            resp.addProperty("textureSignature", textureSignature);
            resp.addProperty("modelType", modelType);
            resp.addProperty("skinUrl", skinUrl);
            WebServer.sendJson(exchange, 200, resp.toString());
            return;
        }

        // Fallback: if no signature, sign via MineSkin from the skin URL
        if (skinUrl != null && !skinUrl.isBlank()) {
            String[] signed = plugin.getMineSkinService().signFromUrl(skinUrl, model.equals("slim") ? "slim" : "classic");
            if (signed == null) {
                WebServer.sendJson(exchange, 503, "{\"error\":\"Skin signing service unavailable.\"}");
                return;
            }

            String modelType = model.equals("slim") ? "SLIM" : "DEFAULT";
            int skinId = plugin.getDatabase().saveSkin(playerUuid, signed[0], signed[1], modelType, "IMPORT_USERNAME");
            applySkinInstantly(playerUuid, signed[0], signed[1], modelType);

            JsonObject resp = new JsonObject();
            resp.addProperty("id", skinId);
            resp.addProperty("textureValue", signed[0]);
            resp.addProperty("textureSignature", signed[1]);
            resp.addProperty("modelType", modelType);
            WebServer.sendJson(exchange, 200, resp.toString());
            return;
        }

        WebServer.sendJson(exchange, 404, "{\"error\":\"No skin found for that username.\"}");
    }

    private void handleImportUrl(HttpExchange exchange, String playerUuid) throws Exception {
        String body = WebServer.readBody(exchange);
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();

        String url = json.get("url").getAsString();
        String model = json.has("model") ? json.get("model").getAsString() : "steve";

        if (url == null || url.isBlank()) {
            WebServer.sendJson(exchange, 400, "{\"error\":\"Missing URL\"}");
            return;
        }

        // Fetch the image
        byte[] imageBytes = ImportService.fetchImageFromUrl(url.trim(), plugin.getLogger());
        if (imageBytes == null) {
            WebServer.sendJson(exchange, 400, "{\"error\":\"Could not fetch image from URL.\"}");
            return;
        }

        // Validate
        String error = ImageValidator.validate(imageBytes);
        if (error != null) {
            WebServer.sendJson(exchange, 400, "{\"error\":\"" + error + "\"}");
            return;
        }

        // Normalize and sign
        String normalized = ImageValidator.normalizeToBase64(imageBytes);
        String[] signed = plugin.getMineSkinService().signFromBase64(normalized, model.equals("slim") ? "slim" : "classic");
        if (signed == null) {
            WebServer.sendJson(exchange, 503, "{\"error\":\"Skin signing service unavailable.\"}");
            return;
        }

        String modelType = model.equals("slim") ? "SLIM" : "DEFAULT";
        int skinId = plugin.getDatabase().saveSkin(playerUuid, signed[0], signed[1], modelType, "IMPORT_URL");
        applySkinInstantly(playerUuid, signed[0], signed[1], modelType);

        JsonObject resp = new JsonObject();
        resp.addProperty("id", skinId);
        resp.addProperty("textureValue", signed[0]);
        resp.addProperty("textureSignature", signed[1]);
        resp.addProperty("modelType", modelType);
        WebServer.sendJson(exchange, 200, resp.toString());
    }

    private void applySkinInstantly(String playerUuid, String textureValue, String textureSignature, String modelType) {
        if (!"INSTANT".equalsIgnoreCase(plugin.getConfigManager().getApplyMode())) return;

        var skinData = new pt.henrique.webskins.model.SkinData(textureValue, textureSignature, modelType, "web", System.currentTimeMillis());
        plugin.getSkinCache().put(java.util.UUID.fromString(playerUuid), skinData);

        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            var player = org.bukkit.Bukkit.getPlayer(java.util.UUID.fromString(playerUuid));
            if (player != null && player.isOnline()) {
                plugin.getSkinApplier().applySkin(player, skinData);
            }
        });
    }

    private String authenticate(HttpExchange exchange) throws Exception {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        String token = auth.substring(7);
        String[] result = plugin.getDatabase().verifyToken(token);
        if (result == null) return null;
        return result[0];
    }
}

