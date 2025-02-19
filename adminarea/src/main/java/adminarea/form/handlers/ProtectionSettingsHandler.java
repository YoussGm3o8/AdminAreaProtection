package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.area.AreaDTO;
import adminarea.constants.AdminAreaConstants;
import adminarea.data.FormTrackingData;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple; 
import cn.nukkit.form.window.FormWindow;

import java.util.Map;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;

public class ProtectionSettingsHandler extends BaseFormHandler {

    public ProtectionSettingsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getFormId() {
        return AdminAreaConstants.FORM_PROTECTION_SETTINGS;
    }

    @Override 
    public FormWindow createForm(Player player) {
        FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) return null;

        Area area = plugin.getArea(areaData.getFormId());
        if (area == null) return null;

        return plugin.getFormFactory().createProtectionSettingsForm(area);
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        Area area = plugin.getArea(areaData != null ? areaData.getFormId() : null);

        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
            return;
        }

        processProtectionSettings(player, response, area);
    }

    private void processProtectionSettings(Player player, FormResponseCustom response, Area area) {
        try {
            // Get current DTO
            AreaDTO currentDTO = area.toDTO();

            // Create new settings object based on current settings
            JSONObject updatedSettings = new JSONObject(currentDTO.settings());

            // Update permissions based on form response
            List<PermissionToggle> toggles = PermissionToggle.getDefaultToggles();
            for (int i = 0; i < toggles.size(); i++) {
                boolean value = response.getToggleResponse(i);
                updatedSettings.put(toggles.get(i).getPermissionNode(), value);
            }

            // Create updated area using builder
            Area updatedArea = AreaBuilder.fromDTO(currentDTO)
                .settings(updatedSettings)
                .build();

            // Save changes
            plugin.updateArea(updatedArea);

            player.sendMessage(plugin.getLanguageManager().get("messages.settingsUpdated"));

            // Return to area settings menu
            plugin.getGuiManager().openAreaSettings(player, updatedArea);

        } catch (Exception e) {
            plugin.getLogger().error("Error processing protection settings", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.saveChanges")); 
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        // Protection settings only use custom forms
        throw new UnsupportedOperationException("Protection settings only supports custom forms");
    }
}
