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
     */
    public String get(String key, String... replacements) {
        String msg = messages.getOrDefault(key, key);

        // Apply replacements in pairs: key, value, key, value...
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            msg = msg.replace(replacements[i], replacements[i + 1]);
        }

        return colorize(msg);
    }

    /**
     * Get prefixed message.
     */
    public String getPrefixed(String key, String... replacements) {
        return get("prefix") + get(key, replacements);
    }

    /**
     * Translate & color codes.
     */
    private String colorize(String text) {
        if (text == null) return "";
        // Support & color codes → §
        return text.replace('&', '§');
    }
}

