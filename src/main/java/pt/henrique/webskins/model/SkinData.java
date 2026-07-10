package pt.henrique.webskins.model;

/**
 * Immutable record holding signed Minecraft skin texture data.
 */
public record SkinData(
        String textureValue,
        String textureSignature,
        String modelType,   // "default" or "slim"
        String source,      // "editor", "upload", "import", "preset"
        long timestamp
) {
    public boolean isValid() {
        return textureValue != null && !textureValue.isBlank()
                && textureSignature != null && !textureSignature.isBlank();
    }
}

