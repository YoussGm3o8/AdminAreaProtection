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

        FormWindowCustom form = new FormWindowCustom(plugin.getLanguageManager().get("gui.buildingSettings.title", 
            Map.of("area", area.getName())));
        
        // Add header with clear instructions
        form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.buildingSettings.header")));
        
        // Get area settings
        AreaDTO dto = area.toDTO();
        JSONObject settings = dto.settings();

        // Add toggles for this category
        List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(PermissionToggle.Category.BUILDING);
        if (toggles != null) {
            for (PermissionToggle toggle : toggles) {
                String description = plugin.getLanguageManager().get(
                    "gui.permissions.toggles." + toggle.getPermissionNode());
                form.addElement(new ElementToggle(
                    plugin.getLanguageManager().get("gui.permissions.toggle.format", 
                        Map.of("name", toggle.getDisplayName(), "description", description)),
                    settings.optBoolean(toggle.getPermissionNode(), toggle.getDefaultValue())
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

            Map<String, Boolean> toggleChanges = new HashMap<>();
            List<PermissionToggle> buildingToggles = PermissionToggle.getTogglesByCategory().get(PermissionToggle.Category.BUILDING);
            if (buildingToggles == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidCategory"));
                plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);
                return;
            }

            int index = 1;
            for (PermissionToggle toggle : buildingToggles) {
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
            updateAreaAndNotifyPlayer(player, area, changedSettings, FormIds.EDIT_AREA, "building");

        } catch (Exception e) {
            plugin.getLogger().error("Error handling building settings", e);
            if (plugin.isDebugMode()) {
                e.printStackTrace();
            }
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            plugin.getGuiManager().openMainMenu(player);
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        // Building settings only use custom form responses
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