package pt.henrique.webskins.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import pt.henrique.webskins.WebSkins;
import pt.henrique.webskins.web.handler.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Embedded HTTP server that serves the skin manager web UI and REST API.
 * Uses the JDK built-in HttpServer — no external dependencies needed.
 * <p>
 * When a player clicks the link in chat, their browser connects here.
 */
public class WebServer {

    private final WebSkins plugin;
    private final Logger log;
    private HttpServer server;
    /** Kept so stop() can shut it down: HttpServer never shuts down an executor it was given. */
    private ExecutorService executor;

    /**
     * Hard cap on the size of a request body we will buffer into memory. A skin PNG is at
     * most 2 MB and it is base64-encoded inside a small JSON envelope, so ~3 MB is generous.
     * Without this cap an authenticated client could POST a multi-gigabyte body and OOM the
     * whole Minecraft server ({@code InputStream.readAllBytes()} buffers everything).
     */
    private static final int MAX_BODY_BYTES = 3 * 1024 * 1024; // 3 MB

    // MIME type mapping
    private static final Map<String, String> MIME_TYPES = Map.of(
            "html", "text/html",
            "css", "text/css",
            "js", "application/javascript",
            "json", "application/json",
            "png", "image/png",
            "svg", "image/svg+xml",
            "ico", "image/x-icon",
            "woff2", "font/woff2"
    );

