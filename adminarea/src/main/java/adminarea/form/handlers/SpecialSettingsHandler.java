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

public class SpecialSettingsHandler extends BaseFormHandler {

    public SpecialSettingsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        validateFormId();
    }

    @Override
    public String getFormId() {
        return FormIds.SPECIAL_SETTINGS;
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
                        plugin.debug("Reloaded area from database for special settings form");
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().error("Failed to reload area from database", e);
            }
            
            FormWindowCustom form = new FormWindowCustom(plugin.getLanguageManager().get("gui.specialSettings.title", 
                Map.of("area", area.getName())));
            
            // Add header with clear instructions
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.specialSettings.header")));
            
            // Get area toggle states directly from the area object
            if (plugin.isDebugMode()) {
                plugin.debug("Creating special settings form for area: " + area.getName());
            }

            // Add toggles for this category
            List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(PermissionToggle.Category.SPECIAL);
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
            plugin.getLogger().error("Error creating special settings form", e);
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

            // Process toggle responses
            Map<String, Boolean> toggleChanges = new HashMap<>();
            List<PermissionToggle> specialToggles = PermissionToggle.getTogglesByCategory().get(PermissionToggle.Category.SPECIAL);
            
            if (specialToggles == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidCategory"));
                plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);
                return;
            }
            
            int index = 1; // Skip label element (index 0)
            for (PermissionToggle toggle : specialToggles) {
                String permissionNode = normalizePermissionNode(toggle.getPermissionNode());
                boolean currentValue = area.getToggleState(permissionNode);
                boolean newValue = response.getToggleResponse(index++);
                
                // Only track changes, don't apply yet
                if (currentValue != newValue) {
                    toggleChanges.put(permissionNode, newValue);
                }
            }
            
            // Use the standardized method to update toggles
            int changedSettings = updateAreaToggles(area, toggleChanges);
            
            // Use the standardized method to update the area and notify the player
            updateAreaAndNotifyPlayer(player, area, changedSettings, FormIds.EDIT_AREA, "special");

        } catch (Exception e) {
            plugin.getLogger().error("Error handling special settings", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            plugin.getGuiManager().openMainMenu(player);
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        // Special settings only use custom form responses
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