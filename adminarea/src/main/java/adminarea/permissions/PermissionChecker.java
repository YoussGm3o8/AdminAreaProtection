package adminarea.permissions;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaDTO;
import cn.nukkit.Player;
import cn.nukkit.level.Position;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

public class PermissionChecker {
    private final AdminAreaProtectionPlugin plugin;
    private final Cache<String, Boolean> permissionCache;
    private static final int CACHE_SIZE = 5000; // Increased for better hit rate
    private static final long CACHE_EXPIRY = TimeUnit.MINUTES.toMillis(2); // Reduced for more frequent updates
    private final Map<String, Long> lastPermissionCheck = new ConcurrentHashMap<>();
    private static final long CHECK_INTERVAL = 50; // Reduced to 50ms for more responsive updates
    
    // Permission prefixes - defined once to avoid string concatenation
    private static final String GUI_PERMISSIONS_PREFIX = "gui.permissions.toggles.";
    private static final String BYPASS_PREFIX = "adminarea.bypass.";
    private static final String ADMIN_PERMISSION = "adminarea.admin";
    
    // Cache statistics
    private final Timer permissionCheckTimer;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    
    public PermissionChecker(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.permissionCache = Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_EXPIRY, TimeUnit.MILLISECONDS)
            .recordStats() // Enable statistics for monitoring
            .build();
            
