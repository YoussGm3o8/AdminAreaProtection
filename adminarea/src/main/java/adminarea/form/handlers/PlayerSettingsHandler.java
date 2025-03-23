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

        // Get the area by name - force a fresh lookup
        Area area = plugin.getArea(areaData.getFormId());
        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.areaNotFound"));
            return null;
        }
        
        // Force refresh area data from database to avoid stale cache
        try {
            Area freshArea = plugin.getDatabaseManager().loadArea(area.getName());
            if (freshArea != null) {
                area = freshArea;
                if (plugin.isDebugMode()) {
                    plugin.debug("Refreshed area data from database for player permission form");
                }
            }
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Failed to refresh area from database: " + e.getMessage());
            }
        }

        FormTrackingData playerData = plugin.getFormIdMap().get(player.getName() + PLAYER_DATA_KEY);
        
        if (playerData == null) {
            // Show player selection form with dropdown and manual input
            FormWindowCustom form = new FormWindowCustom(plugin.getLanguageManager().get(
                "gui.playerSettings.title", 
                Map.of("area", area.getName())));
            
            int elementIndex = 0;
            
            // Add header with instructions
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.playerSettings.header")));
            elementIndex++;
            
            // Add dropdown with online players
            List<String> onlinePlayers = new ArrayList<>(plugin.getServer().getOnlinePlayers().values()
                .stream()
                .map(Player::getName)
                .collect(Collectors.toList()));
            onlinePlayers.add(0, plugin.getLanguageManager().get("gui.playerSettings.labels.defaultOption")); // Add default option
            form.addElement(new ElementDropdown(plugin.getLanguageManager().get("gui.playerSettings.labels.onlinePlayers"), onlinePlayers));
            elementIndex++;
            
            // Add manual input option
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.playerSettings.labels.manualEntry"), 
                plugin.getLanguageManager().get("gui.playerSettings.labels.playerNamePlaceholder"), 
                ""));
            elementIndex++;

            // Add list of players with existing permissions
            Map<String, Map<String, Boolean>> existingPerms = area.getPlayerPermissions();
            if (!existingPerms.isEmpty()) {
                form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.playerSettings.labels.existingPermissions")));
                elementIndex++;
                StringBuilder playerList = new StringBuilder("§7");
                for (String playerName : existingPerms.keySet()) {
                    playerList.append("\n- ").append(playerName);
                }
                form.addElement(new ElementLabel(playerList.toString()));
                elementIndex++;
            }

            // Add reset all permissions button
            form.addElement(new ElementToggle(
                plugin.getLanguageManager().get("gui.playerSettings.labels.resetAllPermissions"), 
                false));
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
        FormWindowCustom form = new FormWindowCustom(plugin.getLanguageManager().get(
            "gui.playerSettings.playerTitle", 
            Map.of("player", playerName)));

        // Get the latest permissions directly from the database via PermissionOverrideManager
        // instead of relying on potentially stale data in the area object
        Map<String, Boolean> currentPerms;
        try {
            currentPerms = plugin.getPermissionOverrideManager().getPlayerPermissions(area.getName(), playerName);
            if (currentPerms == null) {
                currentPerms = new HashMap<>();
                if (plugin.isDebugMode()) {
                    plugin.debug("No permissions found in database for player " + playerName + " in area " + area.getName());
                }
            } else if (plugin.isDebugMode()) {
                plugin.debug("Loaded " + currentPerms.size() + " permissions from database for player " + 
                          playerName + " in area " + area.getName());
            }
        } catch (Exception e) {
            // Fallback to area object if database access fails
            currentPerms = area.getPlayerPermissions(playerName);
            if (plugin.isDebugMode()) {
                plugin.debug("Failed to get permissions from database, using area object: " + e.getMessage());
            }
        }
        
        // Add header with clear instructions
        form.addElement(new ElementLabel(
            plugin.getLanguageManager().get("gui.playerSettings.playerHeader", 
            Map.of("player", playerName))
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
                                
                                // Create new area with cleared player permissions - FIXED: Use safer AreaBuilder approach
                                AreaBuilder areaBuilder = AreaBuilder.fromDTO(area.toDTO());
                                areaBuilder.playerPermissions(new HashMap<>());
                                // We don't need to explicitly set group or track permissions as they're already part of the DTO
                                Area updatedArea = areaBuilder.build();
                                
                                // Force clear caches in the area
                                updatedArea.clearCaches();
                                
                                // Use updateArea directly from AreaManager to avoid duplication
                                if (plugin.isDebugMode()) {
                                    plugin.debug("Using AreaManager.updateArea to update the area without duplication");
                                }
                                
                                // Call updateArea directly on AreaManager
                                plugin.getAreaManager().updateArea(updatedArea);
                                
                                // Verify the permissions were cleared
                                if (plugin.isDebugMode()) {
                                    Area verifyArea = plugin.getArea(area.getName());
                                    plugin.debug("Verification - player permissions after reset: " + 
                                        (verifyArea != null ? verifyArea.getPlayerPermissions().size() : "null"));
                                }
                                
                                // Make sure we're using the updated area instance
                                area = plugin.getArea(area.getName());
                                
                                player.sendMessage(plugin.getLanguageManager().get("messages.form.playerPermissionsReset",
                                    Map.of("area", area.getName())));
                            } catch (Exception e) {
                                plugin.getLogger().error("Failed to reset player permissions", e);
                                player.sendMessage(plugin.getLanguageManager().get("messages.error.failedToResetPermissions"));
                            }

                            // Clean up form data
                            plugin.getFormIdMap().remove(player.getName() + "_resetIndex");
                            
                            // Update the editing area reference to ensure consistency
                            plugin.getFormIdMap().put(player.getName() + "_editing", 
                                new FormTrackingData(area.getName(), System.currentTimeMillis()));
                            
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
                    player.sendMessage(plugin.getLanguageManager().get("messages.form.noChanges"));
                    plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);
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

            // IMPORTANT: Save player permissions to the database FIRST before any area updates
            try {
                if (plugin.isDebugMode()) {
                    plugin.debug("Explicitly saving player permissions for player " + targetPlayer + 
                                " in area " + area.getName());
                    plugin.debug("  Permission count: " + newPerms.size());
                    plugin.debug("  Permissions: " + newPerms);
                }
                
                // Mark this as a permission-only operation to prevent area recreation
                plugin.getAreaManager().markPermissionOperationInProgress(area.getName());
                
                // Use the direct method which bypasses area recreation
                plugin.getPermissionOverrideManager().setPlayerPermissions(
                    area.getName(), 
                    targetPlayer, 
                    newPerms
                );
                
                // Force database checkpoint to ensure persistence
                plugin.getPermissionOverrideManager().forceWalCheckpoint();
                
                if (plugin.isDebugMode()) {
                    plugin.debug("  Successfully saved player permissions to PermissionOverrideManager");
                    
                    // Verify permissions were saved
                    Map<String, Boolean> verifyPerms = plugin.getPermissionOverrideManager().getPlayerPermissions(
                        area.getName(), targetPlayer);
                    plugin.debug("  Verification - retrieved permissions: " + 
                               (verifyPerms != null ? verifyPerms.size() : "null") + " permissions");
                    plugin.debug("  Verification - permissions content: " + verifyPerms);
                    
                    // Debug SQL query directly to see database contents
                    try {
                        plugin.debug("  Direct database query for player permissions:");
                        plugin.getPermissionOverrideManager().debugDumpPlayerPermissions(area.getName(), targetPlayer);
                    } catch (Exception e) {
                        plugin.debug("  Failed to execute direct database query: " + e.getMessage());
                    }
                }
                
                // Force invalidate all caches for this area
                plugin.getPermissionOverrideManager().invalidateCache(area.getName());
                plugin.getAreaManager().invalidateAreaCache(area.getName());
            } catch (Exception e) {
                plugin.getLogger().error("Failed to explicitly save player permissions", e);
                throw e;
            }

            // Create updated area with fresh permissions from the database
            // CRITICAL: Get fresh permissions from the database
            Map<String, Map<String, Boolean>> freshPlayerPerms = 
                plugin.getPermissionOverrideManager().getAllPlayerPermissionsFromDatabase(area.getName());
            
            if (freshPlayerPerms == null || !freshPlayerPerms.containsKey(targetPlayer)) {
                // Fallback to our manually created map if database retrieval fails
                freshPlayerPerms = playerPerms;
                if (plugin.isDebugMode()) {
                    plugin.debug("Using manual player permissions map as fallback");
                }
            } else if (plugin.isDebugMode()) {
                plugin.debug("Using fresh database permissions: " + freshPlayerPerms.size() + " players");
            }
            
            AreaBuilder areaBuilder = AreaBuilder.fromDTO(currentDTO);
            areaBuilder.playerPermissions(freshPlayerPerms);
            Area updatedArea = areaBuilder.build();

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
                    freshPlayerPerms, // Use our explicitly created playerPerms map
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

                // Use updateArea() instead of saveArea() to avoid duplication
                // The updateArea() method will handle database updates and proper cache invalidation
                if (plugin.isDebugMode()) {
                    plugin.debug("Using updateArea to update the area without duplication");
                }
                
                // Call updateArea directly on the AreaManager to ensure proper handling
                plugin.getAreaManager().updateArea(finalArea);
                
                // Finally, unmark the permission operation now that it's complete
                plugin.getAreaManager().unmarkPermissionOperation(area.getName());
                
                if (plugin.isDebugMode()) {
                    plugin.debug("  Area updated successfully through AreaManager");
                    
                    // Verify the area was updated correctly
                    Area verifyArea = plugin.getArea(finalArea.getName());
                    if (verifyArea != null) {
                        Map<String, Map<String, Boolean>> verifyPlayerPerms = verifyArea.getPlayerPermissions();
                        plugin.debug("  Verification - area player permissions: " + 
                                   (verifyPlayerPerms != null ? verifyPlayerPerms.size() : "null") + " players");
                        
                        if (verifyPlayerPerms != null && verifyPlayerPerms.containsKey(targetPlayer)) {
                            plugin.debug("  Verification - permissions for " + targetPlayer + ": " + 
                                       verifyPlayerPerms.get(targetPlayer).size() + " permissions");
                        } else {
                            plugin.debug("  Verification - player " + targetPlayer + " not found in permissions");
                        }
                    } else {
                        plugin.debug("  Verification - area not found: " + finalArea.getName());
                    }
                }
                
                // Update our reference to use the correct area
                area = finalArea;
                
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
            
            // Make sure we're using the updated area reference in the form tracking data
            // This ensures the edit menu shows the correct area without any duplication in cache
            plugin.getFormIdMap().put(player.getName() + "_editing", 
                new FormTrackingData(area.getName(), System.currentTimeMillis()));
            
            // IMPORTANT: Force reload the area from database to avoid duplication in cache
            Area refreshedArea = plugin.getDatabaseManager().loadArea(area.getName());
            if (refreshedArea != null) {
                area = refreshedArea;
                if (plugin.isDebugMode()) {
                    plugin.debug("Using freshly loaded area from database to avoid duplication");
                }
            }
                
            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling player permissions response", e);
            if (plugin.isDebugMode()) {
                e.printStackTrace();
            }
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            handleCancel(player);
        }

        // Force flush permissions to disk
        plugin.getPermissionOverrideManager().forceFlushPermissions();
        
        if (plugin.isDebugMode()) {
            plugin.debug("  Forced permissions to be flushed to disk");
        }
    }

    @Override
    public void handleCancel(Player player) {
        // Clean up all form data
        plugin.getFormIdMap().remove(player.getName() + PLAYER_DATA_KEY);
        plugin.getFormIdMap().remove(player.getName() + "_resetIndex");
        cleanup(player);
    }
}