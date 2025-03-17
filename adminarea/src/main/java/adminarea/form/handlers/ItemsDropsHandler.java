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

import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class ItemsDropsHandler extends BaseFormHandler {

    public ItemsDropsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        validateFormId();
    }

    @Override
    public String getFormId() {
        return FormIds.ITEMS_DROPS_SETTINGS;
    }

    @Override
    public FormWindow createForm(Player player) {
        return createForm(player, getEditingArea(player));
    }

    @Override
    public FormWindow createForm(Player player, Area area) {
        if (area == null) return null;

        FormWindowCustom form = new FormWindowCustom(plugin.getLanguageManager().get("gui.itemsDropsSettings.title", 
            Map.of("area", area.getName())));
        
        // Add header with clear instructions
        form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.itemsDropsSettings.header")));
        
        // Get area toggle states directly from the area object
        if (plugin.isDebugMode()) {
            plugin.debug("Creating items drops settings form for area: " + area.getName());
        }

        // Add toggles for this category
        List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(PermissionToggle.Category.ITEMS);
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
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        try {
            Area area = getEditingArea(player);
            if (area == null) return;

            // Process toggle responses
            Map<String, Boolean> toggleChanges = new HashMap<>();
            List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(PermissionToggle.Category.ITEMS);
            
            if (toggles == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidCategory"));
                plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);
                return;
            }
            
            // Debug log the response data
            if (plugin.isDebugMode()) {
                plugin.debug("Processing items/drops settings form response:");
                plugin.debug("Number of responses: " + response.getResponses().size());
                for (int i = 0; i < response.getResponses().size(); i++) {
                    plugin.debug("Response " + i + ": " + response.getResponse(i));
                }
            }

            // Skip header label element (index 0)
            int index = 1;
            for (PermissionToggle toggle : toggles) {
                String permissionNode = normalizePermissionNode(toggle.getPermissionNode());
                boolean currentValue = area.getToggleState(permissionNode);
                
                // Get response, safely handling potential nulls or type issues
                boolean newValue = false;
                Object rawValue = response.getResponse(index++);
                if (rawValue instanceof Boolean) {
                    newValue = (Boolean)rawValue;
                } else if (rawValue != null) {
                    try {
                        newValue = Boolean.parseBoolean(rawValue.toString());
                    } catch (Exception e) {
                        if (plugin.isDebugMode()) {
                            plugin.debug("Could not parse toggle value: " + rawValue);
                        }
                        continue;
                    }
                }
                
                // Only track changes, don't apply yet
                if (currentValue != newValue) {
                    toggleChanges.put(permissionNode, newValue);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Will change toggle " + permissionNode + " from " + 
                                    currentValue + " to " + newValue);
                    }
                }
            }
            
            // Use the standardized method to update toggles
            int changedSettings = updateAreaToggles(area, toggleChanges);
            
            // Use the standardized method to update the area and notify the player
            updateAreaAndNotifyPlayer(player, area, changedSettings, FormIds.EDIT_AREA, "items/drops");

        } catch (Exception e) {
            plugin.getLogger().error("Error handling items/drops settings", e);
            if (plugin.isDebugMode()) {
                e.printStackTrace();
            }
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            plugin.getGuiManager().openMainMenu(player);
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
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
