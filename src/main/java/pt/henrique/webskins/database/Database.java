package pt.henrique.webskins.database;

import pt.henrique.webskins.WebSkins;
import pt.henrique.webskins.model.SkinData;

import java.io.File;
import java.security.SecureRandom;
import java.sql.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

/**
 * SQLite database for storing players, skins, auth tokens, and presets.
 * The database file is stored in the plugin's data folder.
 * No external database server needed!
 */
public class Database {

    private final WebSkins plugin;
    private final Logger log;
    private Connection connection;
    private final SecureRandom random = new SecureRandom();

    public Database(WebSkins plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    // ========== LIFECYCLE ==========

    public void open() throws SQLException {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) dataFolder.mkdirs();

        String url = "jdbc:sqlite:" + new File(dataFolder, "betterskin.db").getAbsolutePath();
        connection = DriverManager.getConnection(url);

        // Enable WAL mode for better concurrent reads
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA foreign_keys=ON");
        }

        createTables();
        log.info("Database opened: betterskin.db");
    }

    public void close() {
        if (connection != null) {
            try {
                connection.close();
                log.info("Database closed.");
            } catch (SQLException e) {
                log.warning("Error closing database: " + e.getMessage());
            }
        }
    }

    private synchronized Connection conn() throws SQLException {
        if (connection == null || connection.isClosed()) {
            open();
        }
        return connection;
    }

    private void createTables() throws SQLException {
        try (Statement stmt = conn().createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS players (
                    uuid       TEXT PRIMARY KEY,
                    username   TEXT NOT NULL,
                    locale     TEXT DEFAULT 'en',
                    created_at INTEGER DEFAULT (strftime('%s','now')),
                    updated_at INTEGER DEFAULT (strftime('%s','now'))
                )
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS auth_tokens (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    token       TEXT UNIQUE NOT NULL,
                    player_uuid TEXT NOT NULL REFERENCES players(uuid),
                    ip_bind     TEXT,
                    expires_at  INTEGER NOT NULL,
                    used_at     INTEGER,
                    created_at  INTEGER DEFAULT (strftime('%s','now'))
                )
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS skins (
                    id                INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid       TEXT NOT NULL REFERENCES players(uuid),
                    texture_value     TEXT NOT NULL,
                    texture_signature TEXT NOT NULL,
                    model_type        TEXT DEFAULT 'DEFAULT',
                    source            TEXT DEFAULT 'UPLOAD',
                    image_url         TEXT,
                    active            INTEGER DEFAULT 0,
                    created_at        INTEGER DEFAULT (strftime('%s','now'))
                )
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS presets (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    name        TEXT NOT NULL,
                    owner_uuid  TEXT REFERENCES players(uuid),
                    skin_id     INTEGER NOT NULL REFERENCES skins(id),
                    is_global   INTEGER DEFAULT 0,
                    tags        TEXT DEFAULT '[]',
                    created_at  INTEGER DEFAULT (strftime('%s','now'))
                )
            """);

            // Index for fast skin lookups
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_skins_player ON skins(player_uuid, active)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tokens_token ON auth_tokens(token)");
        }
    }

    // ========== PLAYERS ==========

    public synchronized void upsertPlayer(String uuid, String username) throws SQLException {
        String sql = """
            INSERT INTO players (uuid, username) VALUES (?, ?)
            ON CONFLICT(uuid) DO UPDATE SET username = ?, updated_at = strftime('%s','now')
        """;
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.setString(2, username);
            ps.setString(3, username);
            ps.executeUpdate();
        }
    }

    /**
     * Check whether a player already exists in the database.
     * Returns true if they have never been seen before.
     */
    public synchronized boolean isNewPlayer(String uuid) throws SQLException {
        String sql = "SELECT 1 FROM players WHERE uuid = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, uuid);
            return !ps.executeQuery().next();
        }
    }

    // ========== AUTH TOKENS ==========

    /**
     * Create a new auth token. Returns the token string.
     */
    public synchronized String createToken(String playerUuid, String username, int expirySeconds, String ip) throws SQLException {
        upsertPlayer(playerUuid, username);

        byte[] bytes = new byte[36];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        long expiresAt = System.currentTimeMillis() / 1000 + expirySeconds;

        String sql = "INSERT INTO auth_tokens (token, player_uuid, ip_bind, expires_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, token);
            ps.setString(2, playerUuid);
            ps.setString(3, ip);
            ps.setLong(4, expiresAt);
            ps.executeUpdate();
        }

        return token;
    }

    /**
     * Verify a token. Returns [playerUuid, username] or null if invalid/expired.
     */
    public synchronized String[] verifyToken(String token) throws SQLException {
        String sql = """
            SELECT t.player_uuid, p.username, t.expires_at, t.used_at
            FROM auth_tokens t JOIN players p ON t.player_uuid = p.uuid
            WHERE t.token = ?
        """;
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, token);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;

            long expiresAt = rs.getLong("expires_at");
            long now = System.currentTimeMillis() / 1000;
            if (now > expiresAt) return null;

            String playerUuid = rs.getString("player_uuid");
            String username = rs.getString("username");

            // Mark as used (first use only)
            if (rs.getObject("used_at") == null) {
                try (PreparedStatement up = conn().prepareStatement(
                        "UPDATE auth_tokens SET used_at = strftime('%s','now') WHERE token = ?")) {
                    up.setString(1, token);
                    up.executeUpdate();
                }
            }

            return new String[]{playerUuid, username};
        }
    }

    /**
     * Clean up expired tokens.
     */
    public synchronized int cleanExpiredTokens() throws SQLException {
        String sql = "DELETE FROM auth_tokens WHERE expires_at < strftime('%s','now')";
        try (Statement stmt = conn().createStatement()) {
            return stmt.executeUpdate(sql);
        }
    }

    // ========== SKINS ==========

    /**
     * Get the active skin for a player. Returns null if none.
     */
    public synchronized SkinData getActiveSkin(String playerUuid) throws SQLException {
        String sql = "SELECT * FROM skins WHERE player_uuid = ? AND active = 1 ORDER BY created_at DESC LIMIT 1";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, playerUuid);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            return skinFromRow(rs);
        }
    }

    /**
     * Get the active skin record (with ID) for a player. Returns null if none.
     */
    public synchronized SkinRecord getActiveSkinRecord(String playerUuid) throws SQLException {
        String sql = "SELECT * FROM skins WHERE player_uuid = ? AND active = 1 ORDER BY created_at DESC LIMIT 1";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, playerUuid);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            return skinRecordFromRow(rs);
        }
    }

    /**
     * Get skin history for a player.
     */
    public synchronized List<SkinRecord> getSkinHistory(String playerUuid) throws SQLException {
        String sql = "SELECT * FROM skins WHERE player_uuid = ? ORDER BY created_at DESC LIMIT 50";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, playerUuid);
            ResultSet rs = ps.executeQuery();
            List<SkinRecord> list = new ArrayList<>();
            while (rs.next()) {
                list.add(skinRecordFromRow(rs));
            }
            return list;
        }
    }

    /**
     * Save a new skin and make it active. Returns the skin ID.
     */
    public synchronized int saveSkin(String playerUuid, String textureValue, String textureSignature,
                                     String modelType, String source) throws SQLException {
        // Deactivate all current skins
        try (PreparedStatement ps = conn().prepareStatement(
                "UPDATE skins SET active = 0 WHERE player_uuid = ? AND active = 1")) {
            ps.setString(1, playerUuid);
            ps.executeUpdate();
        }

        // Insert new skin
        String sql = "INSERT INTO skins (player_uuid, texture_value, texture_signature, model_type, source, active) VALUES (?, ?, ?, ?, ?, 1)";
        try (PreparedStatement ps = conn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, playerUuid);
            ps.setString(2, textureValue);
            ps.setString(3, textureSignature);
            ps.setString(4, modelType);
            ps.setString(5, source);
            ps.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys();
            return keys.next() ? keys.getInt(1) : -1;
        }
    }

    /**
     * Activate an existing skin by ID.
     */
    public synchronized boolean activateSkin(String playerUuid, int skinId) throws SQLException {
        // Verify the skin exists and belongs to this player
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT id FROM skins WHERE id = ? AND player_uuid = ?")) {
            ps.setInt(1, skinId);
            ps.setString(2, playerUuid);
            if (!ps.executeQuery().next()) return false;
        }

        // Deactivate all
        try (PreparedStatement ps = conn().prepareStatement(
                "UPDATE skins SET active = 0 WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid);
            ps.executeUpdate();
        }

        // Activate the chosen one
        try (PreparedStatement ps = conn().prepareStatement(
                "UPDATE skins SET active = 1 WHERE id = ?")) {
            ps.setInt(1, skinId);
            ps.executeUpdate();
        }

        return true;
    }

    /**
     * Check whether a skin id exists AND belongs to the given player.
     * Used to stop a player from referencing another player's skin id (e.g. when
     * creating a preset).
     */
    public synchronized boolean skinBelongsTo(int skinId, String playerUuid) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT 1 FROM skins WHERE id = ? AND player_uuid = ?")) {
            ps.setInt(1, skinId);
            ps.setString(2, playerUuid);
            return ps.executeQuery().next();
        }
    }

    /**
     * Get a skin by ID.
     */
    public synchronized SkinData getSkinById(int skinId) throws SQLException {
        String sql = "SELECT * FROM skins WHERE id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, skinId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            return skinFromRow(rs);
        }
    }

    // ========== PRESETS ==========

    public synchronized List<PresetRecord> getGlobalPresets() throws SQLException {
        String sql = "SELECT p.*, s.texture_value, s.model_type, s.source as skin_source FROM presets p JOIN skins s ON p.skin_id = s.id WHERE p.is_global = 1 ORDER BY p.created_at DESC";
        try (Statement stmt = conn().createStatement()) {
            ResultSet rs = stmt.executeQuery(sql);
            List<PresetRecord> list = new ArrayList<>();
            while (rs.next()) {
                list.add(presetFromRow(rs));
            }
            return list;
        }
    }

    public synchronized List<PresetRecord> getPlayerPresets(String playerUuid) throws SQLException {
        String sql = "SELECT p.*, s.texture_value, s.model_type, s.source as skin_source FROM presets p JOIN skins s ON p.skin_id = s.id WHERE p.owner_uuid = ? ORDER BY p.created_at DESC";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, playerUuid);
            ResultSet rs = ps.executeQuery();
            List<PresetRecord> list = new ArrayList<>();
            while (rs.next()) {
                list.add(presetFromRow(rs));
            }
            return list;
        }
    }

    public synchronized int createPreset(String name, int skinId, String ownerUuid, boolean isGlobal) throws SQLException {
        String sql = "INSERT INTO presets (name, skin_id, owner_uuid, is_global) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setInt(2, skinId);
            ps.setString(3, isGlobal ? null : ownerUuid);
            ps.setInt(4, isGlobal ? 1 : 0);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            return keys.next() ? keys.getInt(1) : -1;
        }
    }

    public synchronized boolean deletePreset(int id, String requestorUuid) throws SQLException {
        // Only allow deleting presets you own. Global/server presets (owner_uuid IS NULL)
        // must NOT be deletable by arbitrary players, otherwise any authenticated user
        // could wipe every server-wide preset (griefing).
        String sql = "DELETE FROM presets WHERE id = ? AND owner_uuid = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setString(2, requestorUuid);
            return ps.executeUpdate() > 0;
        }
    }

    // ========== HELPERS ==========

    private SkinData skinFromRow(ResultSet rs) throws SQLException {
        return new SkinData(
                rs.getString("texture_value"),
                rs.getString("texture_signature"),
                rs.getString("model_type"),
                rs.getString("source"),
                rs.getLong("created_at") * 1000
        );
    }

    private SkinRecord skinRecordFromRow(ResultSet rs) throws SQLException {
        return new SkinRecord(
                rs.getInt("id"),
                rs.getString("texture_value"),
                rs.getString("texture_signature"),
                rs.getString("model_type"),
                rs.getString("source"),
                rs.getInt("active") == 1,
                rs.getLong("created_at")
        );
    }

    private PresetRecord presetFromRow(ResultSet rs) throws SQLException {
        return new PresetRecord(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getInt("skin_id"),
                rs.getInt("is_global") == 1,
                rs.getString("model_type"),
                rs.getString("skin_source")
        );
    }

    // ========== RECORD TYPES ==========

    public record SkinRecord(int id, String textureValue, String textureSignature,
                             String modelType, String source, boolean active, long createdAt) {}

    public record PresetRecord(int id, String name, int skinId, boolean isGlobal,
                               String modelType, String skinSource) {}
}

