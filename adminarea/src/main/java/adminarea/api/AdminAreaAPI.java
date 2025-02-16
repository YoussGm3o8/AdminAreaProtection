package adminarea.api;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.util.PerformanceMonitor;
import cn.nukkit.Player;
import cn.nukkit.level.Position;
import java.util.List;
import java.util.Set;

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
}
