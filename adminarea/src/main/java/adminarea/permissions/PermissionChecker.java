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
import java.util.Set;
import java.util.List;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
        
        if (playerPerms != null && playerPerms.containsKey(normalizedPermission)) {
            boolean playerAllowed = playerPerms.get(normalizedPermission);
            if (plugin.isDebugMode()) {
                plugin.debug("[PermissionChecker] Found player-specific permission: " + playerAllowed);
                plugin.debug("[PermissionChecker] DECISION: " + playerAllowed + " - storing in cache and returning");
            }
            permissionCache.put(cacheKey, playerAllowed);
            return playerAllowed;
        }
        
        return false;
    }

    /**
     * Check group-based permissions
     */
    private boolean checkGroupPermissions(Player player, Area area, String normalizedPermission, String cacheKey) {
        // Get group permissions directly from the PermissionOverrideManager to ensure we have the latest data
        Map<String, Map<String, Boolean>> groupPermissions;
        try {
            groupPermissions = plugin.getPermissionOverrideManager().getAllGroupPermissions(area.getName());
            if (groupPermissions == null) {
                groupPermissions = new HashMap<>();
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
            plugin.debug("[PermissionChecker] Checking group permissions. Groups: " + 
                       (groupPermissions != null ? groupPermissions.keySet() : "null"));
        }
        
        // If LuckPerms is available, use it to get player groups in priority order
        if (plugin.isLuckPermsEnabled()) {
            String primaryGroup = plugin.getPrimaryGroup(player);
            if (plugin.isDebugMode()) {
                plugin.debug("[PermissionChecker] Player " + player.getName() + " primary group: " + primaryGroup);
            }
            
            if (primaryGroup != null && groupPermissions.containsKey(primaryGroup)) {
                Map<String, Boolean> groupPerms = groupPermissions.get(primaryGroup);
                if (groupPerms.containsKey(normalizedPermission)) {
                    boolean groupAllowed = groupPerms.get(normalizedPermission);
                    if (plugin.isDebugMode()) {
                        plugin.debug("[PermissionChecker] Found LuckPerms primary group permission for " + 
                                   normalizedPermission + " in group " + primaryGroup + ": " + groupAllowed);
                    }
                    permissionCache.put(cacheKey, groupAllowed);
                    return groupAllowed;
                }
                
                if (plugin.isDebugMode()) {
                    plugin.debug("[PermissionChecker] Primary group " + primaryGroup + " does not have permission: " + normalizedPermission);
                }
            }
            
            // Check inherited groups in order of inheritance
            List<String> inheritedGroups = plugin.getGroupInheritance(primaryGroup);
            if (plugin.isDebugMode()) {
                plugin.debug("[PermissionChecker] Inherited groups for " + primaryGroup + ": " + 
                           (inheritedGroups != null ? inheritedGroups : "none"));
            }
            
            if (inheritedGroups != null && !inheritedGroups.isEmpty()) {
                for (String groupName : inheritedGroups) {
                    if (groupPermissions.containsKey(groupName)) {
                        Map<String, Boolean> groupPerms = groupPermissions.get(groupName);
                        if (groupPerms.containsKey(normalizedPermission)) {
                            boolean groupAllowed = groupPerms.get(normalizedPermission);
                            if (plugin.isDebugMode()) {
                                plugin.debug("[PermissionChecker] Found inherited group permission for " + 
                                           normalizedPermission + " in group " + groupName + ": " + groupAllowed);
                            }
                            permissionCache.put(cacheKey, groupAllowed);
                            return groupAllowed;
                        }
                    }
                }
            }
        } else if (plugin.isDebugMode()) {
            plugin.debug("[PermissionChecker] LuckPerms is not enabled, skipping LuckPerms group checks");
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
                    return groupAllowed;
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
            if (plugin.isDebugMode()) {
                plugin.debug("[PermissionChecker] LuckPerms not enabled, skipping track permission checks");
            }
            return false;
        }
        
        // Get track permissions directly from the PermissionOverrideManager to ensure we have the latest data
        Map<String, Map<String, Boolean>> trackPermissions;
        try {
            trackPermissions = plugin.getPermissionOverrideManager().getAllTrackPermissions(area.getName());
        } catch (Exception e) {
            // If there's an error, fall back to the area's cached permissions
            plugin.getLogger().error("Error getting track permissions from database, falling back to cached permissions", e);
            trackPermissions = area.getTrackPermissions();
        }
        
        if (trackPermissions == null || trackPermissions.isEmpty()) {
            if (plugin.isDebugMode()) {
                plugin.debug("[PermissionChecker] No track permissions defined for area " + area.getName());
            }
            return false;
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("[PermissionChecker] Checking track permissions. Tracks: " + trackPermissions.keySet());
        }
        
        for (Map.Entry<String, Map<String, Boolean>> entry : trackPermissions.entrySet()) {
            String trackName = entry.getKey();
            
            boolean isInTrack = plugin.isPlayerInTrack(player, trackName);
            if (plugin.isDebugMode()) {
                plugin.debug("[PermissionChecker] Checking if player " + player.getName() + 
                            " is in track " + trackName + ": " + isInTrack);
            }
            
            if (isInTrack) {
                Map<String, Boolean> trackPerms = entry.getValue();
                
                if (plugin.isDebugMode()) {
                    plugin.debug("[PermissionChecker] Player is in track " + trackName + 
                                ", checking for permission: " + normalizedPermission);
                }
                
                if (trackPerms.containsKey(normalizedPermission)) {
                    boolean trackAllowed = trackPerms.get(normalizedPermission);
                    if (plugin.isDebugMode()) {
                        plugin.debug("[PermissionChecker] Found track permission for " + normalizedPermission + 
                                   " in track " + trackName + ": " + trackAllowed);
                        plugin.debug("[PermissionChecker] DECISION: " + trackAllowed + " - storing in cache and returning");
                    }
                    permissionCache.put(cacheKey, trackAllowed);
                    return trackAllowed;
                } else if (plugin.isDebugMode()) {
                    plugin.debug("[PermissionChecker] Track " + trackName + 
                                " does not have permission: " + normalizedPermission);
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
        for (String key : permissionCache.asMap().keySet()) {
            if (key.endsWith(":" + playerName)) {
                keysToRemove.add(key);
            }
        }
        
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
     * Check if player has bypass permissions
     */
    private boolean checkBypassPermissions(Player player, Area area, String permission) {
        // Check for specific bypass permission
        String bypassPermission = BYPASS_PREFIX + permission;
        if (player.hasPermission(bypassPermission)) {
            if (plugin.isDebugMode()) {
                plugin.debug("[PermissionChecker] Player has bypass permission: " + bypassPermission);
            }
            return true;
        }
        
        // Check for area-specific bypass permission
        String areaBypassPermission = BYPASS_PREFIX + area.getName() + "." + permission;
        if (player.hasPermission(areaBypassPermission)) {
            if (plugin.isDebugMode()) {
                plugin.debug("[PermissionChecker] Player has area-specific bypass permission: " + areaBypassPermission);
            }
            return true;
        }
        
        // Check for wildcard bypass permission
        String wildcardBypassPermission = BYPASS_PREFIX + "*";
        if (player.hasPermission(wildcardBypassPermission)) {
            if (plugin.isDebugMode()) {
                plugin.debug("[PermissionChecker] Player has wildcard bypass permission: " + wildcardBypassPermission);
            }
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
            if (player.hasPermission(ADMIN_PERMISSION)) {
                logDebug("Player has admin permission: " + ADMIN_PERMISSION);
                return true;
            }
            
            // Check for specific bypass permission for this action
            String bypassPermission = BYPASS_PREFIX + permission;
            if (player.hasPermission(bypassPermission)) {
                logDebug("Player has bypass permission: " + bypassPermission);
                return true;
            }
            
            // Only check for bypass permissions if player is in bypass mode
            if (plugin.isBypassing(player.getName())) {
                logDebug("Player " + player.getName() + " is in bypass mode");
                if (checkBypassPermissions(player, area, permission)) {
                    return true;
                }
            }
            
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
                if (checkPlayerSpecificPermissions(player, area, permission, cacheKey)) {
                    return true;
                }
                
                // 2. Group permissions (medium priority)
                // Important: we need to check both primary group and all inherited groups
                logDebug("Checking group permissions for " + player.getName());
                if (checkGroupPermissions(player, area, permission, cacheKey)) {
                    return true;
                }
                
                // 3. Track permissions (lower priority)
                logDebug("Checking track permissions for " + player.getName());
                if (checkTrackPermissions(player, area, permission, cacheKey)) {
                    return true;
                }
            } catch (Exception e) {
                // Log the error but continue with area toggle check
                plugin.getLogger().error("Error checking permissions from permission_overrides database", e);
                logDebug("Error checking permissions, falling back to area toggle check: " + e.getMessage());
            }
            
            // 4. Finally, check area toggle states (lowest priority)
            logDebug("Checking area toggle states for " + permission);
        }

        // Get the toggle state directly from the area as fallback
        boolean toggleState = area.getToggleState(permission);
        logDebug("Using toggle state: " + toggleState + " for permission: " + permission);
        
        // Store the result in the cache
        String cacheKey = buildCacheKey(area, player, permission);
        permissionCache.put(cacheKey, toggleState);
        
        return toggleState;
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
}