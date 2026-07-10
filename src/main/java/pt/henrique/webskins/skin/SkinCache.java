package pt.henrique.webskins.skin;

import pt.henrique.webskins.model.SkinData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory cache for skin textures.
 * Entries expire after the configured TTL.
 */
public class SkinCache {

    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();
    private long ttlMillis;

    public SkinCache(long ttlSeconds) {
        this.ttlMillis = ttlSeconds * 1000;
    }

    public void setTtl(long ttlSeconds) {
        this.ttlMillis = ttlSeconds * 1000;
    }

    public SkinData get(UUID uuid) {
        CacheEntry entry = cache.get(uuid);
        if (entry == null) return null;
        if (System.currentTimeMillis() - entry.timestamp > ttlMillis) {
            cache.remove(uuid);
            return null;
        }
        return entry.data;
    }

    public void put(UUID uuid, SkinData data) {
        cache.put(uuid, new CacheEntry(data, System.currentTimeMillis()));
    }

    public void invalidate(UUID uuid) {
        cache.remove(uuid);
    }

    public void invalidateAll() {
        cache.clear();
    }

    private record CacheEntry(SkinData data, long timestamp) {}
}

