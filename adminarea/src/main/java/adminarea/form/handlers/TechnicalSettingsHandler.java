package adminarea.form.handlers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.area.AreaDTO;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.permissions.PermissionToggle;
import adminarea.util.ValidationUtils; // Add this import if missing
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import org.json.JSONObject;

public class TechnicalSettingsHandler extends BaseFormHandler {

    public TechnicalSettingsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getFormId() {
        return FormIds.TECHNICAL_SETTINGS;
    }

    @Override
    public FormWindow createForm(Player player) {
        FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) return null;

        Area area = plugin.getArea(areaData.getFormId());
        if (area == null) return null;

        return plugin.getFormFactory().createTechnicalSettingsForm(area);
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        try {
            FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
            Area area = plugin.getArea(areaData != null ? areaData.getFormId() : null);

            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                return;
            }

            // Get current DTO
            AreaDTO currentDTO = area.toDTO();

            // Create new settings object based on current settings
            JSONObject updatedSettings = new JSONObject(currentDTO.settings());

            // Process technical toggles
            List<PermissionToggle> technicalToggles = PermissionToggle.getTogglesByCategory()
                .get(PermissionToggle.Category.TECHNICAL);
            int index = 0;
            for (PermissionToggle toggle : technicalToggles) {
                boolean value = response.getToggleResponse(index++);
                updatedSettings.put(toggle.getPermissionNode(), value);
            }

            // Create updated area using builder
            Area updatedArea = AreaBuilder.fromDTO(currentDTO)
                .settings(updatedSettings)
                .build();

            // Save changes
            plugin.updateArea(updatedArea);

            player.sendMessage(plugin.getLanguageManager().get("messages.settingsUpdated"));
            plugin.getGuiManager().openAreaSettings(player, updatedArea);

        } catch (Exception e) {
            plugin.getLogger().error("Error processing technical settings form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error"));
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        throw new UnsupportedOperationException("Technical settings form does not use simple responses");
    }
}
