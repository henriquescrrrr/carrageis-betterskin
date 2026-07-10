package pt.henrique.webskins;

import org.bukkit.plugin.java.JavaPlugin;
import pt.henrique.webskins.auth.TokenManager;
import pt.henrique.webskins.command.SkinCommand;
import pt.henrique.webskins.config.ConfigManager;
import pt.henrique.webskins.config.LangManager;
import pt.henrique.webskins.database.Database;
import pt.henrique.webskins.listener.PlayerJoinListener;
import pt.henrique.webskins.listener.PlayerQuitListener;
import pt.henrique.webskins.skin.SkinApplier;
import pt.henrique.webskins.skin.SkinCache;
import pt.henrique.webskins.web.WebServer;
import pt.henrique.webskins.web.service.MineSkinService;

import java.util.Objects;

/**
 * BetterSkin — Browser-based skin management for offline-mode Minecraft servers.
 * <p>
 * Everything runs inside this plugin:
 * - SQLite database (stored in plugins/BetterSkin/betterskin.db)
 * - Embedded web server (serves the browser UI on the configured port)
 * - Skin signing via MineSkin API
 * <p>
 * No external software needed — just drop the jar in plugins/ and go!
 */
public final class WebSkins extends JavaPlugin {

    private ConfigManager configManager;
    private LangManager langManager;
    private Database database;
    private TokenManager tokenManager;
    private SkinCache skinCache;
    private SkinApplier skinApplier;
    private MineSkinService mineSkinService;
    private WebServer webServer;

    @Override
    public void onEnable() {
        // Save default configs and lang files
        saveDefaultConfig();
        saveResource("lang/en_US.yml", false);
        saveResource("lang/pt_PT.yml", false);

        // Initialize config
        configManager = new ConfigManager(this);
        langManager = new LangManager(this);

        // Initialize database
        database = new Database(this);
        try {
            database.open();
        } catch (Exception e) {
            getLogger().severe("Failed to open database! Plugin cannot function.");
            getLogger().severe(e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize managers
        tokenManager = new TokenManager(this);
        skinCache = new SkinCache(configManager.getCacheTtlSeconds());
        skinApplier = new SkinApplier(this);
        mineSkinService = new MineSkinService(this);

        // Register command
        SkinCommand skinCommand = new SkinCommand(this);
        Objects.requireNonNull(getCommand("skin")).setExecutor(skinCommand);
        Objects.requireNonNull(getCommand("skin")).setTabCompleter(skinCommand);

        // Register listeners
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(this), this);

        // Start the embedded web server
        webServer = new WebServer(this);
        webServer.start();

        // Schedule periodic token cleanup (every 10 minutes)
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                int cleaned = database.cleanExpiredTokens();
                if (cleaned > 0) {
                    getLogger().info("Cleaned " + cleaned + " expired tokens.");
                }
            } catch (Exception e) {
                getLogger().warning("Token cleanup error: " + e.getMessage());
            }
        }, 20 * 60 * 10, 20 * 60 * 10); // Every 10 minutes

        // Warn if MineSkin API key is not set
        if (configManager.getMineskinApiKey() == null || configManager.getMineskinApiKey().isBlank()) {
            getLogger().warning("========================================");
            getLogger().warning("  MineSkin API key is NOT configured!");
            getLogger().warning("  Custom skins will NOT work until you");
            getLogger().warning("  add your key to config.yml");
            getLogger().warning("  Get a free key: https://account.mineskin.org/keys");
            getLogger().warning("========================================");
        }

        getLogger().info("BetterSkin enabled!");
        getLogger().info("Players can manage skins at: " + configManager.getPublicUrl());
    }

    @Override
    public void onDisable() {
        if (webServer != null) webServer.stop();
        if (database != null) database.close();
        if (skinCache != null) skinCache.invalidateAll();
        getLogger().info("BetterSkin disabled.");
    }

    // Accessors
    public ConfigManager getConfigManager()    { return configManager; }
    public LangManager getLangManager()         { return langManager; }
    public Database getDatabase()               { return database; }
    public TokenManager getTokenManager()       { return tokenManager; }
    public SkinCache getSkinCache()             { return skinCache; }
    public SkinApplier getSkinApplier()         { return skinApplier; }
    public MineSkinService getMineSkinService() { return mineSkinService; }
}

