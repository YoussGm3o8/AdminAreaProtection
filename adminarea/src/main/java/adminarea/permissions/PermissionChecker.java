package adminarea.permissions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaDTO;
import cn.nukkit.Player;
import cn.nukkit.level.Position;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Timer.Sample;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

public class PermissionChecker {
    private static final String GUI_PERMISSIONS_PREFIX = "gui.permissions.toggles.";
    private static final String BYPASS_PREFIX = "adminarea.bypass.";
    private static final String ADMIN_PERMISSION = "adminarea.admin";
    
    private final AdminAreaProtectionPlugin plugin;
    private final Cache<String, Boolean> permissionCache;
    private final Map<String, Long> lastPermissionCheck;
    private static final long CHECK_INTERVAL = 200;
    private final Timer permissionCheckTimer;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    
    private final Map<String, Map<String, Map<String, Boolean>>> areaGroupPermissions = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Map<String, Boolean>>> areaTrackPermissions = new ConcurrentHashMap<>();

    private enum PermissionStatus {
        ALLOWED,
        DENIED,
        NOT_FOUND
    }

    public PermissionChecker(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.permissionCache = Caffeine.newBuilder()
            .maximumSize(5000)
            .expireAfterWrite(2, TimeUnit.MINUTES)
            .recordStats()
            .build();
        this.lastPermissionCheck = new ConcurrentHashMap<>();
        
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
        if (area == null) {
            plugin.debug("[PermissionChecker] No area provided, defaulting to allowed");
            return true;
        }

        String normalizedPermission = normalizePermission(permission);
        plugin.debug("[PermissionChecker] Checking permission: " + normalizedPermission + " in area: " + area.getName());

        // If no player, only check toggle state
        if (player == null) {
            boolean toggleState = area.getToggleState(normalizedPermission);
            plugin.debug("[PermissionChecker] No player provided, using only toggle state: " + toggleState);
            return toggleState;
        }

        // Check player permissions first - these override area settings
        // No longer check for admin permission, only rely on the bypass mode
        
        // Check if player is in bypass mode
        if (plugin.isBypassing(player.getName())) {
            plugin.debug("[PermissionChecker] Player is in bypass mode, allowing action");
            return true;
        }

        // Generate cache key
        String cacheKey = area.getName() + ":" + player.getName() + ":" + normalizedPermission;
        
        // Disable caching temporarily to troubleshoot permission issues
        // Boolean cachedResult = permissionCache.getIfPresent(cacheKey);
        // if (cachedResult != null) {
        //    plugin.debug("[PermissionChecker] Using cached result: " + cachedResult);
        //    return cachedResult;
        // }
        
        // Check player-specific permissions - HIGHEST PRIORITY
        Map<String, Boolean> playerPerms = area.getPlayerPermissions(player.getName());
        if (playerPerms != null) {
            // Try with the normalized permission (with prefix)
            if (playerPerms.containsKey(normalizedPermission)) {
                boolean allowed = playerPerms.get(normalizedPermission);
                plugin.debug("[PermissionChecker] Found player-specific permission (with prefix): " + allowed);
                return allowed;
            }
            
            // Try without the prefix
            String permWithoutPrefix = normalizedPermission.replace("gui.permissions.toggles.", "");
            if (playerPerms.containsKey(permWithoutPrefix)) {
                boolean allowed = playerPerms.get(permWithoutPrefix);
                plugin.debug("[PermissionChecker] Found player-specific permission (without prefix): " + allowed);
                return allowed;
            }
        }

        // Check group permissions
        boolean groupResult = checkGroupPermissions(player, area, normalizedPermission, cacheKey);
        if (groupResult) {
            plugin.debug("[PermissionChecker] Permission granted by group permissions");
            return true;
        }

        // Check track permissions
        boolean trackResult = checkTrackPermissions(player, area, normalizedPermission, cacheKey);
        if (trackResult) {
            plugin.debug("[PermissionChecker] Permission granted by track permissions");
            return true;
        }

        // Now check area toggle state - LOWEST PRIORITY
        boolean toggleState = area.getToggleState(normalizedPermission);
        plugin.debug("[PermissionChecker] Area toggle state: " + toggleState);
        
        // If toggle is false, it means the action is DENIED
        if (!toggleState) {
            plugin.debug("[PermissionChecker] Permission denied by area toggle state");
            return false;
        }

        // If we get here, use the toggle state as the final decision
        plugin.debug("[PermissionChecker] No specific permissions found, using toggle state: " + toggleState);
        return toggleState;
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
                default -> {
                    // Check if it's an "allow" permission without prefix
                    if (permission.startsWith("allow")) {
                        yield GUI_PERMISSIONS_PREFIX + permission;
                    } else {
                        yield GUI_PERMISSIONS_PREFIX + "allow" + permission.substring(0, 1).toUpperCase() + permission.substring(1);
                    }
                }
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
     * Check group-based permissions
     */
    private boolean checkGroupPermissions(Player player, Area area, String normalizedPermission, String cacheKey) {
        if (player == null || area == null) return false;

        PermissionStatus result = checkGroupPermission(player, area, normalizedPermission, cacheKey);
        if (result == PermissionStatus.ALLOWED) {
            plugin.debug("[PermissionChecker] Permission granted by group permissions");
            return true;
        } else if (result == PermissionStatus.DENIED) {
            return false;
        }

        // No specific permission found
        return false;
    }

    /**
     * Check track-based permissions (LuckPerms specific)
     */
    private boolean checkTrackPermissions(Player player, Area area, String normalizedPermission, String cacheKey) {
        if (player == null || area == null) return false;

        PermissionStatus result = checkTrackPermission(player, area, normalizedPermission, cacheKey);
        if (result == PermissionStatus.ALLOWED) {
            plugin.debug("[PermissionChecker] Permission granted by track permissions");
            return true;
        } else if (result == PermissionStatus.DENIED) {
            return false;
        }

        // No specific permission found
        return false;
    }

    /**
     * Invalidates the permission cache for a specific area
     */
    public void invalidateCache(String areaName) {
        if (areaName == null || areaName.isEmpty()) {
            return;
        }

        if (plugin.isDebugMode()) {
            plugin.debug("Invalidating permission cache for area: " + areaName);
        }

        int count = invalidateAreaCache(areaName);

        // If this is a global area, also invalidate all world-specific caches
        Area area = plugin.getArea(areaName);
        if (area != null && isAreaGlobal(area)) {
            invalidateWorldCache(area.getWorld());
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("Invalidated " + count + " cache entries for area: " + areaName);
        }
    }
    
    /**
     * Invalidates cache entries for a specific area and returns the count of invalidated entries.
     * This method specifically focuses on keys in the cache that start with the area name.
     * 
     * @param areaName The name of the area to invalidate cache for
     * @return The number of cache entries that were invalidated
     */
    public int invalidateAreaCache(String areaName) {
        if (areaName == null || areaName.isEmpty()) {
            return 0;
        }
        
        // Ensure lowercase for consistent key lookup
        String normalizedAreaName = areaName.toLowerCase();
        
        // Build pattern for cache keys related to this area (consistent format)
        String prefix = normalizedAreaName + ":";
        
        // Two-phase cache invalidation
        // Phase 1: Mark entries for invalidation (work with a copy to avoid ConcurrentModificationException)
        Set<String> toInvalidate = new HashSet<>();
        
        // Collect keys from both caches
        for (String key : lastPermissionCheck.keySet()) {
            if (key.toLowerCase().startsWith(prefix)) {
                toInvalidate.add(key);
            }
        }
        
        // Also check for any keys directly containing the area name (different format)
        for (String key : permissionCache.asMap().keySet()) {
            if (key.toLowerCase().contains(normalizedAreaName)) {
                toInvalidate.add(key);
            }
        }
        
        // Additionally clear any area-specific permission mappings
        areaGroupPermissions.remove(normalizedAreaName);
        areaTrackPermissions.remove(normalizedAreaName);

        // Phase 2: Atomic invalidation
        int invalidatedCount = 0;
        for (String key : toInvalidate) {
            lastPermissionCheck.remove(key);
            permissionCache.invalidate(key);
            invalidatedCount++;
        }
        
        return invalidatedCount;
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
     */
    public void invalidatePlayerCache(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return;
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("Invalidating permission cache for player: " + playerName);
        }
        
        // Find and remove all cache entries for this player
        Set<String> keysToRemove = new HashSet<>();
        
        // Check both cache and lastPermissionCheck map
        permissionCache.asMap().keySet().forEach(key -> {
            if (key.contains(":" + playerName)) {
                keysToRemove.add(key);
            }
        });
        
        lastPermissionCheck.keySet().forEach(key -> {
            if (key.contains(":" + playerName)) {
                keysToRemove.add(key);
            }
        });
        
        // Perform the actual invalidation
        for (String key : keysToRemove) {
            permissionCache.invalidate(key);
            lastPermissionCheck.remove(key);
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("Invalidated " + keysToRemove.size() + " cache entries for player: " + playerName);
        }
    }
    
    /**
     * Invalidates the entire permission cache
     */
    public void invalidateCache() {
        if (plugin.isDebugMode()) {
            plugin.debug("Invalidating entire permission cache");
        }
        
        permissionCache.invalidateAll();
        lastPermissionCheck.clear();
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
        // Player permissions should have already been checked in isAllowed
        // This method is for checking more specific permissions
        
        String cacheKey = buildCacheKey(area, player, permission);
        
        try {
            // First, ensure we have the most up-to-date data for this area
            // This is important because the area permissions might have been modified
            boolean shouldRefreshPlayerPerms = plugin.getPermissionOverrideManager().hasUpdatedPlayerPermissions(area.getName(), player.getName());
            boolean shouldRefreshGroupPerms = plugin.getPermissionOverrideManager().hasUpdatedGroupPermissions(area.getName());
            boolean shouldRefreshTrackPerms = plugin.getPermissionOverrideManager().hasUpdatedTrackPermissions(area.getName());
            
            if (shouldRefreshPlayerPerms || shouldRefreshGroupPerms || shouldRefreshTrackPerms) {
                // Invalidate the cache for this key since we have updated data
                permissionCache.invalidate(cacheKey);
                logDebug("Refreshing permissions data for " + area.getName() + " due to updates");
            }
            
            // IMPORTANT: Follow the permission hierarchy in order:
            // 1. Player-specific permissions (highest priority)
            logDebug("Checking player-specific permissions for " + player.getName());
            PermissionStatus playerStatus = checkPlayerSpecificPermission(player, area, permission, cacheKey);
            if (playerStatus == PermissionStatus.ALLOWED) {
                logDebug("Player permission allowed");
                return true;
            } else if (playerStatus == PermissionStatus.DENIED) {
                logDebug("Player permission denied");
                return false;
            }
            
            // 2. Group permissions (medium priority)
            // Important: we need to check both primary group and all inherited groups
            logDebug("Checking group permissions for " + player.getName());
            
            // Get all group permissions directly from the area for debugging
            if (plugin.isDebugMode()) {
                Map<String, Map<String, Boolean>> groupPerms = area.getGroupPermissions();
                logDebug("Available groups in area: " + area.getName() + ": " + 
                     (groupPerms != null ? String.join(", ", groupPerms.keySet()) : "none"));
            }
            
            PermissionStatus groupStatus = checkGroupPermission(player, area, permission, cacheKey);
            if (groupStatus == PermissionStatus.ALLOWED) {
                logDebug("Group permission allowed");
                return true;
            } else if (groupStatus == PermissionStatus.DENIED) {
                logDebug("Group permission denied");
                return false;
            }
            
            // 3. Track permissions (lower priority)
            logDebug("Checking track permissions for " + player.getName());
            
            // Get all track permissions directly from the area for debugging
            if (plugin.isDebugMode()) {
                Map<String, Map<String, Boolean>> trackPerms = area.getTrackPermissions();
                logDebug("Available tracks in area: " + area.getName() + ": " + 
                     (trackPerms != null ? String.join(", ", trackPerms.keySet()) : "none"));
            }
            
            PermissionStatus trackStatus = checkTrackPermission(player, area, permission, cacheKey);
            if (trackStatus == PermissionStatus.ALLOWED) {
                logDebug("Track permission allowed");
                return true;
            } else if (trackStatus == PermissionStatus.DENIED) {
                logDebug("Track permission denied");
                return false;
            }
        } catch (Exception e) {
            // Log the error but continue with area toggle check
            plugin.getLogger().error("Error checking permissions from permission_overrides database", e);
            logDebug("Error checking permissions, falling back to area toggle check: " + e.getMessage());
        }
        
        // 4. Finally, check area toggle states (lowest priority)
        logDebug("Checking area toggle states for " + permission);

        // Get the toggle state directly from the area as fallback
        boolean toggleState = area.getToggleState(permission);
        logDebug("Using toggle state: " + toggleState + " for permission: " + permission);
        
        return toggleState;
    }

    /**
     * Check player-specific permissions
     */
    private PermissionStatus checkPlayerSpecificPermission(Player player, Area area, String normalizedPermission, String cacheKey) {
        // Get player permissions directly from the PermissionOverrideManager to ensure we have the latest data
        Map<String, Boolean> playerPerms;
        try {
            playerPerms = plugin.getPermissionOverrideManager().getPlayerPermissions(area.getName(), player.getName());
        } catch (Exception e) {
            // If there's an error, fall back to the area's cached permissions
            plugin.getLogger().error("Error getting player permissions from database, falling back to cached permissions", e);
            Map<String, Map<String, Boolean>> playerPermissions = area.getPlayerPermissions();
            playerPerms = playerPermissions.get(player.getName());
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("[PermissionChecker] Player permissions map: " + 
                       (playerPerms != null ? playerPerms.toString() : "null"));
        }
        
        if (playerPerms != null) {
            // Check with full prefixed permission first
            if (playerPerms.containsKey(normalizedPermission)) {
                boolean playerAllowed = playerPerms.get(normalizedPermission);
                if (plugin.isDebugMode()) {
                    plugin.debug("[PermissionChecker] Found player-specific permission: " + playerAllowed);
                    plugin.debug("[PermissionChecker] DECISION: " + playerAllowed + " - storing in cache and returning");
                }
                permissionCache.put(cacheKey, playerAllowed);
                return playerAllowed ? PermissionStatus.ALLOWED : PermissionStatus.DENIED;
            }
            
            // Try without the prefix
            String permWithoutPrefix = normalizedPermission.replace("gui.permissions.toggles.", "");
            if (playerPerms.containsKey(permWithoutPrefix)) {
                boolean playerAllowed = playerPerms.get(permWithoutPrefix);
                if (plugin.isDebugMode()) {
                    plugin.debug("[PermissionChecker] Found player-specific permission without prefix: " + playerAllowed);
                    plugin.debug("[PermissionChecker] DECISION: " + playerAllowed + " - storing in cache and returning");
                }
                permissionCache.put(cacheKey, playerAllowed);
                return playerAllowed ? PermissionStatus.ALLOWED : PermissionStatus.DENIED;
            }
        }
        
        // Permission not found at player level - continue to next level
        if (plugin.isDebugMode()) {
            plugin.debug("[PermissionChecker] No player-specific permission found for " + normalizedPermission);
        }
        return PermissionStatus.NOT_FOUND;
    }

    /**
     * Check group-based permissions
     */
    private PermissionStatus checkGroupPermission(Player player, Area area, String normalizedPermission, String cacheKey) {
        // Get group permissions directly from the PermissionOverrideManager to ensure we have the latest data
        Map<String, Map<String, Boolean>> groupPermissions;
        try {
            groupPermissions = plugin.getPermissionOverrideManager().getAllGroupPermissions(area.getName());
            if (groupPermissions == null) {
                groupPermissions = new HashMap<>();
            }
            
            if (plugin.isDebugMode()) {
                logDebug("Retrieved " + groupPermissions.size() + " groups from database for area: " + area.getName());
            }
        } catch (Exception e) {
            // If there's an error, fall back to the area's cached permissions
            plugin.getLogger().error("Error getting group permissions from database, falling back to cached permissions", e);
            groupPermissions = area.getGroupPermissions();
            if (groupPermissions == null) {
                groupPermissions = new HashMap<>();
            }
        }
        
        if (plugin.isDebugMode()) {
            logDebug("[PermissionChecker] Checking group permissions. Groups: " + 
                   (groupPermissions != null ? String.join(", ", groupPermissions.keySet()) : "null"));
        }
        
        // If LuckPerms is available, use it to get player groups in priority order
        if (plugin.isLuckPermsEnabled()) {
            String primaryGroup = plugin.getPrimaryGroup(player);
            if (plugin.isDebugMode()) {
                logDebug("[PermissionChecker] Player " + player.getName() + " primary group: " + primaryGroup);
            }
            
            if (primaryGroup != null && groupPermissions.containsKey(primaryGroup)) {
                Map<String, Boolean> groupPerms = groupPermissions.get(primaryGroup);
                
                if (plugin.isDebugMode()) {
                    logDebug("[PermissionChecker] Primary group permissions: " + 
                           (groupPerms != null ? groupPerms.size() : "null") + " permissions");
                    if (groupPerms != null && groupPerms.size() > 0) {
                        logDebug("[PermissionChecker] Permission keys: " + 
                               String.join(", ", groupPerms.keySet().stream().limit(5).collect(java.util.stream.Collectors.toList())));
                    }
                }
                
                if (groupPerms.containsKey(normalizedPermission)) {
                    boolean groupAllowed = groupPerms.get(normalizedPermission);
                    if (plugin.isDebugMode()) {
                        logDebug("[PermissionChecker] Found LuckPerms primary group permission for " + 
                               normalizedPermission + " in group " + primaryGroup + ": " + groupAllowed);
                    }
                    permissionCache.put(cacheKey, groupAllowed);
                    return groupAllowed ? PermissionStatus.ALLOWED : PermissionStatus.DENIED;
                }
                
                // Try without the prefix
                String permWithoutPrefix = normalizedPermission.replace("gui.permissions.toggles.", "");
                if (groupPerms.containsKey(permWithoutPrefix)) {
                    boolean groupAllowed = groupPerms.get(permWithoutPrefix);
                    if (plugin.isDebugMode()) {
                        logDebug("[PermissionChecker] Found LuckPerms primary group permission without prefix for " + 
                               permWithoutPrefix + " in group " + primaryGroup + ": " + groupAllowed);
                    }
                    permissionCache.put(cacheKey, groupAllowed);
                    return groupAllowed ? PermissionStatus.ALLOWED : PermissionStatus.DENIED;
                }
                
                if (plugin.isDebugMode()) {
                    logDebug("[PermissionChecker] Primary group " + primaryGroup + " does not have permission: " + 
                           normalizedPermission + " or " + permWithoutPrefix);
                }
            }
            
            // Check inherited groups in order of inheritance
            List<String> inheritedGroups = plugin.getGroupInheritance(primaryGroup);
            if (plugin.isDebugMode()) {
                logDebug("[PermissionChecker] Inherited groups for " + primaryGroup + ": " + 
                       (inheritedGroups != null ? String.join(", ", inheritedGroups) : "none"));
            }
            
            if (inheritedGroups != null) {
                for (String group : inheritedGroups) {
                    if (plugin.isDebugMode()) {
                        logDebug("[PermissionChecker] Checking inherited group: " + group);
                    }
                    
                    if (groupPermissions.containsKey(group)) {
                        Map<String, Boolean> inheritedGroupPerms = groupPermissions.get(group);
                        if (inheritedGroupPerms.containsKey(normalizedPermission)) {
                            boolean inheritedAllowed = inheritedGroupPerms.get(normalizedPermission);
                            if (plugin.isDebugMode()) {
                                logDebug("[PermissionChecker] Found inherited group permission for " + 
                                       normalizedPermission + " in group " + group + ": " + inheritedAllowed);
                                logDebug("[PermissionChecker] DECISION: " + inheritedAllowed + " - storing in cache and returning");
                            }
                            permissionCache.put(cacheKey, inheritedAllowed);
                            return inheritedAllowed ? PermissionStatus.ALLOWED : PermissionStatus.DENIED;
                        }
                        
                        // Try without the prefix
                        String permWithoutPrefix = normalizedPermission.replace("gui.permissions.toggles.", "");
                        if (inheritedGroupPerms.containsKey(permWithoutPrefix)) {
                            boolean inheritedAllowed = inheritedGroupPerms.get(permWithoutPrefix);
                            if (plugin.isDebugMode()) {
                                logDebug("[PermissionChecker] Found inherited group permission without prefix for " + 
                                       permWithoutPrefix + " in group " + group + ": " + inheritedAllowed);
                                logDebug("[PermissionChecker] DECISION: " + inheritedAllowed + " - storing in cache and returning");
                            }
                            permissionCache.put(cacheKey, inheritedAllowed);
                            return inheritedAllowed ? PermissionStatus.ALLOWED : PermissionStatus.DENIED;
                        }
                    }
                }
            }
        } else {
            // If LuckPerms is not available, just check all group permissions
            for (Map.Entry<String, Map<String, Boolean>> entry : groupPermissions.entrySet()) {
                String group = entry.getKey();
                Map<String, Boolean> groupPerms = entry.getValue();
                
                if (plugin.isDebugMode()) {
                    logDebug("[PermissionChecker] Checking group: " + group);
                }
                
                // Check if player has this group permission
                if (player.hasPermission("group." + group)) {
                    if (plugin.isDebugMode()) {
                        logDebug("[PermissionChecker] Player is in group: " + group);
                    }
                    
                    if (groupPerms.containsKey(normalizedPermission)) {
                        boolean groupAllowed = groupPerms.get(normalizedPermission);
                        if (plugin.isDebugMode()) {
                            logDebug("[PermissionChecker] Found group permission for " + 
                                   normalizedPermission + " in group " + group + ": " + groupAllowed);
                            logDebug("[PermissionChecker] DECISION: " + groupAllowed + " - storing in cache and returning");
                        }
                        permissionCache.put(cacheKey, groupAllowed);
                        return groupAllowed ? PermissionStatus.ALLOWED : PermissionStatus.DENIED;
                    }
                }
            }
        }
        
        // Permission not found at group level - continue to next level
        if (plugin.isDebugMode()) {
            logDebug("[PermissionChecker] No group permissions found for " + normalizedPermission);
        }
        return PermissionStatus.NOT_FOUND;
    }

    private PermissionStatus checkTrackPermission(Player player, Area area, String normalizedPermission, String cacheKey) {
        if (!plugin.isLuckPermsEnabled()) {
            if (plugin.isDebugMode()) {
                logDebug("[PermissionChecker] LuckPerms is not enabled, skipping track permission check");
            }
            return PermissionStatus.NOT_FOUND;
        }
        
        Map<String, Map<String, Boolean>> trackPermissions = areaTrackPermissions.get(area.getName());
        if (trackPermissions == null || trackPermissions.isEmpty()) {
            // Try to load track permissions from the database if they're not in the cache
            if (plugin.isDebugMode()) {
                logDebug("[PermissionChecker] Track permissions not found in cache, trying to load from database");
            }
            
            try {
                trackPermissions = plugin.getPermissionOverrideManager().getAllTrackPermissions(area.getName());
                if (trackPermissions != null && !trackPermissions.isEmpty()) {
                    // Store in cache for future use
                    areaTrackPermissions.put(area.getName(), trackPermissions);
                    
                    if (plugin.isDebugMode()) {
                        logDebug("[PermissionChecker] Loaded " + trackPermissions.size() + " track permissions from database");
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().error("Failed to load track permissions from database", e);
            }
            
            // If still null or empty after loading
            if (trackPermissions == null || trackPermissions.isEmpty()) {
                if (plugin.isDebugMode()) {
                    logDebug("[PermissionChecker] No track permissions defined for area " + area.getName());
                }
                return PermissionStatus.NOT_FOUND;
            }
        }
        
        // Get player's primary group for debugging
        String primaryGroup = plugin.getPrimaryGroup(player);
        
        // Get all groups the player is in
        String playerName = player.getName();
        List<String> playerGroups = plugin.getLuckPermsCache().getGroups(playerName);
        
        if (plugin.isDebugMode()) {
            logDebug("[PermissionChecker] Checking track permissions. Tracks defined: " + 
                   String.join(", ", trackPermissions.keySet()));
            logDebug("[PermissionChecker] Player " + player.getName() + " has primary group: " + primaryGroup);
            logDebug("[PermissionChecker] Player " + player.getName() + " belongs to groups: " + 
                   String.join(", ", playerGroups));
            
            // Show which tracks the player is in
            List<String> playerTracks = new ArrayList<>();
            for (String trackName : trackPermissions.keySet()) {
                if (plugin.isPlayerInTrack(player, trackName)) {
                    playerTracks.add(trackName);
                }
            }
            
            if (!playerTracks.isEmpty()) {
                logDebug("[PermissionChecker] Player is in tracks: " + String.join(", ", playerTracks));
            } else {
                logDebug("[PermissionChecker] Player is not in any tracks");
            }
        }
        
        for (Map.Entry<String, Map<String, Boolean>> entry : trackPermissions.entrySet()) {
            String trackName = entry.getKey();
            
            // Check if player is in this track (through any of their groups)
            boolean isInTrack = plugin.isPlayerInTrack(player, trackName);
            
            if (plugin.isDebugMode()) {
                // Get track groups for better debugging
                try {
                    var track = plugin.getLuckPermsApi().getTrackManager().getTrack(trackName);
                    if (track != null) {
                        logDebug("[PermissionChecker] Track " + trackName + " contains groups: " + 
                               String.join(", ", track.getGroups()));
                    }
                } catch (Exception e) {
                    // Ignore exceptions in debug code
                    logDebug("[PermissionChecker] Error getting track groups: " + e.getMessage());
                }
                
                logDebug("[PermissionChecker] Checking if player " + player.getName() + 
                        " is in track " + trackName + ": " + isInTrack);
            }
            
            if (isInTrack) {
                Map<String, Boolean> trackPerms = entry.getValue();
                
                if (plugin.isDebugMode()) {
                    logDebug("[PermissionChecker] Player is in track " + trackName + 
                            ", checking for permission: " + normalizedPermission);
                    logDebug("[PermissionChecker] Track has " + trackPerms.size() + " permissions defined");
                    if (trackPerms.size() > 0) {
                        logDebug("[PermissionChecker] Sample permissions: " +
                               String.join(", ", trackPerms.keySet().stream().limit(3).collect(java.util.stream.Collectors.toList())));
                    }
                }
                
                if (trackPerms.containsKey(normalizedPermission)) {
                    boolean trackAllowed = trackPerms.get(normalizedPermission);
                    if (plugin.isDebugMode()) {
                        logDebug("[PermissionChecker] Found track permission for " + normalizedPermission + 
                                " in track " + trackName + ": " + trackAllowed);
                        logDebug("[PermissionChecker] DECISION: " + trackAllowed + " - using this value");
                    }
                    permissionCache.put(cacheKey, trackAllowed);
                    return trackAllowed ? PermissionStatus.ALLOWED : PermissionStatus.DENIED;
                } 
                
                // Try without the gui.permissions.toggles. prefix
                String permWithoutPrefix = normalizedPermission.replace("gui.permissions.toggles.", "");
                if (trackPerms.containsKey(permWithoutPrefix)) {
                    boolean trackAllowed = trackPerms.get(permWithoutPrefix);
                    if (plugin.isDebugMode()) {
                        logDebug("[PermissionChecker] Found track permission without prefix for " + 
                                permWithoutPrefix + " in track " + trackName + ": " + trackAllowed);
                        logDebug("[PermissionChecker] DECISION: " + trackAllowed + " - using this value");
                    }
                    permissionCache.put(cacheKey, trackAllowed);
                    return trackAllowed ? PermissionStatus.ALLOWED : PermissionStatus.DENIED;
                } else if (plugin.isDebugMode()) {
                    logDebug("[PermissionChecker] Track " + trackName + " does not have permission: " + normalizedPermission + 
                           " or " + permWithoutPrefix);
                }
            }
        }
        
        if (plugin.isDebugMode()) {
            logDebug("[PermissionChecker] No track permissions found for " + normalizedPermission);
        }
        return PermissionStatus.NOT_FOUND;
    }

    public boolean hasPermission(String areaName, String groupName, String permission) {
        Map<String, Map<String, Boolean>> groupPermissions = areaGroupPermissions.get(areaName);
        
        // Add null check before accessing groupPermissions
        if (groupPermissions == null) {
            return false;
        }
        
        Map<String, Boolean> permissions = groupPermissions.get(groupName);
        return permissions != null && permissions.getOrDefault(permission, true);
    }

    public boolean hasPermissionInTrack(String areaName, String trackName, String permission) {
        Map<String, Map<String, Boolean>> groupPermissions = areaGroupPermissions.get(areaName);
        
        // Add null check before accessing groupPermissions
        if (groupPermissions == null) {
            return false;
        }

        // Get track permissions and check them
        Map<String, Boolean> trackPerms = areaTrackPermissions
            .getOrDefault(areaName, Collections.emptyMap())
            .getOrDefault(trackName, Collections.emptyMap());
            
        return trackPerms.getOrDefault(permission, true);
    }

    public boolean checkPlayerPermission(String areaName, String playerName, String permission) {
        Map<String, Map<String, Boolean>> groupPermissions = areaGroupPermissions.get(areaName);
        
        // Return false if no permissions exist
        if (groupPermissions == null) {
            return false;
        }

        // Get player's permissions
        Map<String, Boolean> playerPerms = groupPermissions.get(playerName);
        if (playerPerms == null) {
            return false;
        }

        // Return permission value or false if not found
        return playerPerms.getOrDefault(permission, false);
    }

    // Add safe getters/setters for area permissions
    public Map<String, Boolean> getAreaGroupPermissions(String areaName, String groupName) {
        Map<String, Map<String, Boolean>> groupPermissions = areaGroupPermissions.getOrDefault(areaName, new HashMap<>());
        return groupPermissions.getOrDefault(groupName, Collections.emptyMap());
    }

    public void setAreaGroupPermissions(String areaName, String groupName, Map<String, Boolean> permissions) {
        areaGroupPermissions.computeIfAbsent(areaName, k -> new ConcurrentHashMap<>())
                          .put(groupName, new HashMap<>(permissions));
    }

    public Map<String, Boolean> getAreaTrackPermissions(String areaName, String track) {
        Map<String, Map<String, Boolean>> trackPermissions = areaTrackPermissions.getOrDefault(areaName, new HashMap<>());
        return trackPermissions.getOrDefault(track, Collections.emptyMap());
    }

    public void setAreaTrackPermissions(String areaName, String track, Map<String, Boolean> permissions) {
        areaTrackPermissions.computeIfAbsent(areaName, k -> new ConcurrentHashMap<>())
                          .put(track, new HashMap<>(permissions));
    }

    // Methods for clearing caches and permissions
    public void clearCaches() {
        permissionCache.invalidateAll();
        lastPermissionCheck.clear();
        areaGroupPermissions.clear();
        areaTrackPermissions.clear();
    }

    /**
     * Updates track permissions cache for a specific area
     * @param areaName The name of the area
     */
    public void updateAreaTrackPermissions(String areaName) {
        try {
            // Get track permissions directly from the database via PermissionOverrideManager
            Map<String, Map<String, Boolean>> trackPerms = 
                plugin.getPermissionOverrideManager().getAllTrackPermissions(areaName);
            
            if (trackPerms != null && !trackPerms.isEmpty()) {
                // Store in the local cache
                areaTrackPermissions.put(areaName, trackPerms);
                
                if (plugin.isDebugMode()) {
                    logDebug("Updated track permissions for area " + areaName + ": " + 
                           trackPerms.size() + " tracks");
                    logDebug("Tracks: " + String.join(", ", trackPerms.keySet()));
                }
            } else if (plugin.isDebugMode()) {
                logDebug("No track permissions found in database for area " + areaName);
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error updating track permissions for area " + areaName, e);
        }
    }
    
    /**
     * Called during startup or reload to refresh the permission caches from the database
     */
    public void refreshPermissionCaches() {
        if (plugin.isDebugMode()) {
            logDebug("Refreshing permission caches from database");
        }
        
        // Clear existing caches first
        clearCaches();
        
        // Get all areas
        List<Area> areas = plugin.getAreaManager().getAllAreas();
        
        for (Area area : areas) {
            String areaName = area.getName();
            
            // Update track permissions
            updateAreaTrackPermissions(areaName);
            
            // Get group permissions
            try {
                Map<String, Map<String, Boolean>> groupPerms = 
                    plugin.getPermissionOverrideManager().getAllGroupPermissions(areaName);
                
                if (groupPerms != null && !groupPerms.isEmpty()) {
                    areaGroupPermissions.put(areaName, groupPerms);
                    
                    if (plugin.isDebugMode()) {
                        logDebug("Updated group permissions for area " + areaName + ": " + 
                               groupPerms.size() + " groups");
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().error("Error updating group permissions for area " + areaName, e);
            }
        }
        
        if (plugin.isDebugMode()) {
            logDebug("Permission caches refreshed for " + areas.size() + " areas");
        }
    }
}