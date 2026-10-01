package pt.henrique.webskins.config;

import org.bukkit.configuration.file.YamlConfiguration;
import pt.henrique.webskins.WebSkins;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages localized messages from lang/*.yml files.
 */
public class LangManager {

    private final WebSkins plugin;
    private final Map<String, String> messages = new HashMap<>();

    public LangManager(WebSkins plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        messages.clear();
        String locale = plugin.getConfigManager().getLocale();

        // Try to load from data folder first (user overrides)
        File langFile = new File(plugin.getDataFolder(), "lang/" + locale + ".yml");
        YamlConfiguration lang;

        if (langFile.exists()) {
            lang = YamlConfiguration.loadConfiguration(langFile);
        } else {
            // Load from resources
            InputStream stream = plugin.getResource("lang/" + locale + ".yml");
            if (stream == null) {
                // Fallback to the default locale (pt_PT)
                stream = plugin.getResource("lang/pt_PT.yml");
            }
            if (stream == null) {
                plugin.getLogger().severe("No language file found!");
                return;
            }
            lang = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }

        for (String key : lang.getKeys(false)) {
            messages.put(key, lang.getString(key, key));
        }
    }

    /**
     * Get a message by key, applying color codes and placeholders.
     * <p>
     * The template is colorized first and the values are substituted after, so a value can
     * neither be read as a colour code nor be damaged by the conversion. That matters for
     * {@code %link%}, which is a URL and may legitimately contain {@code &}, and for
     * {@code %player%}, which on an offline-mode server is not a validated string.
     */
    public String get(String key, String... replacements) {
        String msg = colorize(messages.getOrDefault(key, key));

        // Apply replacements in pairs: key, value, key, value...
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            msg = msg.replace(replacements[i], stripSectionSigns(replacements[i + 1]));
        }

        return msg;
    }

    /**
     * Get prefixed message.
     */
    public String getPrefixed(String key, String... replacements) {
        return get("prefix") + get(key, replacements);
    }

    /**
     * Translate {@code &} colour codes to {@code §}.
     * <p>
     * Only an {@code &} followed by a legacy code character is converted. Replacing every
     * {@code &} would turn ordinary text in an operator-edited lang file ("Skins &amp; Capes")
     * into a broken format code that swallows the next character.
     */
    private String colorize(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' && i + 1 < text.length() && isLegacyCode(text.charAt(i + 1))) {
                out.append('§').append(Character.toLowerCase(text.charAt(i + 1)));
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isLegacyCode(char c) {
        char lower = Character.toLowerCase(c);
        return (lower >= '0' && lower <= '9') || (lower >= 'a' && lower <= 'f')
                || (lower >= 'k' && lower <= 'o') || lower == 'r';
    }

    /** Values are not templates: a raw § in one would be an unintended format code. */
    private static String stripSectionSigns(String value) {
        return value == null ? "" : value.replace("§", "");
    }
}

