package adminarea.permissions;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Efficient caching system for permissions
 */
public class PermissionCache {
    private final Cache<String, Map<String, Boolean>> playerPermCache;
    private final Cache<String, Map<String, Boolean>> groupPermCache;
    private final Cache<String, Map<String, Boolean>> trackPermCache;
    
    private static final int CACHE_SIZE = 200;
    private static final long CACHE_DURATION = TimeUnit.MINUTES.toMillis(5);
    
    public PermissionCache() {
        this.playerPermCache = buildCache();
        this.groupPermCache = buildCache();
        this.trackPermCache = buildCache();
    }
    
    private <K, V> Cache<K, V> buildCache() {
        return Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_DURATION, TimeUnit.MILLISECONDS)
            .build();
    }
    
    // Player permissions
    public Map<String, Boolean> getPlayerPermissions(String cacheKey) {
        return playerPermCache.getIfPresent(cacheKey);
    }
    
    public void cachePlayerPermissions(String cacheKey, Map<String, Boolean> permissions) {
        playerPermCache.put(cacheKey, new HashMap<>(permissions));
    }
    
    public void invalidatePlayerPermissions(String cacheKey) {
        playerPermCache.invalidate(cacheKey);
    }
    
    // Group permissions
    public Map<String, Boolean> getGroupPermissions(String cacheKey) {
        return groupPermCache.getIfPresent(cacheKey);
    }
    
    public void cacheGroupPermissions(String cacheKey, Map<String, Boolean> permissions) {
        groupPermCache.put(cacheKey, new HashMap<>(permissions));
    }
    
    public void invalidateGroupPermissions(String cacheKey) {
        groupPermCache.invalidate(cacheKey);
    }
    
    // Track permissions
    public Map<String, Boolean> getTrackPermissions(String cacheKey) {
        return trackPermCache.getIfPresent(cacheKey);
    }
    
    public void cacheTrackPermissions(String cacheKey, Map<String, Boolean> permissions) {
        trackPermCache.put(cacheKey, new HashMap<>(permissions));
    }
    
    public void invalidateTrackPermissions(String cacheKey) {
        trackPermCache.invalidate(cacheKey);
    }
    
    // Combined operations
    public void invalidateArea(String areaName) {
        // Invalidate all keys that start with this area name
        playerPermCache.asMap().keySet().removeIf(key -> key.startsWith(areaName.toLowerCase() + ":"));
        groupPermCache.asMap().keySet().removeIf(key -> key.startsWith(areaName.toLowerCase() + ":"));
        trackPermCache.asMap().keySet().removeIf(key -> key.startsWith(areaName.toLowerCase() + ":"));
    }
    
    public void invalidateAll() {
        playerPermCache.invalidateAll();
        groupPermCache.invalidateAll();
        trackPermCache.invalidateAll();
    }
    
    // Helper methods for creating cache keys
    public static String createPlayerKey(String areaName, String playerName) {
        return areaName + ":" + playerName;
    }
    
    public static String createGroupKey(String areaName, String groupName) {
        return areaName + ":" + groupName;
    }
    
    public static String createTrackKey(String areaName, String trackName) {
        return areaName + ":" + trackName;
    }
    
    /**
     * Get all player names that have cached permissions for a specific area
     * 
     * @param areaPrefix The area prefix to search for (lowercase)
     * @return Set of player names with cached permissions for this area
     */
    public Set<String> getPlayerKeysForArea(String areaPrefix) {
        if (areaPrefix == null) {
            return Collections.emptySet();
        }
        
        String prefix = areaPrefix.toLowerCase() + ":player:";
        Set<String> result = new HashSet<>();
        
        playerPermCache.asMap().keySet().forEach(key -> {
            if (key.startsWith(prefix)) {
                // Extract player name from key format "areaname:player:playername"
                String playerName = key.substring(prefix.length());
                if (!playerName.isEmpty()) {
                    result.add(playerName);
                }
            }
        });
        
        return result;
    }
} 