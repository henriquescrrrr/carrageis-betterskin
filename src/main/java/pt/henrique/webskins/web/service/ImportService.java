package pt.henrique.webskins.web.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.logging.Logger;

/**
 * Imports skins from Minecraft usernames via the Mojang API.
 */
public class ImportService {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** Hard cap on how many bytes we will download from a user-supplied URL. */
    private static final long MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024; // 2 MB

    /**
     * Fetch the skin of a paid Minecraft account by username.
     * Returns [textureValue, textureSignature, skinUrl] or null if not found.
     */
    public static String[] importFromUsername(String username, Logger log) {
        try {
            // Step 1: Get UUID from username
            HttpRequest uuidReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + username))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> uuidResp = HTTP.send(uuidReq, HttpResponse.BodyHandlers.ofString());
            if (uuidResp.statusCode() != 200) {
                log.info("Mojang username lookup failed for '" + username + "': " + uuidResp.statusCode());
                return null;
            }

            JsonObject uuidData = JsonParser.parseString(uuidResp.body()).getAsJsonObject();
            String mojangUuid = uuidData.get("id").getAsString();

            // Step 2: Get profile with signed textures
            HttpRequest profileReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + mojangUuid + "?unsigned=false"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> profileResp = HTTP.send(profileReq, HttpResponse.BodyHandlers.ofString());
            if (profileResp.statusCode() != 200) {
                log.info("Mojang profile lookup failed for " + mojangUuid + ": " + profileResp.statusCode());
                return null;
            }

            JsonObject profile = JsonParser.parseString(profileResp.body()).getAsJsonObject();
            JsonArray properties = profile.getAsJsonArray("properties");

            for (var elem : properties) {
                JsonObject prop = elem.getAsJsonObject();
                if ("textures".equals(prop.get("name").getAsString())) {
                    String value = prop.get("value").getAsString();
                    String signature = prop.has("signature") ? prop.get("signature").getAsString() : "";

                    // Decode the texture value to get the skin URL
                    String decoded = new String(Base64.getDecoder().decode(value));
                    JsonObject texturesJson = JsonParser.parseString(decoded).getAsJsonObject();
                    String skinUrl = "";
                    if (texturesJson.has("textures") &&
                        texturesJson.getAsJsonObject("textures").has("SKIN")) {
                        skinUrl = texturesJson.getAsJsonObject("textures")
                                .getAsJsonObject("SKIN").get("url").getAsString();
                    }

                    return new String[]{value, signature, skinUrl};
                }
            }

            log.info("No textures property found for " + username);
            return null;
        } catch (Exception e) {
            log.warning("Import from username '" + username + "' failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Fetch an image from a user-supplied URL. Returns the image bytes, or null on failure.
     * <p>
     * SSRF hardening: only {@code http}/{@code https} URLs pointing at a public host are
     * allowed. Requests to loopback, link-local (incl. the 169.254.169.254 cloud metadata
     * endpoint), private/site-local and other non-routable addresses are rejected so a
     * player cannot make the server probe the internal network. Redirects are disabled and
     * the response body is capped at {@link #MAX_DOWNLOAD_BYTES} to prevent memory-exhaustion
     * DoS via oversized downloads.
     */
    public static byte[] fetchImageFromUrl(String url, Logger log) {
        final URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            log.info("Rejected image URL (malformed): " + url);
            return null;
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            log.info("Rejected image URL (scheme must be http/https): " + url);
            return null;
        }
        if (uri.getHost() == null || !isPublicHost(uri.getHost(), log)) {
            log.info("Rejected image URL (host is not a public address): " + url);
            return null;
        }

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "BetterSkin/1.0")
                    .GET()
                    .build();

            HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                log.info("Failed to fetch image from URL: " + resp.statusCode());
                return null;
            }

            try (InputStream in = resp.body()) {
                return readCapped(in, MAX_DOWNLOAD_BYTES);
            }
        } catch (Exception e) {
            log.warning("Failed to fetch image from URL '" + url + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Resolve a host and verify that EVERY resolved address is a routable public address.
     * Rejects loopback, any-local, link-local (169.254/16, fe80::/10), site-local/private
     * (10/8, 172.16/12, 192.168/16, fc00::/7) and multicast addresses.
     */
    private static boolean isPublicHost(String host, Logger log) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) return false;
            for (InetAddress addr : addresses) {
                if (addr.isAnyLocalAddress() || addr.isLoopbackAddress()
                        || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                        || addr.isMulticastAddress()) {
                    return false;
                }
                // Unique-local IPv6 (fc00::/7) is not covered by isSiteLocalAddress().
                byte[] raw = addr.getAddress();
                if (raw.length == 16 && (raw[0] & 0xFE) == 0xFC) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.info("Could not resolve host '" + host + "': " + e.getMessage());
            return false;
        }
    }

    /** Read at most {@code max} bytes from the stream; returns null if the limit is exceeded. */
    private static byte[] readCapped(InputStream in, long max) throws java.io.IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > max) return null; // too large — refuse
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}