    public WebServer(WebSkins plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public void start() {
        int port = plugin.getConfigManager().getServerPort();
        String bindAddress = plugin.getConfigManager().getServerBindAddress();
        try {
            server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
            executor = Executors.newFixedThreadPool(4);
            server.setExecutor(executor);

            // API routes
            AuthHandler authHandler = new AuthHandler(plugin);
            SkinHandler skinHandler = new SkinHandler(plugin);
            ImportHandler importHandler = new ImportHandler(plugin);
            PresetHandler presetHandler = new PresetHandler(plugin);

            server.createContext("/api/auth", authHandler::handle);
            server.createContext("/api/skins", skinHandler::handle);
            server.createContext("/api/import", importHandler::handle);
            server.createContext("/api/presets", presetHandler::handle);
            server.createContext("/api/health", this::handleHealth);

            // Static files + SPA fallback
            server.createContext("/", this::handleStatic);

            server.start();
            log.info("========================================");
            log.info("  BetterSkin web server started!");
            log.info("  Bind: " + bindAddress + ":" + port);
            log.info("  URL:  " + plugin.getConfigManager().getPublicUrl());
            log.info("========================================");
        } catch (IOException e) {
            log.severe("FAILED to start web server on port " + port + "!");
            log.severe("Error: " + e.getMessage());
            log.severe("Make sure port " + port + " is not already in use.");
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
        }
        if (executor != null) {
            // HttpServer.stop() closes the connections but leaves an externally supplied
            // executor running, so its four threads survive every disable/enable cycle.
            executor.shutdownNow();
            executor = null;
            log.info("Web server stopped.");
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        String json = "{\"status\":\"ok\",\"plugin\":\"BetterSkin\"}";
        sendJson(exchange, 200, json);
    }

    /**
     * Serve static files from resources/web/ or fall back to index.html (SPA).
     */
    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        // Default to index.html
        if (path.equals("/") || path.equals("/skins") || path.equals("/skins/")
                || path.startsWith("/skins/dashboard") || path.startsWith("/skins/editor")
                || path.startsWith("/skins/presets")) {
            path = "/index.html";
        }

        // Security: prevent directory traversal. Allowlist the characters a legitimate static
        // asset path can contain (letters, digits, '/', '.', '-', '_') and explicitly reject any
        // parent-directory or backslash sequence. A blocklist of just ".." misses encoded and
        // backslash variants; the allowlist is the robust check even though the classloader
        // already confines lookups to the jar.
        if (path.contains("..") || path.contains("\\") || !path.matches("[A-Za-z0-9/._-]*")) {
            send(exchange, 403, "text/plain", "Forbidden");
            return;
        }

        // Try to load from classpath
        String resourcePath = "web" + path;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is != null) {
                byte[] data = is.readAllBytes();
                String ext = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : "html";
                String contentType = MIME_TYPES.getOrDefault(ext, "application/octet-stream");

                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("Cache-Control", ext.equals("html") ? "no-cache" : "max-age=3600");
                // The login link carries the token in the query string. Send no Referer so
                // the token is not leaked to third-party origins (e.g. the skinview3d CDN).
                exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.sendResponseHeaders(200, data.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(data);
                }
            } else {
                // SPA fallback — serve index.html for unmatched paths
                try (InputStream indexIs = getClass().getClassLoader().getResourceAsStream("web/index.html")) {
                    if (indexIs != null) {
                        byte[] data = indexIs.readAllBytes();
                        exchange.getResponseHeaders().set("Content-Type", "text/html");
                        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
                        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                        exchange.sendResponseHeaders(200, data.length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(data);
                        }
                    } else {
                        send(exchange, 404, "text/plain", "Not Found");
                    }
                }
            }
        }
    }

    // ========== UTILITY METHODS (shared by handlers) ==========

    /**
     * Send a JSON error whose message is not a literal — image validation messages carry
     * decoder text derived from the uploaded bytes, and concatenating that into a
     * hand-built JSON string produces a malformed body as soon as it contains a quote or
     * a backslash. Gson escapes it.
     */
    public static void sendJsonError(HttpExchange exchange, int status, String message) throws IOException {
        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
        obj.addProperty("error", message);
        sendJson(exchange, status, obj.toString());
    }

    public static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Read the request body as a string, refusing bodies larger than {@link #MAX_BODY_BYTES}.
     * <p>
     * Fails closed with a 413 and throws {@link BodyTooLargeException} so the caller aborts —
     * this prevents a memory-exhaustion DoS from an oversized (or {@code Content-Length}-lying)
     * body. The advertised {@code Content-Length} is checked first as a cheap reject, then the
     * actual bytes are counted while streaming in case the header is missing or dishonest.
     */
    public static String readBody(HttpExchange exchange) throws IOException {
        String lenHeader = exchange.getRequestHeaders().getFirst("Content-Length");
        if (lenHeader != null) {
            try {
                if (Long.parseLong(lenHeader.trim()) > MAX_BODY_BYTES) {
                    sendJson(exchange, 413, "{\"error\":\"Request body too large\"}");
                    throw new BodyTooLargeException();
                }
            } catch (NumberFormatException ignored) {
                // Malformed header — fall through to the streaming cap below.
            }
        }

        try (InputStream is = exchange.getRequestBody()) {
            byte[] chunk = new byte[8192];
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            int total = 0;
            int read;
            while ((read = is.read(chunk)) != -1) {
                total += read;
                if (total > MAX_BODY_BYTES) {
                    sendJson(exchange, 413, "{\"error\":\"Request body too large\"}");
                    throw new BodyTooLargeException();
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * Thrown by {@link #readBody(HttpExchange)} when a request body exceeds the size cap.
     * A 413 response has already been sent; handlers catch this in their generic {@code Exception}
     * block and simply stop (the response is committed, so nothing further should be written).
     */
    public static final class BodyTooLargeException extends IOException {
        public BodyTooLargeException() { super("Request body too large"); }
    }

    /**
     * The address the request came from, for {@code token.bind-ip} checks.
     * <p>
     * Deliberately the real peer address and not {@code X-Forwarded-For}: the header is
     * client-controlled unless a trusted proxy overwrites it, so honouring it would turn the
     * binding into a check the attacker gets to answer. Behind a reverse proxy every request
     * carries the proxy's address, which is why {@code token.bind-ip} defaults to off.
     */
    public static String remoteIp(HttpExchange exchange) {
        InetSocketAddress remote = exchange.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return null;
        }
        return remote.getAddress().getHostAddress();
    }

    /**
     * Parse simple query parameters from a URL.
     */
    public static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isBlank()) return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(kv[0], java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    /**
     * Extract a path segment. E.g., for "/api/skins/some-uuid/all", getPathSegment(path, 3) = "some-uuid"
     */
    public static String getPathSegment(String path, int index) {
        String[] parts = path.split("/");
        if (index < parts.length) return parts[index];
        return null;
    }
}

