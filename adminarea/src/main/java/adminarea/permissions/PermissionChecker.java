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
    private static final int CACHE_SIZE = 1000;
    private static final long CACHE_EXPIRY = TimeUnit.MINUTES.toMillis(5);
    private final Map<String, Long> lastPermissionCheck = new ConcurrentHashMap<>();
    private static final long CHECK_INTERVAL = 100; // ms between checks for the same permission
    
    public PermissionChecker(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.permissionCache = Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_EXPIRY, TimeUnit.MILLISECONDS)
            .build();
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

            // Fix missing gui.permissions.toggles prefix
            if (!permission.contains(".")) {
                permission = "gui.permissions.toggles." + permission;
            }
            
            // Build cache key
            String cacheKey = area.getName() + ":" + permission;
            if (player != null) {
                cacheKey += ":" + player.getName();
            }

            // Check cache first
            Boolean cached = permissionCache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }
            
            // Rate limit permission checks for the same key
            long now = System.currentTimeMillis();
            Long lastCheck = lastPermissionCheck.get(cacheKey);
            if (lastCheck != null && now - lastCheck < CHECK_INTERVAL) {
                // Return last cached result or default to true
                return permissionCache.getIfPresent(cacheKey) != null ? 
                    permissionCache.getIfPresent(cacheKey) : true;
            }
            lastPermissionCheck.put(cacheKey, now);

            // Check if player has bypass permission for this specific area
            if (player != null && player.hasPermission("adminarea.bypass." + area.getName())) {
                permissionCache.put(cacheKey, true);
                return true;
            }
            
            // Check if player has bypass permission for this specific action
            String bypassPerm = "adminarea.bypass." + permission.replace("gui.permissions.toggles.", "");
            if (player != null && player.hasPermission(bypassPerm)) {
                permissionCache.put(cacheKey, true);
                return true;
            }

            // Get the toggle state
            boolean allowed = area.getToggleState(permission);
            
            // If allowed by toggle, check if player has permission
            if (allowed && player != null) {
                // Check if player has applicable permissions in the area
                Map<String, Map<String, Boolean>> playerPermissions = area.getPlayerPermissions();
                if (playerPermissions.containsKey(player.getName())) {
                    Map<String, Boolean> playerPerms = playerPermissions.get(player.getName());
                    if (playerPerms.containsKey(permission)) {
                        allowed = playerPerms.get(permission);
                    }
                }
                
                // Check if player's group has applicable permissions
                Map<String, Map<String, Boolean>> groupPermissions = area.getGroupPermissions();
                for (Map.Entry<String, Map<String, Boolean>> entry : groupPermissions.entrySet()) {
                    String group = entry.getKey();
                    if (player.hasPermission("group." + group)) {
                        Map<String, Boolean> groupPerms = entry.getValue();
                        if (groupPerms.containsKey(permission)) {
                            // Group permissions override player permissions if more restrictive
                            allowed = allowed && groupPerms.get(permission);
                        }
                    }
                }
            }

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
        permissionCache.invalidateAll();
    }
    
    /**
     * Invalidates the entire permission cache
     */
    public void invalidateCache() {
        permissionCache.invalidateAll();
    }
}