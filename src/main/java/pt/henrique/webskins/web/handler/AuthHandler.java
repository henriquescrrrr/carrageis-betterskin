package pt.henrique.webskins.web.handler;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import pt.henrique.webskins.WebSkins;
import pt.henrique.webskins.web.WebServer;

import java.io.IOException;

/**
 * Handles /api/auth/* endpoints.
 *
 * POST /api/auth/token   — Create a token (called internally by plugin)
 * GET  /api/auth/verify/TOKEN — Verify a token (called by browser)
 */
public class AuthHandler {

    private final WebSkins plugin;

    public AuthHandler(WebSkins plugin) {
        this.plugin = plugin;
    }

    public void handle(HttpExchange exchange) throws IOException {
        // Handle CORS preflight
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            WebServer.sendJson(exchange, 200, "{}");
            return;
        }

        String path = exchange.getRequestURI().getPath();

        try {
            if (path.startsWith("/api/auth/verify/")) {
                handleVerify(exchange);
            } else {
                // SECURITY: token creation is intentionally NOT exposed over HTTP.
                // Tokens are minted server-side (TokenManager -> Database) only when a
                // player runs /skin or joins. Exposing a create-token endpoint would let
                // anyone mint a valid session for ANY player UUID (offline-mode UUIDs are
                // derived deterministically from the username), resulting in full
                // account/skin takeover. Do not add such a route.
                WebServer.sendJson(exchange, 404, "{\"error\":\"Not found\"}");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Auth handler error: " + e.getMessage());
            WebServer.sendJson(exchange, 500, "{\"error\":\"Internal server error\"}");
        }
    }

    /**
     * GET /api/auth/verify/TOKEN
     * Called by the browser to verify a token and start a session.
     */
    private void handleVerify(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String token = path.substring("/api/auth/verify/".length());

        if (token.isBlank()) {
            WebServer.sendJson(exchange, 400, "{\"error\":\"Missing token\"}");
            return;
        }

        try {
            String[] result = plugin.getDatabase().verifyToken(token);
            if (result == null) {
                WebServer.sendJson(exchange, 401, "{\"error\":\"Invalid or expired token\"}");
                return;
            }

            JsonObject resp = new JsonObject();
            resp.addProperty("playerUuid", result[0]);
            resp.addProperty("username", result[1]);
            resp.addProperty("sessionToken", token);
            WebServer.sendJson(exchange, 200, resp.toString());
        } catch (java.sql.SQLException e) {
            plugin.getLogger().warning("Database error verifying token: " + e.getMessage());
            WebServer.sendJson(exchange, 500, "{\"error\":\"Database error\"}");
        }
    }
}

