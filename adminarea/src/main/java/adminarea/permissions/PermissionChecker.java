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
                if (plugin.isDebugMode()) {
                    plugin.debug("[PermissionChecker] Player " + player.getName() + " is bypassing protection");
                }
                return true;
            }

            // If area is null, allow the action by default
            if (area == null) {
                if (plugin.isDebugMode()) {
                    plugin.debug("[PermissionChecker] Area is null, allowing by default");
                }
                return true;
            }

            // Normalize permission name once
            String normalizedPermission = normalizePermission(permission);
            if (plugin.isDebugMode()) {
                plugin.debug("[PermissionChecker] Normalized permission: " + normalizedPermission);
            }
            
            // Build cache key (minimal string operations)
            String cacheKey = area.getName() + ":" + normalizedPermission + (player != null ? ":" + player.getName() : "");

            // Check cache first
            Boolean cached = permissionCache.getIfPresent(cacheKey);
            if (cached != null) {
                if (plugin.isDebugMode()) {
                    plugin.debug("[PermissionChecker] Cache hit for key " + cacheKey + ", returning cached value: " + cached);
                }
                return cached;
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("[PermissionChecker] Cache miss for " + cacheKey + ", computing fresh value");
            }
            
            // Rate limit permission checks for the same key
            long now = System.currentTimeMillis();
            Long lastCheck = lastPermissionCheck.get(cacheKey);
            if (lastCheck != null && now - lastCheck < CHECK_INTERVAL) {
                if (plugin.isDebugMode()) {
                    plugin.debug("[PermissionChecker] Rate limiting triggered, returning default true");
                }
                // Return default if no cached result (shouldn't happen often)
                return true;
            }
            lastPermissionCheck.put(cacheKey, now);

            // Player bypass checks
            if (player != null) {
                // Only check for area and action bypass permissions if the player is actually in bypass mode
                if (plugin.isBypassing(player.getName())) {
                    if (plugin.isDebugMode()) {
                        plugin.debug("[PermissionChecker] Player " + player.getName() + " is in bypass mode, checking specific bypass permissions");
                    }
                    
                    // Check area bypass
                    if (player.hasPermission(BYPASS_PREFIX + area.getName())) {
                        if (plugin.isDebugMode()) {
                            plugin.debug("[PermissionChecker] Player has area bypass permission: " + BYPASS_PREFIX + area.getName());
                        }
                        permissionCache.put(cacheKey, true);
                        return true;
                    }
                    
                    // Check action bypass
                    String permWithoutPrefix = normalizedPermission.startsWith(GUI_PERMISSIONS_PREFIX) ?
                        normalizedPermission.substring(GUI_PERMISSIONS_PREFIX.length()) : normalizedPermission;
                    if (player.hasPermission(BYPASS_PREFIX + permWithoutPrefix)) {
                        if (plugin.isDebugMode()) {
                            plugin.debug("[PermissionChecker] Player has action bypass permission: " + BYPASS_PREFIX + permWithoutPrefix);
                        }
                        permissionCache.put(cacheKey, true);
                        return true;
                    }
                } else if (plugin.isDebugMode()) {
                    plugin.debug("[PermissionChecker] Player " + player.getName() + " is NOT in bypass mode, specific bypass permissions will not be checked");
                }
            }

            // Get the toggle state directly from the area
            boolean toggleState = area.getToggleState(normalizedPermission);
            
            // Debug log the toggle state
            if (plugin.isDebugMode()) {
                plugin.debug("[PermissionChecker] Toggle state for " + normalizedPermission + 
                           " in area " + area.getName() + ": " + toggleState +
                           " (true=allowed, false=denied)");
            }
            
            // Check specific overrides before using toggle state
            if (player != null) {
                // Check player-specific permissions first (highest priority)
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
                    return playerAllowed;
                }
                
                // Check group permissions (next priority)
                Map<String, Map<String, Boolean>> groupPermissions = area.getGroupPermissions();
                
                if (plugin.isDebugMode()) {
                    plugin.debug("[PermissionChecker] Checking group permissions. Groups: " + 
                               (groupPermissions != null ? groupPermissions.keySet() : "null"));
                }
                
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
                
                // No specific permissions found, use the toggle state
                if (plugin.isDebugMode()) {
                    plugin.debug("[PermissionChecker] No specific player or group permissions found");
                    plugin.debug("[PermissionChecker] USING TOGGLE STATE as final decision: " + toggleState);
                    plugin.debug("[PermissionChecker] DECISION: " + toggleState + " - storing in cache and returning");
                }
                
                // Cache and return the toggle state 
                permissionCache.put(cacheKey, toggleState);
                return toggleState;
            }

            // For environment actions (no player), use the toggle state as the final answer
            if (plugin.isDebugMode()) {
                plugin.debug("[PermissionChecker] Environment action (no player), using toggle state: " + toggleState);
                plugin.debug("[PermissionChecker] DECISION: " + toggleState + " - storing in cache and returning");
            }
            permissionCache.put(cacheKey, toggleState);
            return toggleState;
            
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