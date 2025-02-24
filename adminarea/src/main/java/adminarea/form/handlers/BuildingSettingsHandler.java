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

            // Get current DTO
            AreaDTO currentDTO = area.toDTO();
            JSONObject updatedSettings = new JSONObject(currentDTO.settings());

            // Get category toggles
            List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(PermissionToggle.Category.BUILDING);
            if (toggles == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidCategory"));
                plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);
                return;
            }

            // Track number of changes
            int changedSettings = 0;

            // Skip header label element (index 0)
            // Process each toggle starting at index 1
            for (int i = 0; i < toggles.size(); i++) {
                try {
                    // Get current value from settings
                    boolean currentValue = updatedSettings.optBoolean(
                        toggles.get(i).getPermissionNode(), 
                        toggles.get(i).getDefaultValue()
                    );
                    
                    Boolean toggleResponse = response.getToggleResponse(i + 1);
                    if (toggleResponse == null) {
                        plugin.getLogger().debug("Null toggle response for " + toggles.get(i).getPermissionNode());
                        continue;
                    }
                    
                    // Only count as changed if value is different
                    if (currentValue != toggleResponse) {
                        changedSettings++;
                        updatedSettings.put(toggles.get(i).getPermissionNode(), toggleResponse);
                    }
                } catch (Exception e) {
                    plugin.getLogger().debug("Error processing toggle " + toggles.get(i).getPermissionNode() + ": " + e.getMessage());
                }
            }

            // Create updated area
            Area updatedArea = AreaBuilder.fromDTO(currentDTO)
                .settings(updatedSettings)
                .build();

            // Save changes
            plugin.updateArea(updatedArea); 
            
            // Show success message with change count
            player.sendMessage(plugin.getLanguageManager().get("messages.area.updated",
                Map.of(
                    "area", area.getName(),
                    "count", String.valueOf(changedSettings)
                )));

            // Return to edit menu
            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, updatedArea);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling building settings", e);
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