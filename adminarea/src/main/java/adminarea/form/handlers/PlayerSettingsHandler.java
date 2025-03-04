package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.area.AreaDTO;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.event.PlayerPermissionChangeEvent;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.element.*;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import java.util.*;
import java.util.stream.Collectors;

public class PlayerSettingsHandler extends BaseFormHandler {
    private static final String PLAYER_DATA_KEY = "_player";

    public PlayerSettingsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        validateFormId();
    }

    @Override
    public String getFormId() {
        return FormIds.PLAYER_SETTINGS;
    }

    @Override
    public FormWindow createForm(Player player) {
        FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.noAreaSelected"));
            return null;
        }

        Area area = plugin.getArea(areaData.getFormId());
        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.areaNotFound"));
            return null;
        }

        FormTrackingData playerData = plugin.getFormIdMap().get(player.getName() + PLAYER_DATA_KEY);
        
        if (playerData == null) {
            // Show player selection form with dropdown and manual input
            FormWindowCustom form = new FormWindowCustom("Player Permissions - " + area.getName());
            
            int elementIndex = 0;
            
            // Add header with instructions
            form.addElement(new ElementLabel("§2Configure Player Permissions\n§7Select a player or enter a name manually"));
            elementIndex++;
            
            // Add dropdown with online players
            List<String> onlinePlayers = new ArrayList<>(plugin.getServer().getOnlinePlayers().values()
                .stream()
                .map(Player::getName)
                .collect(Collectors.toList()));
            onlinePlayers.add(0, "-- Select Player --"); // Add default option
            form.addElement(new ElementDropdown("Online Players", onlinePlayers));
            elementIndex++;
            
            // Add manual input option
            form.addElement(new ElementInput("Or enter player name manually:", "Player name", ""));
            elementIndex++;

            // Add list of players with existing permissions
            Map<String, Map<String, Boolean>> existingPerms = area.getPlayerPermissions();
            if (!existingPerms.isEmpty()) {
                form.addElement(new ElementLabel("\n§6Players with Custom Permissions:"));
                elementIndex++;
                StringBuilder playerList = new StringBuilder("§7");
                for (String playerName : existingPerms.keySet()) {
                    playerList.append("\n- ").append(playerName);
                }
                form.addElement(new ElementLabel(playerList.toString()));
                elementIndex++;
            }

            // Add reset all permissions button
            form.addElement(new ElementToggle("\n§cReset All Player Permissions\n§7This will clear all player-specific permissions", false));
            // Store the reset toggle index for later use
            plugin.getFormIdMap().put(player.getName() + "_resetIndex", 
                new FormTrackingData(String.valueOf(elementIndex), System.currentTimeMillis()));
            
            return form;
        } else {
            // Show permission editing form for selected player
            return createPlayerPermissionForm(area, playerData.getFormId());
        }
    }

    private FormWindowCustom createPlayerPermissionForm(Area area, String playerName) {
        FormWindowCustom form = new FormWindowCustom("Edit " + playerName + " Permissions");

        Map<String, Boolean> currentPerms = area.getPlayerPermissions(playerName);
        
        // Add header with clear instructions
        form.addElement(new ElementLabel(
            "§2Player Permissions for " + playerName + "\n" +
            "§7These permissions will override track and group permissions.\n" +
            "§7Area permission changes will reset these settings."
        ));
        
        // Add toggles for player-relevant permissions
        for (PermissionToggle toggle : PermissionToggle.getPlayerToggles()) {
            boolean currentValue = currentPerms.getOrDefault(toggle.getPermissionNode(), area.getToggleState(toggle.getPermissionNode()));
            String description = plugin.getLanguageManager().get(
                "gui.permissions.toggles." + toggle.getPermissionNode(),
                Map.of("player", playerName)
            );
            form.addElement(new ElementToggle(
                toggle.getDisplayName() + "\n§7" + description,
                currentValue
            ));
        }

        return form;
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        handleCancel(player);
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        if (response == null) {
            handleCancel(player);
            return;
        }

        try {
            FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
            FormTrackingData playerData = plugin.getFormIdMap().get(player.getName() + PLAYER_DATA_KEY);

            if (areaData == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.form.invalidSession"));
                return;
            }

            Area area = plugin.getArea(areaData.getFormId());
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                return;
            }

            if (playerData == null) {
                // Get the stored reset toggle index
                FormTrackingData resetIndexData = plugin.getFormIdMap().get(player.getName() + "_resetIndex");
                if (resetIndexData != null) {
                    try {
                        int resetIndex = Integer.parseInt(resetIndexData.getFormId());
                        boolean resetAll = response.getToggleResponse(resetIndex);
                        if (resetAll) {
                            try {
                                // Delete all player permissions from the database
                                // Fix: We need to get all player permissions and delete them one by one
                                Map<String, Map<String, Boolean>> allPlayerPerms = area.getPlayerPermissions();
                                if (!allPlayerPerms.isEmpty()) {
                                    for (String playerName : allPlayerPerms.keySet()) {
                                        plugin.getPermissionOverrideManager().deletePlayerPermissions(area.getName(), playerName);
                                        
                                        if (plugin.isDebugMode()) {
                                            plugin.debug("Deleted permissions for player " + playerName + " in area: " + area.getName());
                                        }
                                    }
                                }
                                
                                if (plugin.isDebugMode()) {
                                    plugin.debug("Deleted all player permissions for area: " + area.getName() + " from permission_overrides database");
                                }
                                
                                // Create new area with cleared player permissions
                                Area updatedArea = AreaBuilder.fromDTO(area.toDTO())
                                    .playerPermissions(new HashMap<>())
                                    .build();
                                
                                // Force clear caches in the area
                                updatedArea.clearCaches();
                                
                                // Save the area to database to ensure changes are persisted
                                plugin.getDatabaseManager().saveArea(updatedArea);
                                
                                // Update area in plugin
                                plugin.updateArea(updatedArea);
                                
                                // Verify the permissions were cleared
                                if (plugin.isDebugMode()) {
                                    Area verifyArea = plugin.getArea(area.getName());
                                    plugin.debug("Verification - player permissions after reset: " + 
                                        (verifyArea != null ? verifyArea.getPlayerPermissions().size() : "null"));
                                }
                                
                                player.sendMessage(plugin.getLanguageManager().get("messages.form.playerPermissionsReset",
                                    Map.of("area", area.getName())));
                            } catch (Exception e) {
                                plugin.getLogger().error("Failed to reset player permissions", e);
                                player.sendMessage(plugin.getLanguageManager().get("messages.error.failedToResetPermissions"));
                            }

                            // Clean up form data
                            plugin.getFormIdMap().remove(player.getName() + "_resetIndex");
                            
                            // Return to edit menu
                            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);
                            return;
                        }
                    } catch (NumberFormatException e) {
                        if (plugin.isDebugMode()) {
                            plugin.debug("Error parsing reset index: " + e.getMessage());
                        }
                    }
                }

                // Handle player selection
                String selectedPlayer = null;
                
                // Check dropdown selection first
                int dropdownIndex = response.getDropdownResponse(1).getElementID();
                if (dropdownIndex > 0) { // Skip default option
                    List<String> onlinePlayers = new ArrayList<>(plugin.getServer().getOnlinePlayers().values()
                        .stream()
                        .map(Player::getName)
                        .collect(Collectors.toList()));
                    selectedPlayer = onlinePlayers.get(dropdownIndex - 1); // Adjust for default option
                }
                
                // If no dropdown selection, check manual input
                if (selectedPlayer == null || selectedPlayer.isEmpty()) {
                    selectedPlayer = response.getInputResponse(2);
                }
                
                // Validate player selection
                if (selectedPlayer == null || selectedPlayer.trim().isEmpty()) {
                    player.sendMessage(plugin.getLanguageManager().get("validation.form.error.generic"));
                    return;
                }

                plugin.getFormIdMap().put(
                    player.getName() + PLAYER_DATA_KEY,
                    new FormTrackingData(selectedPlayer.trim(), System.currentTimeMillis())
                );
                
                // Clean up form data
                plugin.getFormIdMap().remove(player.getName() + "_resetIndex");
                
                plugin.getGuiManager().openFormById(player, FormIds.PLAYER_SETTINGS, area);
                return;
            }

            // Get current DTO
            AreaDTO currentDTO = area.toDTO();
            String targetPlayer = playerData.getFormId();
            
            if (plugin.isDebugMode()) {
                plugin.debug("Processing player permissions for " + targetPlayer + " in area " + area.getName());
                plugin.debug("DTO before processing: " + currentDTO);
            }
            
            // Process permissions
            Map<String, Boolean> newPerms = new HashMap<>();
            int index = 1; // Skip the header label
            for (PermissionToggle toggle : PermissionToggle.getPlayerToggles()) {
                Boolean value = response.getToggleResponse(index);
                // Don't add the gui.permissions.toggles prefix for player permissions
                newPerms.put(toggle.getPermissionNode(), value != null ? value : false);
                index++;
            }

            // Update player permissions
            Map<String, Map<String, Boolean>> playerPerms = new HashMap<>(currentDTO.playerPermissions());
            Map<String, Boolean> oldPerms = playerPerms.getOrDefault(targetPlayer, new HashMap<>());
            
            // IMPORTANT: Make sure to create new instances to avoid reference issues
            playerPerms = new HashMap<>(playerPerms);
            playerPerms.put(targetPlayer, new HashMap<>(newPerms));

            if (plugin.isDebugMode()) {
                plugin.debug("Updating player permissions for " + targetPlayer + " in area " + area.getName());
                plugin.debug("Old permissions: " + oldPerms);
                plugin.debug("New permissions: " + newPerms);
                plugin.debug("Updated player permissions map: " + playerPerms);
            }

            // Create updated area with new permissions
            Area updatedArea = AreaBuilder.fromDTO(currentDTO)
                .playerPermissions(playerPerms)
                .build();

            if (plugin.isDebugMode()) {
                plugin.debug("Built updated area: " + updatedArea.getName());
                plugin.debug("Updated area player permissions: " + updatedArea.getPlayerPermissions());
                plugin.debug("DTO player permissions: " + updatedArea.toDTO().playerPermissions());
            }

            // Fire event
            PlayerPermissionChangeEvent event = new PlayerPermissionChangeEvent(area, targetPlayer, oldPerms, newPerms);
            plugin.getServer().getPluginManager().callEvent(event);

            if (event.isCancelled()) {
                player.sendMessage(plugin.getLanguageManager().get("messages.form.permissionUpdateCancelled"));
                return;
            }

            // Update area in plugin (this will save to database)
            try {
                // IMPORTANT: Clear permission caches before updating
                updatedArea.clearCaches();
                
                // Force clear all caches in area manager
                plugin.getAreaManager().invalidateAreaCache(area.getName());
                
                // Create a new DTO with updated permissions to ensure they're preserved
                AreaDTO updatedDTO = new AreaDTO(
                    updatedArea.getName(),
                    updatedArea.getWorld(),
                    updatedArea.toDTO().bounds(),
                    updatedArea.getPriority(),
                    updatedArea.toDTO().showTitle(),
                    updatedArea.toDTO().toggleStates(), // Keep original toggle states
                    updatedArea.toDTO().groupPermissions(),
                    updatedArea.toDTO().inheritedPermissions(),
                    updatedArea.toDTO().toggleStates(),
                    updatedArea.toDTO().defaultToggleStates(),
                    updatedArea.toDTO().inheritedToggleStates(),
                    updatedArea.toDTO().permissions(),
                    updatedArea.toDTO().enterMessage(),
                    updatedArea.toDTO().leaveMessage(),
                    updatedArea.toDTO().trackPermissions(),
                    playerPerms, // Use our explicitly created playerPerms map
                    updatedArea.toDTO().potionEffects()
                );
                
                // Create final area with guaranteed player permissions
                Area finalArea = AreaBuilder.fromDTO(updatedDTO).build();
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Final area before saving:");
                    plugin.debug("Player permissions in DTO: " + finalArea.toDTO().playerPermissions());
                    plugin.debug("Direct player permissions: " + finalArea.getPlayerPermissions());
                    plugin.debug("Toggle states: " + finalArea.toDTO().toggleStates());
                }

                // IMPORTANT: Explicitly save player permissions to the permission database first
                try {
                    plugin.getPermissionOverrideManager().setPlayerPermissions(
                        finalArea.getName(), 
                        targetPlayer, 
                        newPerms
                    );
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Explicitly saved player permissions for " + targetPlayer);
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Failed to explicitly save player permissions", e);
                    throw e;
                }

                // Update in database first
                try {
                    plugin.getDatabaseManager().saveArea(finalArea);
                    
                    // Force synchronize permissions to ensure they're saved
                    plugin.getPermissionOverrideManager().synchronizeFromArea(finalArea);
                    
                    // Verify the save
                    Area verifyArea = plugin.getDatabaseManager().loadArea(area.getName());
                    if (verifyArea != null && plugin.isDebugMode()) {
                        plugin.debug("Database save verification:");
                        plugin.debug("Saved player permissions: " + verifyArea.getPlayerPermissions());
                        plugin.debug("Expected player permissions: " + playerPerms);
                        plugin.debug("Toggle states after save: " + verifyArea.toDTO().toggleStates());
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Failed to save area to database", e);
                    throw e;
                }
                
                // Then update in plugin
                plugin.updateArea(finalArea);
                
                if (plugin.isDebugMode()) {
                    // Verify the update worked by retrieving the area again
                    Area verifyArea = plugin.getArea(area.getName());
                    if (verifyArea != null) {
                        plugin.debug("Verification - area player permissions after update: " + verifyArea.getPlayerPermissions());
                        plugin.debug("Verification - permissions for " + targetPlayer + ": " + verifyArea.getPlayerPermissions(targetPlayer));
                    } else {
                        plugin.debug("Verification failed - area not found after update");
                    }
                }
                
                player.sendMessage(plugin.getLanguageManager().get("messages.form.playerPermissionsUpdated",
                    Map.of("player", targetPlayer, "area", area.getName())));
            } catch (Exception e) {
                plugin.getLogger().error("Failed to update player permissions for " + targetPlayer, e);
                if (plugin.isDebugMode()) {
                    e.printStackTrace();
                }
                player.sendMessage(plugin.getLanguageManager().get("messages.error.failedToUpdatePermissions"));
            }

            // Clear player selection and return to edit menu
            plugin.getFormIdMap().remove(player.getName() + PLAYER_DATA_KEY);
            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, updatedArea);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling player permissions response", e);
            if (plugin.isDebugMode()) {
                e.printStackTrace();
            }
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            handleCancel(player);
        }
    }

    @Override
    public void handleCancel(Player player) {
        // Clean up all form data
        plugin.getFormIdMap().remove(player.getName() + PLAYER_DATA_KEY);
        plugin.getFormIdMap().remove(player.getName() + "_resetIndex");
        plugin.getGuiManager().openMainMenu(player);
    }
}