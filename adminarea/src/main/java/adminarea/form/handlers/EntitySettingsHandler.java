package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.area.AreaDTO;
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

public class EntitySettingsHandler extends BaseFormHandler {

    public EntitySettingsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        validateFormId();
    }

    @Override
    public String getFormId() {
        return FormIds.ENTITY_SETTINGS;
    }

    @Override
    public FormWindow createForm(Player player) {
        return createForm(player, getEditingArea(player));
    }

    @Override
    public FormWindow createForm(Player player, Area area) {
        if (area == null) return null;

        try {
            FormWindowCustom form = new FormWindowCustom(plugin.getLanguageManager().get("gui.entitySettings.title", 
                Map.of("area", area.getName())));
            
            // Add header with clear instructions
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.entitySettings.header")));
            
            // Get area settings
            AreaDTO dto = area.toDTO();
            JSONObject settings = dto.settings();

            // Add toggles for this category
            List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(PermissionToggle.Category.ENTITY);
            if (toggles != null) {
                for (PermissionToggle toggle : toggles) {
                    try {
                        String permissionNode = normalizePermissionNode(toggle.getPermissionNode());
                        String description = plugin.getLanguageManager().get(
                            "gui.permissions.toggles." + toggle.getPermissionNode());
                        
                        // Add player placeholder for relevant toggles
                        Map<String, String> placeholders = new HashMap<>();
                        placeholders.put("name", toggle.getDisplayName());
                        placeholders.put("description", description);
                        if (description.contains("{player}")) {
                            placeholders.put("player", "players");
                        }
                        
                        form.addElement(new ElementToggle(
                            plugin.getLanguageManager().get("gui.permissions.toggle.format", placeholders),
                            settings.optBoolean(permissionNode, toggle.getDefaultValue())
                        ));
                    } catch (Exception e) {
                        plugin.getLogger().error("Error adding toggle " + toggle.getPermissionNode(), e);
                        player.sendMessage(plugin.getLanguageManager().get("messages.form.entity.mobTypeError",
                            Map.of("type", toggle.getPermissionNode(), "toggle", toggle.getDisplayName())));
                    }
                }
            }
            
            return form;
        } catch (Exception e) {
            plugin.getLogger().error("Error creating entity settings form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.createError",
                Map.of("error", e.getMessage())));
            return null;
        }
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        try {
            Area area = getEditingArea(player);
            if (area == null) return;

            // Track number of changes
            int changedSettings = 0;

            // Debug log the response data
            if (plugin.isDebugMode()) {
                plugin.debug("Processing entity settings form response:");
                plugin.debug("Number of responses: " + response.getResponses().size());
                for (int i = 0; i < response.getResponses().size(); i++) {
                    plugin.debug("Response " + i + ": " + response.getResponse(i));
                }
            }

            // Get category toggles
            List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(PermissionToggle.Category.ENTITY);
            if (toggles == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidCategory"));
                plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);
                return;
            }

            // Skip header label element (index 0)
            // Process each toggle starting at index 1
            for (int i = 0; i < toggles.size(); i++) {
                try {
                    String permissionNode = normalizePermissionNode(toggles.get(i).getPermissionNode());
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Processing toggle: " + toggles.get(i).getDisplayName() + 
                                    " with permission node: " + permissionNode);
                    }
                    
                    // Get current value directly from the area's toggle state
                    boolean currentValue = area.getToggleState(permissionNode);
                    
                    // Get raw response first
                    Object rawResponse = response.getResponse(i + 1);
                    if (rawResponse == null) {
                        if (plugin.isDebugMode()) {
                            plugin.debug("Null raw response for toggle " + permissionNode + " at index " + (i + 1));
                        }
                        continue;
                    }

                    // Convert response to boolean
                    boolean toggleValue;
                    if (rawResponse instanceof Boolean) {
                        toggleValue = (Boolean) rawResponse;
                    } else {
                        if (plugin.isDebugMode()) {
                            plugin.debug("Invalid response type for toggle " + permissionNode + 
                                ": " + rawResponse.getClass().getName());
                        }
                        continue;
                    }
                    
                    // Only count as changed if value is different
                    if (currentValue != toggleValue) {
                        changedSettings++;
                        
                        // Update the toggle state directly in the area object
                        area.setToggleState(permissionNode, toggleValue);
                        
                        if (plugin.isDebugMode()) {
                            plugin.debug("Updated toggle " + permissionNode + " from " + 
                                currentValue + " to " + toggleValue);
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Error processing toggle " + toggles.get(i).getPermissionNode() + ": " + e.getMessage());
                    if (plugin.isDebugMode()) {
                        e.printStackTrace();
                    }
                }
            }

            // Save changes - first make sure toggle states are synchronized
            if (changedSettings > 0) {
                // Explicitly synchronize toggle states before recreation
                if (plugin.isDebugMode()) {
                    plugin.debug("Before synchronization - toggle states: " + area.toDTO().toggleStates());
                }
                
                area = area.synchronizeToggleStates();
                
                if (plugin.isDebugMode()) {
                    plugin.debug("After synchronization - toggle states: " + area.toDTO().toggleStates());
                    plugin.debug("After synchronization - settings: " + area.toDTO().settings());
                }
                
                // Now recreate the area with the updated toggle states
                area = plugin.getAreaManager().recreateArea(area);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Recreated area with " + changedSettings + " toggle changes for area " + area.getName());
                    plugin.debug("Final toggle states: " + area.toDTO().toggleStates());
                    plugin.debug("Final settings: " + area.toDTO().settings());
                    
                    // Double-check if the changes were saved
                    try {
                        Area freshArea = plugin.getDatabaseManager().loadArea(area.getName());
                        if (freshArea != null) {
                            plugin.debug("Verification from database:");
                            plugin.debug("  Toggle states from DB: " + freshArea.toDTO().toggleStates());
                            plugin.debug("  Settings from DB: " + freshArea.toDTO().settings());
                        }
                    } catch (Exception e) {
                        plugin.debug("Error verifying area from database: " + e.getMessage());
                    }
                }
            }
            
            // Show success message with change count
            player.sendMessage(plugin.getLanguageManager().get("messages.area.updated",
                Map.of(
                    "area", area.getName(),
                    "count", String.valueOf(changedSettings)
                )));

            // Return to edit menu
            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling entity settings", e);
            if (plugin.isDebugMode()) {
                e.printStackTrace();
            }
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            plugin.getGuiManager().openMainMenu(player);
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        // Entity settings only use custom form responses
        plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, getEditingArea(player));
    }

    private Area getEditingArea(Player player) {
        var areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.noAreaSelected"));
            return null;
        }
        return plugin.getArea(areaData.getFormId());
    }
} 