        // Initialize metrics using the registry from PerformanceMonitor
        MeterRegistry registry = plugin.getPerformanceMonitor().getRegistry();
        this.permissionCheckTimer = Timer.builder("permission_check_time")
            .description("Time taken to check permissions")
            .register(registry);
        this.cacheHits = Counter.builder("permission_cache_hits")
            .description("Number of permission cache hits")
            .register(registry);
        this.cacheMisses = Counter.builder("permission_cache_misses")
            .description("Number of permission cache misses")
            .register(registry);
    }

    /**
     * Main permission check method following strict priority order:
     * 1. Admin/bypass permissions
     * 2. Player-specific permissions
     * 3. LuckPerms group permissions 
     * 4. LuckPerms track permissions
     * 5. Area default permissions
     */
    public boolean isAllowed(Player player, Area area, String permission) {
        return doPermissionCheck(player, area, permission);
    }

    private boolean shouldRateLimit(String cacheKey) {
        long now = System.currentTimeMillis();
        Long lastCheck = lastPermissionCheck.get(cacheKey);
        
        if (lastCheck != null && now - lastCheck < CHECK_INTERVAL) {
            return true;
        }
        
        lastPermissionCheck.put(cacheKey, now);
        return false;
    }

    private String buildCacheKey(Area area, Player player, String permission) {
        StringBuilder key = new StringBuilder(64) // Pre-sized for efficiency
            .append(area.getName())
            .append(':')
            .append(permission);
            
        if (player != null) {
            key.append(':').append(player.getName());
        }
        
        return key.toString();
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
        
        // Handle special case shortcuts
        if (!permission.contains(".")) {
            return switch (permission) {
                case "break" -> GUI_PERMISSIONS_PREFIX + "allowBlockBreak";
                case "build" -> GUI_PERMISSIONS_PREFIX + "allowBlockPlace";
                case "container" -> GUI_PERMISSIONS_PREFIX + "allowContainer";
                case "interact" -> GUI_PERMISSIONS_PREFIX + "allowInteract";
                case "itemDrop" -> GUI_PERMISSIONS_PREFIX + "allowItemDrop";
                case "itemPickup" -> GUI_PERMISSIONS_PREFIX + "allowItemPickup";
                default -> GUI_PERMISSIONS_PREFIX + permission;
            };
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
     * Check player-specific permissions
     */
    private boolean checkPlayerSpecificPermissions(Player player, Area area, String normalizedPermission, String cacheKey) {
        Map<String, Map<String, Boolean>> playerPermissions = area.getPlayerPermissions();
        Map<String, Boolean> playerPerms = playerPermissions.get(player.getName());
        
        if (plugin.isDebugMode()) {
            plugin.debug("[PermissionChecker] Player permissions map: " + 
                       (playerPerms != null ? playerPerms.toString() : "null"));
        }
        
        if (playerPerms != null && playerPerms.containsKey(normalizedPermission)) {
            boolean playerAllowed = playerPerms.get(normalizedPermission);
            if (plugin.isDebugMode()) {
                plugin.debug("[PermissionChecker] Found player-specific permission: " + playerAllowed);
                plugin.debug("[PermissionChecker] DECISION: " + playerAllowed + " - storing in cache and returning");
            }
            permissionCache.put(cacheKey, playerAllowed);
            return true;
        }
        
        return false;
    }

    /**
     * Check group-based permissions
     */
    private boolean checkGroupPermissions(Player player, Area area, String normalizedPermission, String cacheKey) {
        Map<String, Map<String, Boolean>> groupPermissions = area.getGroupPermissions();
        
        if (plugin.isDebugMode()) {
            plugin.debug("[PermissionChecker] Checking group permissions. Groups: " + 
                       (groupPermissions != null ? groupPermissions.keySet() : "null"));
        }
        
        // If LuckPerms is available, use it to get player groups in priority order
        if (plugin.isLuckPermsEnabled()) {
            String primaryGroup = plugin.getPrimaryGroup(player);
            if (primaryGroup != null && groupPermissions.containsKey(primaryGroup)) {
                Map<String, Boolean> groupPerms = groupPermissions.get(primaryGroup);
                if (groupPerms.containsKey(normalizedPermission)) {
                    boolean groupAllowed = groupPerms.get(normalizedPermission);
                    if (plugin.isDebugMode()) {
                        plugin.debug("[PermissionChecker] Found LuckPerms primary group permission for " + 
                                   normalizedPermission + " in group " + primaryGroup + ": " + groupAllowed);
                    }
                    permissionCache.put(cacheKey, groupAllowed);
                    return true;
                }
            }
        }
        
        // Standard permission check by checking each group the player has permission for
        for (Map.Entry<String, Map<String, Boolean>> entry : groupPermissions.entrySet()) {
            String groupName = entry.getKey();
            if (player.hasPermission("group." + groupName)) {
                if (plugin.isDebugMode()) {
                    plugin.debug("[PermissionChecker] Player is in group: " + groupName);
                }
                
                Map<String, Boolean> groupPerms = entry.getValue();
                if (groupPerms.containsKey(normalizedPermission)) {
                    boolean groupAllowed = groupPerms.get(normalizedPermission);
                    if (plugin.isDebugMode()) {
                        plugin.debug("[PermissionChecker] Found group permission for " + normalizedPermission + 
                                   " in group " + groupName + ": " + groupAllowed);
                        plugin.debug("[PermissionChecker] DECISION: " + groupAllowed + " - storing in cache and returning");
                    }
                    permissionCache.put(cacheKey, groupAllowed);
                    return true;
                }
            }
        }
        
        return false;
    }

    /**
     * Check track-based permissions (LuckPerms specific)
     */
    private boolean checkTrackPermissions(Player player, Area area, String normalizedPermission, String cacheKey) {
        if (!plugin.isLuckPermsEnabled()) {
            return false;
        }
        
        Map<String, Map<String, Boolean>> trackPermissions = area.getTrackPermissions();
        if (trackPermissions == null || trackPermissions.isEmpty()) {
            return false;
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("[PermissionChecker] Checking track permissions. Tracks: " + trackPermissions.keySet());
        }
        
        for (Map.Entry<String, Map<String, Boolean>> entry : trackPermissions.entrySet()) {
            String trackName = entry.getKey();
            
            if (plugin.isPlayerInTrack(player, trackName)) {
                Map<String, Boolean> trackPerms = entry.getValue();
                
                if (trackPerms.containsKey(normalizedPermission)) {
                    boolean trackAllowed = trackPerms.get(normalizedPermission);
                    if (plugin.isDebugMode()) {
                        plugin.debug("[PermissionChecker] Found track permission for " + normalizedPermission + 
                                   " in track " + trackName + ": " + trackAllowed);
                        plugin.debug("[PermissionChecker] DECISION: " + trackAllowed + " - storing in cache and returning");
                    }
                    permissionCache.put(cacheKey, trackAllowed);
                    return true;
                }
            }
        }
        
        return false;
    }

    /**
     * Invalidates the permission cache for a specific area, with option to cascade to related caches
     */
    public void invalidateCache(String areaName) {
        if (areaName == null || areaName.isEmpty()) {
            invalidateCache();
            return;
        }

        if (plugin.isDebugMode()) {
            plugin.debug("Invalidating permission cache for area: " + areaName);
        }

        // Build pattern for cache keys related to this area
        String prefix = areaName.toLowerCase() + ":";
        
        // Two-phase cache invalidation
        // Phase 1: Mark entries for invalidation
        Set<String> toInvalidate = new HashSet<>();
        lastPermissionCheck.keySet().forEach(key -> {
            if (key.startsWith(prefix)) {
                toInvalidate.add(key);
            }
        });

        // Phase 2: Atomic invalidation
        toInvalidate.forEach(key -> {
            lastPermissionCheck.remove(key);
            permissionCache.invalidate(key);
        });

        // If this is a global area, also invalidate all world-specific caches
        Area area = plugin.getArea(areaName);
        if (area != null && isAreaGlobal(area)) {
            invalidateWorldCache(area.getWorld());
        }
    }

    /**
     * Helper method to check if an area is global without accessing private methods
     */
    private boolean isAreaGlobal(Area area) {
        // Re-implement the global area check logic, since isGlobal() is private
        // A global area typically has extremely large bounds (covering the entire world)
        AreaDTO.Bounds bounds = area.getBounds();
        return bounds.xMin() <= -29000000 && bounds.xMax() >= 29000000 &&
               bounds.zMin() <= -29000000 && bounds.zMax() >= 29000000;
    }

    /**
     * Invalidates permission cache for an entire world
     */
    private void invalidateWorldCache(String worldName) {
        if (worldName == null || worldName.isEmpty()) return;

        if (plugin.isDebugMode()) {
            plugin.debug("Invalidating world permission cache: " + worldName);
        }

        String worldPrefix = worldName.toLowerCase() + ":";
        lastPermissionCheck.keySet().removeIf(key -> key.startsWith(worldPrefix));
        permissionCache.asMap().keySet().removeIf(key -> key.startsWith(worldPrefix));
    }
    
    /**
     * Invalidates the permission cache for a specific player
     *
     * @param playerName The name of the player
     */
    public void invalidatePlayerCache(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return;
        }

        if (plugin.isDebugMode()) {
            plugin.debug("Invalidating permission cache for player: " + playerName);
        }

        // Batch removals for better performance
        lastPermissionCheck.keySet().removeIf(key -> key.endsWith(":" + playerName));
        permissionCache.asMap().keySet().removeIf(key -> key.endsWith(":" + playerName));
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

    private void logDebug(String message) {
        if (plugin.isDebugMode()) {
            plugin.debug("[PermissionChecker] " + message);
        }
    }

    private boolean checkBypassPermissions(Player player, Area area, String permission) {
        // Check area bypass
        if (player.hasPermission(BYPASS_PREFIX + area.getName())) {
            logDebug("Player has area bypass permission: " + BYPASS_PREFIX + area.getName());
            return true;
        }

        // Check action bypass
        String permWithoutPrefix = permission.startsWith(GUI_PERMISSIONS_PREFIX) ?
            permission.substring(GUI_PERMISSIONS_PREFIX.length()) : permission;
        if (player.hasPermission(BYPASS_PREFIX + permWithoutPrefix)) {
            logDebug("Player has action bypass permission: " + BYPASS_PREFIX + permWithoutPrefix);
            return true;
        }

        return false;
    }

    /**
     * Gets cache statistics for monitoring and diagnostics
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalChecks", permissionCheckTimer.count());
        stats.put("avgCheckTime", permissionCheckTimer.mean(TimeUnit.MILLISECONDS));
        stats.put("cacheHits", cacheHits.count());
        stats.put("cacheMisses", cacheMisses.count());
        stats.put("cacheSize", permissionCache.estimatedSize());
        stats.put("lastCheckTimes", lastPermissionCheck.size());
        return stats;
    }

    /**
     * Performs a permission check with proper timing and cache metrics
     */
    private boolean doPermissionCheck(Player player, Area area, String permission) {
        Timer.Sample sample = Timer.start();
        try {
            // Normalize the permission first
            String normalizedPermission = normalizePermission(permission);
            String cacheKey = buildCacheKey(area, player, normalizedPermission);
            
            // Check cache first for performance
            Boolean cached = permissionCache.getIfPresent(cacheKey);
            if (cached != null) {
                cacheHits.increment();
                logDebug("Cache hit for " + cacheKey + ": " + cached);
                return cached;
            }
            
            cacheMisses.increment();
            logDebug("Cache miss for " + cacheKey + ", computing fresh value");
            
            // Rate limit permission checks for the same key
            if (shouldRateLimit(cacheKey)) {
                logDebug("Rate limiting triggered, returning default true");
                return true;
            }
            
            // Perform actual permission check
            boolean result = checkPermission(player, area, normalizedPermission);
            permissionCache.put(cacheKey, result);
            
            return result;
        } finally {
            sample.stop(permissionCheckTimer);
        }
    }

    /**
     * Core permission checking logic, separated from caching/timing concerns
     */
    private boolean checkPermission(Player player, Area area, String permission) {
        // Quick bypass check first
        if (player != null) {
            // Check for admin permission
            // if (player.hasPermission(ADMIN_PERMISSION)) {
            //     logDebug("Player has admin permission: " + ADMIN_PERMISSION);
            //     return true;
            // }
            
            // Only check for bypass permissions if player is in bypass mode
            if (plugin.isBypassing(player.getName())) {
                logDebug("Player " + player.getName() + " is in bypass mode");
                if (checkBypassPermissions(player, area, permission)) {
                    return true;
                }
            }
            
            // Check player-specific permissions
            if (checkPlayerSpecificPermissions(player, area, permission, 
                buildCacheKey(area, player, permission))) {
                return true;
            }
            
            // Check group permissions
            if (checkGroupPermissions(player, area, permission, 
                buildCacheKey(area, player, permission))) {
                return true;
            }
            
            // Check track permissions
            if (checkTrackPermissions(player, area, permission, 
                buildCacheKey(area, player, permission))) {
                return true;
            }
        }

        // Get the toggle state directly from the area as fallback
        boolean toggleState = area.getToggleState(permission);
        logDebug("Using toggle state: " + toggleState + " for permission: " + permission);
        return toggleState;
    }
}