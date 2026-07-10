package pt.henrique.webskins.web.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import pt.henrique.webskins.WebSkins;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Signs skin textures via the MineSkin API.
 * This is required so Minecraft clients accept the skin as valid.
 */
public class MineSkinService {

    private final WebSkins plugin;
    private final Logger log;
    private final HttpClient httpClient;

    public MineSkinService(WebSkins plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * Sign a skin from a URL. Returns [textureValue, textureSignature] or null on failure.
     */
    public String[] signFromUrl(String imageUrl, String variant) {
        String apiKey = plugin.getConfigManager().getMineskinApiKey();
        String apiUrl = plugin.getConfigManager().getMineskinApiUrl();

        if (apiKey == null || apiKey.isBlank()) {
            log.warning("MineSkin API key not configured! Skins cannot be signed.");
            return null;
        }

        try {
            JsonObject body = new JsonObject();
            body.addProperty("url", imageUrl);
            body.addProperty("variant", variant.equals("slim") ? "slim" : "classic");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/generate/url"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("User-Agent", "BetterSkin/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                log.warning("MineSkin rate limited! Wait a minute before trying again.");
                return null;
            }

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warning("MineSkin error (" + response.statusCode() + "): " + response.body());
                return null;
            }

            return parseMineSkinResponse(response.body());
        } catch (Exception e) {
            log.warning("MineSkin signing failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Sign a skin from base64 image data. Returns [textureValue, textureSignature] or null on failure.
     */
    public String[] signFromBase64(String base64ImageData, String variant) {
        String apiKey = plugin.getConfigManager().getMineskinApiKey();
        String apiUrl = plugin.getConfigManager().getMineskinApiUrl();

        if (apiKey == null || apiKey.isBlank()) {
            log.warning("MineSkin API key not configured! Skins cannot be signed.");
            return null;
        }

        try {
            byte[] imageBytes = java.util.Base64.getDecoder().decode(base64ImageData);

            // Build multipart form data
            String boundary = "----WebSkinsBoundary" + System.currentTimeMillis();
            StringBuilder sb = new StringBuilder();

            // File part
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n");
            sb.append("Content-Type: image/png\r\n\r\n");

            byte[] header = sb.toString().getBytes();
            String variantPart = "\r\n--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"variant\"\r\n\r\n"
                    + (variant.equals("slim") ? "slim" : "classic")
                    + "\r\n--" + boundary + "--\r\n";
            byte[] footer = variantPart.getBytes();

            byte[] bodyBytes = new byte[header.length + imageBytes.length + footer.length];
            System.arraycopy(header, 0, bodyBytes, 0, header.length);
            System.arraycopy(imageBytes, 0, bodyBytes, header.length, imageBytes.length);
            System.arraycopy(footer, 0, bodyBytes, header.length + imageBytes.length, footer.length);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/generate/upload"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("User-Agent", "BetterSkin/1.0")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                log.warning("MineSkin rate limited! Wait a minute before trying again.");
                return null;
            }

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warning("MineSkin error (" + response.statusCode() + "): " + response.body());
                return null;
            }

            return parseMineSkinResponse(response.body());
        } catch (Exception e) {
            log.warning("MineSkin signing failed: " + e.getMessage());
            return null;
        }
    }

    private String[] parseMineSkinResponse(String responseBody) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonObject texture = json.getAsJsonObject("data").getAsJsonObject("texture");
            String value = texture.get("value").getAsString();
            String signature = texture.get("signature").getAsString();
            return new String[]{value, signature};
        } catch (Exception e) {
            log.warning("Failed to parse MineSkin response: " + e.getMessage());
            return null;
        }
    }
}

