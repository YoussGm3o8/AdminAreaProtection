package adminarea.api;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.permissions.PermissionToggle;
import adminarea.util.PerformanceMonitor;
import cn.nukkit.Player;
import cn.nukkit.level.Position;
import java.util.List;
import java.util.Set;

import org.json.JSONObject;

import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;


/**
 * Public API for AdminAreaProtection plugin.
 * Provides methods for managing protected areas and checking permissions.
 */
public class AdminAreaAPI {
    private final AdminAreaProtectionPlugin plugin;

    public AdminAreaAPI(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Creates a new protected area.
     *
     * @param name Area name
     * @param world World name
     * @param x1 First X coordinate
     * @param x2 Second X coordinate
     * @param y1 First Y coordinate
     * @param y2 Second Y coordinate
     * @param z1 First Z coordinate
     * @param z2 Second Z coordinate
     * @param priority Area priority
     * @return The created Area object
     * @throws IllegalArgumentException if parameters are invalid
     */
    public Area createArea(String name, String world, int x1, int x2, int y1, int y2, int z1, int z2, int priority) {
        Area area = Area.builder()
            .name(name)
            .world(world)
            .coordinates(x1, x2, y1, y2, z1, z2)
            .priority(priority)
            .build();
        plugin.addArea(area);
        return area;
    }

    /**
     * Checks if a location is protected.
     *
     * @param position The position to check
     * @return The protecting Area, or null if unprotected
     */
    public Area getProtectingArea(Position position) {
        return plugin.getHighestPriorityArea(
            position.getLevel().getName(),
            position.getX(),
            position.getY(),
            position.getZ()
        );
    }

    /**
     * Gets all areas that contain the given position, sorted by priority.
     *
     * @param position The position to check
     * @return List of areas containing the position, sorted by priority (highest first)
     */
    public List<Area> getApplicableAreas(Position position) {
        return plugin.getApplicableAreas(
            position.getLevel().getName(),
            position.getX(),
            position.getY(),
            position.getZ()
        );
    }

    /**
     * Toggles protection bypass status for a player.
     *
     * @param playerName The name of the player
     * @return The new bypass status
     */
    public boolean toggleBypass(String playerName) {
        plugin.toggleBypass(playerName);
        return plugin.isBypassing(playerName);
    }

    /**
     * Gets the available group names from LuckPerms.
     *
     * @return Set of group names, or empty set if LuckPerms is not available
     */
    public Set<String> getAvailableGroups() {
        return plugin.getGroupNames();
    }

    /**
     * Gets the performance monitor instance.
     *
     * @return The performance monitor
     */
    public PerformanceMonitor getPerformanceMonitor() {
        return plugin.getPerformanceMonitor();
    }

    /**
     * Checks if a player has permission for an action in an area.
     *
     * @param player The player to check
     * @param area The area to check
     * @param permission The permission to check
     * @return true if the player has permission
     */
    public boolean hasPermission(Player player, Area area, String permission) {
        return plugin.hasGroupPermission(player, area, permission);
    }

    /**
     * Gets the plugin instance.
     *
     * @return The AdminAreaProtectionPlugin instance
     */
    public AdminAreaProtectionPlugin getPlugin() {
        return plugin;
    }

    /**
     * Gets all permissions for a specific group in an area
     * @param area The area to check
     * @param group The group name
     * @return Map of permission nodes to boolean values
     */
    public Map<String, Boolean> getGroupPermissions(Area area, String group) {
        return area.getGroupPermissions(group);
    }

    /**
     * Sets multiple permissions for a group in an area
     * @param area The area to modify 
     * @param group The group name
     * @param permissions Map of permission nodes and values to set
     */
    public void setGroupPermissions(Area area, String group, Map<String, Boolean> permissions) {
        area.setGroupPermissions(group, permissions);
        plugin.saveArea(area);
    }

    /**
     * Gets all permissions in an area by category
     * @param area The area to check
     * @param category The category to get permissions for
     * @return Map of permission nodes to boolean values
     */
    public Map<String, Boolean> getPermissionsByCategory(Area area, PermissionToggle.Category category) {
        Map<String, Boolean> perms = new HashMap<>();
        for (PermissionToggle toggle : PermissionToggle.getTogglesByCategory().get(category)) {
            String node = toggle.getPermissionNode();
            perms.put(node, area.getSetting(node));
        }
        return perms;
    }

    /**
     * Updates multiple permissions in a category
     * @param area The area to modify
     * @param category The category to update
     * @param permissions Map of permission nodes and values to set
     */
    public void setPermissionsByCategory(Area area, PermissionToggle.Category category, 
                                       Map<String, Boolean> permissions) {
        JSONObject settings = area.getSettings();
        for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
            settings.put(entry.getKey(), entry.getValue());
        }
        area.setSettings(settings);
        plugin.saveArea(area);
    }

    /**
     * Gets effective permission value considering group inheritance
     * @param area The area to check
     * @param group The group name
     * @param permission The permission to check
     * @return The effective permission value
     */
    public boolean getEffectivePermission(Area area, String group, String permission) {
        return area.getEffectivePermission(group, permission);
    }

    /**
     * Gets effective permissions for a player in an area
     * @param player The player to check
     * @param area The area to check  
     * @return Map of all effective permissions for the player
     */
    public Map<String, Boolean> getEffectivePermissions(Player player, Area area) {
        Map<String, Boolean> effective = new HashMap<>();
        Set<String> groups = plugin.getGroupNames();
        
        // Get all possible permission nodes
        Set<String> allNodes = new HashSet<>();
        for (List<PermissionToggle> toggles : PermissionToggle.getTogglesByCategory().values()) {
            for (PermissionToggle toggle : toggles) {
                allNodes.add(toggle.getPermissionNode());
            }
        }

        // Check each permission node
        for (String node : allNodes) {
            boolean value = false;
            // Check groups in priority order
            for (String group : groups) {
                if (area.hasExplicitGroupPermission(group, node)) {
                    value = area.getGroupPermission(group, node);
                    break;
                }
            }
            effective.put(node, value);
        }
        
        return effective;
    }
}
