package adminarea.permissions;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import cn.nukkit.Player;
import cn.nukkit.level.Position;
import io.micrometer.core.instrument.Timer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

public class PermissionChecker {
    private final AdminAreaProtectionPlugin plugin;
    private final Cache<String, Boolean> permissionCache;
    private static final int CACHE_SIZE = 2000; // Increased from 1000 for better hit rate
    private static final long CACHE_EXPIRY = TimeUnit.MINUTES.toMillis(10); // Increased from 5 minutes
    private final Map<String, Long> lastPermissionCheck = new ConcurrentHashMap<>();
    private static final long CHECK_INTERVAL = 100; // ms between checks for the same permission
    
    // Common permission prefix to avoid string concatenation in hot paths
    private static final String GUI_PERMISSIONS_PREFIX = "gui.permissions.toggles.";
    private static final String BYPASS_PREFIX = "adminarea.bypass.";
    
    public PermissionChecker(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.permissionCache = Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_EXPIRY, TimeUnit.MILLISECONDS)
            .build();
    }
    
    /**
     * Normalizes permission names to ensure consistent format with prefix
     * 
     * @param permission Permission to normalize
     * @return Normalized permission with proper prefix
     */
    private String normalizePermission(String permission) {
        if (permission == null || permission.isEmpty()) {
            return GUI_PERMISSIONS_PREFIX + "default";
        }
        
        // Most efficient check first - if already has prefix, return as is
        if (permission.startsWith(GUI_PERMISSIONS_PREFIX)) {
            return permission;
        }
        
        // Skip special permission types that shouldn't be prefixed
        if (permission.contains("adminarea.") || permission.contains("group.")) {
            return permission;
        }
        
        // Add prefix for simple permission names
        return GUI_PERMISSIONS_PREFIX + permission;
    }
    
    /**
     * Checks if a player is allowed to perform an action in an area
     * 
     * @param player The player to check, can be null for environment actions
     * @param area The area to check
     * @param permission The permission to check
     * @return true if allowed, false if denied
     */
    public boolean isAllowed(Player player, Area area, String permission) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Quick bypass check first
            if (player != null && plugin.isBypassing(player.getName())) {
                return true;
            }

            // If area is null, allow the action by default
            if (area == null) {
                return true;
            }

            // Normalize permission name once
            String normalizedPermission = normalizePermission(permission);
            
            // Build cache key (minimal string operations)
            String cacheKey = area.getName() + ":" + normalizedPermission + (player != null ? ":" + player.getName() : "");

            // Check cache first
            Boolean cached = permissionCache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }
            
            // Rate limit permission checks for the same key
            long now = System.currentTimeMillis();
            Long lastCheck = lastPermissionCheck.get(cacheKey);
            if (lastCheck != null && now - lastCheck < CHECK_INTERVAL) {
                // Return default if no cached result (shouldn't happen often)
                return true;
            }
            lastPermissionCheck.put(cacheKey, now);

            // Player bypass checks
            if (player != null) {
                // Check area bypass
                if (player.hasPermission(BYPASS_PREFIX + area.getName())) {
                    permissionCache.put(cacheKey, true);
                    return true;
                }
                
                // Check action bypass
                String permWithoutPrefix = normalizedPermission.startsWith(GUI_PERMISSIONS_PREFIX) ?
                    normalizedPermission.substring(GUI_PERMISSIONS_PREFIX.length()) : normalizedPermission;
                if (player.hasPermission(BYPASS_PREFIX + permWithoutPrefix)) {
                    permissionCache.put(cacheKey, true);
                    return true;
                }
            }

            // Get the toggle state
            boolean allowed = area.getToggleState(normalizedPermission);
            
            // Check player and group permissions if allowed by toggle
            if (allowed && player != null) {
                // Check player-specific permissions first (most specific)
                Map<String, Map<String, Boolean>> playerPermissions = area.getPlayerPermissions();
                Map<String, Boolean> playerPerms = playerPermissions.get(player.getName());
                if (playerPerms != null && playerPerms.containsKey(normalizedPermission)) {
                    allowed = playerPerms.get(normalizedPermission);
                } else {
                    // Check group permissions (can override)
                    Map<String, Map<String, Boolean>> groupPermissions = area.getGroupPermissions();
                    for (Map.Entry<String, Map<String, Boolean>> entry : groupPermissions.entrySet()) {
                        if (player.hasPermission("group." + entry.getKey())) {
                            Map<String, Boolean> groupPerms = entry.getValue();
                            if (groupPerms.containsKey(normalizedPermission)) {
                                // Group permissions override player permissions if more restrictive
                                allowed = allowed && groupPerms.get(normalizedPermission);
                            }
                        }
                    }
                }
            }

            // Cache the result
            permissionCache.put(cacheKey, allowed);
            return allowed;
            
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "permission_check");
        }
    }
    
    /**
     * Checks if a player is allowed to perform an action at a specific position
     * 
     * @param pos The position to check
     * @param player The player to check
     * @param permission The permission to check
     * @return true if allowed, false if denied
     */
    public boolean isAllowed(Position pos, Player player, String permission) {
        // Fail fast for null position
        if (pos == null || pos.getLevel() == null) {
            return true;
        }
        
        // Get the highest priority area at this position
        Area area = plugin.getAreaManager().getHighestPriorityArea(
            pos.getLevel().getName(),
            pos.getX(),
            pos.getY(),
            pos.getZ()
        );
        
        // If no area, allow the action by default
        if (area == null) {
            return true;
        }
        
        // Check permission in the area
        return isAllowed(player, area, permission);
    }
    
    /**
     * Invalidates the permission cache for a specific area
     *
     * @param areaName The name of the area
     */
    public void invalidateCache(String areaName) {
        if (areaName == null || areaName.isEmpty()) {
            invalidateCache();
            return;
        }

        if (plugin.isDebugMode()) {
            plugin.debug("Invalidating permission cache for area: " + areaName);
        }

        // Batch removals for better performance
        String prefix = areaName.toLowerCase() + ":";
        lastPermissionCheck.keySet().removeIf(key -> key.startsWith(prefix));
        permissionCache.asMap().keySet().removeIf(key -> key.startsWith(prefix));
    }
    
    /**
     * Invalidates the entire permission cache
     */
    public void invalidateCache() {
        if (plugin.isDebugMode()) {
            plugin.debug("Invalidating entire permission cache");
        }
        lastPermissionCheck.clear();
        permissionCache.invalidateAll();
    }
    
    /**
     * Performs maintenance on the cache by removing expired entries
     * Call this periodically (e.g., once per minute) to avoid memory leaks
     */
    public void cleanupCache() {
        // Remove old entries from lastPermissionCheck to prevent memory leaks
        long now = System.currentTimeMillis();
        lastPermissionCheck.entrySet().removeIf(entry -> now - entry.getValue() > CHECK_INTERVAL * 100);
    }
}