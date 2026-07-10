package pt.henrique.webskins.web.service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;

/**
 * Validates and normalizes Minecraft skin images.
 * Skins must be 64x64 (modern) or 64x32 (legacy) PNG images.
 */
public class ImageValidator {

    private static final int MAX_FILE_SIZE = 2 * 1024 * 1024; // 2 MB

    /**
     * Validate a base64-encoded PNG image.
     * Returns null if valid, or an error message if invalid.
     */
    public static String validate(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return "No image data provided.";
        }
        if (imageBytes.length > MAX_FILE_SIZE) {
            return "Image too large. Max 2 MB.";
        }

        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            if (iis == null) {
                return "Invalid image file. Must be PNG.";
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return "Invalid image file. Must be PNG.";
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                // Read ONLY the header dimensions first. This avoids decoding the full
                // raster of a "decompression bomb" (a tiny file that declares huge
                // dimensions, e.g. 20000x20000) which would otherwise allocate gigabytes
                // of heap during ImageIO.read() and crash the server.
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (!((w == 64 && h == 64) || (w == 64 && h == 32))) {
                    return "Invalid dimensions " + w + "x" + h + ". Must be 64x64 or 64x32.";
                }
                return null; // valid!
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            return "Could not read image: " + e.getMessage();
        }
    }

    /**
     * Normalize a skin image to 64x64.
     * If the input is 64x32 (legacy), it's expanded to 64x64 with transparent bottom.
     * Returns base64-encoded PNG.
     */
    public static String normalizeToBase64(byte[] imageBytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (img == null) throw new IOException("Invalid image");

        BufferedImage output;
        if (img.getWidth() == 64 && img.getHeight() == 32) {
            // Expand legacy 64x32 to 64x64
            output = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = output.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.drawImage(img, 0, 0, null);
            g.dispose();
        } else {
            output = img;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(output, "png", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    /**
     * Decode base64 to bytes.
     */
    public static byte[] decodeBase64(String base64) {
        // Strip data URL prefix if present
        if (base64.contains(",")) {
            base64 = base64.substring(base64.indexOf(",") + 1);
        }
        return Base64.getDecoder().decode(base64);
    }
}

