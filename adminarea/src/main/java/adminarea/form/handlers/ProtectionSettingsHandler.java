package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.area.AreaDTO;
import adminarea.constants.FormIds;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

public class ProtectionSettingsHandler extends BaseFormHandler {

    public ProtectionSettingsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        validateFormId();
    }

    @Override
    public String getFormId() {
        return FormIds.PROTECTION_SETTINGS;
    }

    @Override
    public FormWindow createForm(Player player, Area area) {
        if (area == null) return null;

        FormWindowCustom form = new FormWindowCustom("Protection Settings: " + area.getName());
        
        AreaDTO dto = area.toDTO();
        JSONObject settings = dto.settings();
        for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
            String description = plugin.getLanguageManager().get(
                "gui.permissions.toggles." + toggle.getPermissionNode());
            form.addElement(new ElementToggle(
                toggle.getDisplayName() + "\n§7" + description,
                settings.optBoolean(toggle.getPermissionNode(), toggle.getDefaultValue())
            ));
        }
        
        return form;
    }

    @Override
    public FormWindow createForm(Player player) {
        return createForm(player, getEditingArea(player));
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        // Protection settings only use custom forms
        plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, getEditingArea(player));
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        try {
            Area area = getEditingArea(player);
            if (area == null) return;

            // Get current DTO
            AreaDTO currentDTO = area.toDTO();
            JSONObject updatedSettings = new JSONObject(currentDTO.settings());

            // Validate all toggle responses are present
            List<PermissionToggle> toggles = PermissionToggle.getDefaultToggles();
            for (int i = 0; i < toggles.size(); i++) {
                if (response.getResponse(i) == null) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidForm"));
                    plugin.getGuiManager().openFormById(player, FormIds.PROTECTION_SETTINGS, area);
                    return;
                }
            }

            // Update toggles
            for (int i = 0; i < toggles.size(); i++) {
                Boolean value = response.getToggleResponse(i); 
                updatedSettings.put(toggles.get(i).getPermissionNode(), value != null ? value : false);
            }

            // Create updated area
            Area updatedArea = AreaBuilder.fromDTO(currentDTO)
                .settings(updatedSettings)
                .build();

            // Save changes
            plugin.updateArea(updatedArea);
            player.sendMessage(plugin.getLanguageManager().get("messages.area.updated",
                Map.of("area", area.getName())));

            // Return to edit menu
            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, updatedArea);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling protection settings", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.saveChanges")); 
            plugin.getGuiManager().openMainMenu(player);
        }
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
