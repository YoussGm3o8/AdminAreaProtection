package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.FormIds;
import adminarea.form.utils.FormUtils;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.form.window.FormWindowCustom;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom form for editing building-related permissions of an area.
 */
public class BuildingSettingsHandler {
    private final AdminAreaProtectionPlugin plugin;

    public BuildingSettingsHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Open the building settings form for a player with no pre-selected area
     */
    public void open(Player player) {
        Area area = getEditingArea(player);
        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.noAreaSelected"));
            FormUtils.cleanup(player);
            plugin.getGuiManager().openMainMenu(player);
            return;
        }
        open(player, area);
    }

    /**
     * Open the building settings form for a player and area
     */
    public void open(Player player, Area area) {
        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.noAreaSelected"));
            FormUtils.cleanup(player);
            plugin.getGuiManager().openMainMenu(player);
            return;
        }

        try {
            // Force reload the area from the database to ensure we have the latest data
            try {
                Area freshArea = plugin.getDatabaseManager().loadArea(area.getName());
                if (freshArea != null) {
                    // Use the fresh area
                    area = freshArea;
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Reloaded area from database for building settings form");
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().error("Failed to reload area from database", e);
            }
            
            // Create a custom form with the area name in the title
            FormWindowCustom form = new FormWindowCustom(plugin.getLanguageManager().get("gui.buildingSettings.title", 
                Map.of("area", area.getName())));
            
            // Add header with clear instructions
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.buildingSettings.header")));
            
            // Store the toggles and their indices for easier response handling
            Map<Integer, String> toggleIndices = new HashMap<>();
            int elementIndex = 1; // Start at 1 because index 0 is the header label
            
            // Add toggles for this category
            List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(PermissionToggle.Category.BUILDING);
            if (toggles != null) {
                for (PermissionToggle toggle : toggles) {
                    String permissionNode = FormUtils.normalizePermissionNode(toggle.getPermissionNode());
                    String description = plugin.getLanguageManager().get(
                        "gui.permissions.toggles." + toggle.getPermissionNode());
                    
                    // Get current toggle state with default fallback
                    boolean currentValue = area.getToggleState(permissionNode);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Toggle " + toggle.getDisplayName() + " (" + permissionNode + ") value: " + currentValue);
                    }
                    
                    form.addElement(new ElementToggle(
                        plugin.getLanguageManager().get("gui.permissions.toggle.format", 
                            Map.of("name", toggle.getDisplayName(), "description", description)),
                        currentValue
                    ));
                    
                    // Store the index for this toggle
                    toggleIndices.put(elementIndex, permissionNode);
                    elementIndex++;
                }
            }
            
            // Store reference to the current area for the response handler
            final Area finalArea = area;
            final Map<Integer, String> finalToggleIndices = toggleIndices;
            
            // Add response handler
            form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
                if (form.wasClosed()) {
                    handleCancel(player);
                    return;
                }
                
                try {
                    Map<String, Boolean> toggleStates = new HashMap<>();
                    Map<String, Boolean> changedToggleStates = new HashMap<>();
                    int changedCount = 0;
                    
                    // Process all toggles
                    for (Map.Entry<Integer, String> entry : finalToggleIndices.entrySet()) {
                        int index = entry.getKey();
                        String toggleKey = entry.getValue();
                        
                        // Get current value from area
                        boolean currentValue = finalArea.getToggleState(toggleKey);
                        
                        // Get new value from form response
                        boolean newValue = form.getResponse().getToggleResponse(index);
                        
                        // Store all toggle states
                        toggleStates.put(toggleKey, newValue);
                        
                        // Track only changed toggles
                        if (currentValue != newValue) {
                            changedToggleStates.put(toggleKey, newValue);
                            changedCount++;
                            
                            if (plugin.isDebugMode()) {
                                plugin.debug("Changed toggle " + toggleKey + " from " + currentValue + " to " + newValue);
                            }
                        }
                    }
                    
                    // Only update if something changed
                    if (changedCount > 0) {
                        // Update the toggle states in the area
                        FormUtils.updateAreaToggles(finalArea, changedToggleStates);
                        
                        // Update the area and notify the player
                        FormUtils.updateAreaAndNotifyPlayer(player, finalArea, changedCount, FormIds.EDIT_AREA, "building");
                    } else {
                        // No changes made, return to edit form
                        player.sendMessage(plugin.getLanguageManager().get("messages.form.noChanges"));
                        FormUtils.cleanup(player);
                        plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, finalArea);
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Error processing building settings form", e);
                    player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
                    FormUtils.cleanup(player);
                }
            }));
            
            // Add error handler as fallback
            form.addHandler(FormUtils.createErrorHandler(player));
            
            // Send the form to the player
            player.showFormWindow(form);
            
            // Update form tracking
            plugin.getFormIdMap().put(player.getName(), new adminarea.data.FormTrackingData(
                FormIds.BUILDING_SETTINGS, System.currentTimeMillis()));
            
        } catch (Exception e) {
            plugin.getLogger().error("Error creating building settings form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.createError", 
                Map.of("error", e.getMessage())));
            FormUtils.cleanup(player);
        }
    }

    /**
     * Handle form cancellation
     */
    private void handleCancel(Player player) {
        // Get the area being edited
        Area area = getEditingArea(player);
        
        // Return to the edit area form
        FormUtils.cleanup(player);
        if (area != null) {
            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);
        } else {
            plugin.getGuiManager().openMainMenu(player);
        }
    }

    /**
     * Get the area being edited by a player
     */
    private Area getEditingArea(Player player) {
        var areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) {
            return null;
        }
        return plugin.getArea(areaData.getFormId());
    }
} 