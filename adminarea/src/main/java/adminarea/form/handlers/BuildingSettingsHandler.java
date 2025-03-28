package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.FormIds;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

public class BuildingSettingsHandler extends BaseFormHandler {

    public BuildingSettingsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        validateFormId();
    }

    @Override
    public String getFormId() {
        return FormIds.BUILDING_SETTINGS;
    }

    @Override
    public FormWindow createForm(Player player) {
        return createForm(player, getEditingArea(player));
    }

    @Override
    public FormWindow createForm(Player player, Area area) {
        if (area == null) return null;

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
            
            FormWindowCustom form = new FormWindowCustom(plugin.getLanguageManager().get("gui.buildingSettings.title", 
                Map.of("area", area.getName())));
            
            // Add header with clear instructions
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.buildingSettings.header")));
            
            // Get area toggle states directly from the area object
            if (plugin.isDebugMode()) {
                plugin.debug("Creating building settings form for area: " + area.getName());
            }

            // Add toggles for this category
            List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(PermissionToggle.Category.BUILDING);
            if (toggles != null) {
                for (PermissionToggle toggle : toggles) {
                    String permissionNode = normalizePermissionNode(toggle.getPermissionNode());
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
                }
            }
            
            return form;
        } catch (Exception e) {
            plugin.getLogger().error("Error creating building settings form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.createError", 
                Map.of("error", e.getMessage())));
            return null;
        }
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        if (response == null) {
            handleCancel(player);
            return;
        }

        try {
            // Get the area being edited
            String areaName = plugin.getFormIdMap().get(player.getName() + "_editing").getFormId();
            Area area = plugin.getArea(areaName);
            
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                handleCancel(player);
                return;
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("Processing building settings form response for area: " + area.getName());
            }
            
            Map<String, Boolean> toggleStates = new HashMap<>();
            Map<String, Boolean> changedToggleStates = new HashMap<>();
            List<String> changedToggles = new ArrayList<>();
            
            // Process toggle states for building-related permissions
            List<PermissionToggle> buildingToggles = PermissionToggle.getTogglesByCategory().get(PermissionToggle.Category.BUILDING);
            int changedCount = 0;
            
            if (buildingToggles != null) {
                for (int i = 0; i < buildingToggles.size(); i++) {
                    PermissionToggle toggle = buildingToggles.get(i);
                    int toggleIndex = i + 1; // Offset for element index
                    
                    String toggleKey = normalizePermissionNode(toggle.getPermissionNode());
                    
                    // Get the current value from area
                    boolean currentValue = area.getToggleState(toggleKey);
                    
                    // Get the new value from the response
                    boolean newValue = response.getToggleResponse(toggleIndex);
                    
                    // If they differ, count as changed
                    if (currentValue != newValue) {
                        changedCount++;
                        
                        // For debugging
                        if (plugin.isDebugMode()) {
                            plugin.debug("Will change toggle " + toggleKey + " from " + currentValue + " to " + newValue);
                        }
                        
                        // Track the changed toggle
                        changedToggles.add(toggleKey);
                        
                        // Add to changed toggle states map (only the ones that changed)
                        changedToggleStates.put(toggleKey, newValue);
                    }
                    
                    // Add to complete toggle states map (for reference)
                    toggleStates.put(toggleKey, newValue);
                }
            }
            
            if (plugin.isDebugMode() && changedCount > 0) {
                plugin.debug("Updating " + changedCount + " toggle states for area: " + area.getName());
                plugin.debug("Changed toggle states: " + changedToggleStates);
            } else if (plugin.isDebugMode()) {
                plugin.debug("No toggle states changed for area: " + area.getName());
            }
            
            // Only update if something actually changed
            if (changedCount > 0) {
                // Update the toggle states in memory
                int updatedCount = updateAreaToggles(area, changedToggleStates);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Updated " + updatedCount + " toggle states via updateAreaToggles");
                }
                
                // Update the area and notify the player
                updateAreaAndNotifyPlayer(player, area, updatedCount, FormIds.EDIT_AREA, "building");
            } else {
                // No changes, just return to the edit area form
                plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);
            }
            
        } catch (Exception e) {
            plugin.getLogger().error("Error handling building settings form response", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            handleCancel(player);
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        // Building settings only use custom form responses
        plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, getEditingArea(player));
    }

    protected Area getEditingArea(Player player) {
        var areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.noAreaSelected"));
            return null;
        }
        return plugin.getArea(areaData.getFormId());
    }
} 