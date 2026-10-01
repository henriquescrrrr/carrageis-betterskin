package pt.henrique.webskins.config;

import org.bukkit.configuration.file.FileConfiguration;
import pt.henrique.webskins.WebSkins;

/**
 * Manages plugin config.yml values with typed accessors.
 */
public class ConfigManager {

    private final WebSkins plugin;

    // Server
    private int serverPort;
    private String serverBindAddress;
    private String publicUrl;

    // MineSkin
    private String mineskinApiKey;
    private String mineskinApiUrl;

    // Token
    private int tokenExpirySeconds;
    private boolean tokenBindIp;

    // Join
    private String joinSendLinkMode;
    private int joinLinkDelayTicks;

    // Skin
    private String applyMode;
    private int cacheTtlSeconds;

    // Language
    private String locale;

    public ConfigManager(WebSkins plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        serverPort = cfg.getInt("server.port", 4567);
        // Default keeps the pre-existing behaviour (all interfaces), because players reach
        // this server from their own browsers. Operators who front it with a reverse proxy
        // should set 127.0.0.1 so nothing but the proxy can reach the API.
        serverBindAddress = cfg.getString("server.bind-address", "0.0.0.0");
        publicUrl = cfg.getString("server.public-url", "http://localhost:4567");
        // Remove trailing slash if present
        if (publicUrl.endsWith("/")) {
            publicUrl = publicUrl.substring(0, publicUrl.length() - 1);
        }

        mineskinApiKey = cfg.getString("mineskin.api-key", "");
        mineskinApiUrl = cfg.getString("mineskin.api-url", "https://api.mineskin.org");

        tokenExpirySeconds = cfg.getInt("token.expiry-seconds", 300);
        tokenBindIp = cfg.getBoolean("token.bind-ip", false);

        joinSendLinkMode = cfg.getString("join.send-link-mode", "ALWAYS").toUpperCase();
        // Backwards-compat: old boolean "send-link: true/false" maps to ALWAYS / NEVER
        if (cfg.isBoolean("join.send-link")) {
            joinSendLinkMode = cfg.getBoolean("join.send-link", true) ? "ALWAYS" : "NEVER";
        }
        joinLinkDelayTicks = cfg.getInt("join.link-delay-ticks", 40);

        applyMode = cfg.getString("skin.apply-mode", "INSTANT");
        cacheTtlSeconds = cfg.getInt("skin.cache-ttl-seconds", 600);

        locale = cfg.getString("locale", "pt_PT");
    }

    public int getServerPort()          { return serverPort; }
    public String getServerBindAddress() { return serverBindAddress; }
    public String getPublicUrl()        { return publicUrl; }
    public String getMineskinApiKey()   { return mineskinApiKey; }
    public String getMineskinApiUrl()   { return mineskinApiUrl; }
    public int getTokenExpirySeconds()  { return tokenExpirySeconds; }
    public boolean isTokenBindIp()      { return tokenBindIp; }
    public String getJoinSendLinkMode() { return joinSendLinkMode; }
    public int getJoinLinkDelayTicks()  { return joinLinkDelayTicks; }
    public String getApplyMode()        { return applyMode; }
    public int getCacheTtlSeconds()     { return cacheTtlSeconds; }
    public String getLocale()           { return locale; }
}

