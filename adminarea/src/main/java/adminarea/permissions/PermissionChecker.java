package adminarea.permissions;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import cn.nukkit.Player;
import java.util.Map;

public class PermissionChecker {
    private final AdminAreaProtectionPlugin plugin;

    public PermissionChecker(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Checks if an action is allowed based on the permission hierarchy:
     * 1. Player-specific permission
     * 2. Track permission
     * 3. Group permission
     * 4. Area default permission
     *
     * @param player The player to check permissions for (can be null for environment events)
     * @param area The area to check permissions in
     * @param permission The permission node to check
     * @return true if the action is allowed, false if denied
     */
    public boolean isAllowed(Player player, Area area, String permission) {
        if (area == null) {
            if (plugin.isDebugMode()) {
                plugin.debug("No area found, allowing action");
            }
            return true;
        }

        // Debug logging
        if (plugin.isDebugMode()) {
            plugin.debug(String.format("Checking permission '%s' for %s in area %s",
                permission,
                player != null ? player.getName() : "environment",
                area.getName()));
            plugin.debug(" Player toggle states: " + area.toDTO().playerPermissions().toString());
            plugin.debug("  Area toggle states: " + area.toDTO().toggleStates().toString());
            plugin.debug("  Area permissions: " + area.toDTO().permissions().toMap());
        }

        // Map common permission nodes to their toggle state equivalents
        String togglePermission = mapPermissionToToggle(permission);
        if (plugin.isDebugMode()) {
            plugin.debug("  Mapped permission '" + permission + "' to toggle '" + togglePermission + "'");
        }

        // If player is bypassing protection, allow everything
        if (player != null && plugin.isBypassing(player.getName())) {
            if (plugin.isDebugMode()) {
                plugin.debug("  Player is bypassing protection, allowing action");
            }
            return true;
        }

        // Check player-specific permission if player exists
        if (player != null) {
            Map<String, Map<String, Boolean>> allPlayerPerms = area.getPlayerPermissions();
            Map<String, Boolean> playerPerms = allPlayerPerms.get(player.getName());
            
            if (plugin.isDebugMode()) {
                plugin.debug("  Checking player permissions for " + player.getName());
                plugin.debug("  All player permissions: " + allPlayerPerms);
                plugin.debug("  This player's permissions: " + playerPerms);
            }

            if (playerPerms != null && playerPerms.containsKey(togglePermission)) {
                boolean result = playerPerms.get(togglePermission);
                if (plugin.isDebugMode()) {
                    plugin.debug("  Player-specific permission found: " + result);
                }
                return result;
            } else if (plugin.isDebugMode()) {
                plugin.debug("  No player-specific permission found for " + togglePermission);
            }
        }

        // Check track permissions if LuckPerms is enabled
        if (player != null && plugin.isLuckPermsEnabled()) {
            Map<String, Map<String, Boolean>> allTrackPerms = area.getTrackPermissions();
            if (plugin.isDebugMode()) {
                plugin.debug("  Checking track permissions");
                plugin.debug("  All track permissions: " + allTrackPerms);
            }

            for (Map.Entry<String, Map<String, Boolean>> entry : allTrackPerms.entrySet()) {
                String track = entry.getKey();
                Map<String, Boolean> trackPerms = entry.getValue();
                
                if (plugin.isDebugMode()) {
                    plugin.debug("  Checking track " + track + ": " + trackPerms);
                }
                
                if (trackPerms.containsKey(togglePermission) && plugin.isPlayerInTrack(player, track)) {
                    boolean result = trackPerms.get(togglePermission);
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Track permission found for " + track + ": " + result);
                    }
                    return result;
                }
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("  No track permissions found for " + togglePermission);
            }
        }

        // Check group permissions if LuckPerms is enabled
        if (player != null && plugin.isLuckPermsEnabled()) {
            String group = plugin.getPrimaryGroup(player);
            if (group != null) {
                Map<String, Map<String, Boolean>> allGroupPerms = area.getGroupPermissions();
                Map<String, Boolean> groupPerms = allGroupPerms.get(group);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("  Checking group permissions for group " + group);
                    plugin.debug("  All group permissions: " + allGroupPerms);
                    plugin.debug("  This group's permissions: " + groupPerms);
                }

                if (groupPerms != null && groupPerms.containsKey(togglePermission)) {
                    boolean result = groupPerms.get(togglePermission);
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Group permission found for " + group + ": " + result);
                    }
                    return result;
                } else if (plugin.isDebugMode()) {
                    plugin.debug("  No group permission found for " + togglePermission);
                }
            }
        }

        // Fall back to area toggle state
        boolean result = area.getToggleState(togglePermission);
        if (plugin.isDebugMode()) {
            plugin.debug("  Using area toggle state: " + result);
            plugin.debug("  Toggle permission node: " + togglePermission);
        }
        return result;
    }

    private String mapPermissionToToggle(String permission) {
        return switch (permission) {
            case "break" -> "allowBlockBreak";
            case "build", "place" -> "allowBlockPlace";
            case "interact" -> "allowInteract";
            case "pvp" -> "allowPvP";
            case "container" -> "allowContainer";
            case "itemframe" -> "allowItemFrame";
            case "armorstand" -> "allowArmorStand";
            case "redstone" -> "allowRedstone";
            case "pistons" -> "allowPistons";
            case "hopper" -> "allowHopper";
            case "dispenser" -> "allowDispenser";
            case "fire" -> "allowFire";
            case "liquid" -> "allowLiquid";
            case "blockspread" -> "allowBlockSpread";
            case "leafdecay" -> "allowLeafDecay";
            case "iceform" -> "allowIceForm";
            case "snowform" -> "allowSnowForm";
            case "mobspawn" -> "allowMonsterSpawn";
            case "animalspawn" -> "allowAnimalSpawn";
            case "breeding" -> "allowBreeding";
            case "leashing" -> "allowLeashing";
            case "itemdrop" -> "allowItemDrop";
            case "itempickup" -> "allowItemPickup";
            case "vehicleplace" -> "allowVehiclePlace";
            case "vehiclebreak" -> "allowVehicleDamage";
            case "vehicleenter" -> "allowVehicleEnter";
            default -> permission; // If no mapping exists, use the permission as is
        };
    }
} 