package adminarea.form.handlers;

import java.util.Map;

import org.json.JSONObject;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.area.AreaDTO;
import adminarea.constants.AdminAreaConstants;
import adminarea.data.FormTrackingData;
import adminarea.form.IFormHandler;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.response.FormResponseCustom;

public class AreaSettingsHandler extends BaseFormHandler {
    public AreaSettingsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getFormId() {
        return AdminAreaConstants.FORM_AREA_SETTINGS;
    }

    @Override
    public FormWindow createForm(Player player) {
        FormTrackingData areaName = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaName == null) return null;
        return plugin.getFormFactory().createAreaSettingsMenu(plugin.getArea(areaName.getFormId())); // changed from areaName.toString()
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) return;

        Area area = plugin.getArea(areaData.getFormId());
        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
            return;
        }

        processAreaSettings(player, response, area);
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) return;

        handleMenuSelection(player, response, areaData);
    }

    private void processAreaSettings(Player player, FormResponseCustom response, Area area) {
        try {
            // Get current area data
            AreaDTO currentDTO = area.toDTO();
            
            // Update toggle states in settings
            JSONObject settings = currentDTO.settings();
            int toggleIndex = 3; // Start after basic settings
            for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
                boolean value = response.getToggleResponse(toggleIndex++);
                settings.put(toggle.getPermissionNode(), value);
            }

            // Create updated area using builder
            Area updatedArea = AreaBuilder.fromDTO(currentDTO)
                .showTitle(response.getToggleResponse(0))
                .enterMessage(response.getInputResponse(1))
                .leaveMessage(response.getInputResponse(2))
                .settings(settings)
                .build();

            // Save updated area
            plugin.updateArea(updatedArea);
            player.sendMessage(plugin.getLanguageManager().get("messages.areaUpdated", 
                Map.of("area", area.getName())));

            // Return to edit menu
            plugin.getGuiManager().openEditForm(player, updatedArea);

        } catch (Exception e) {
            plugin.getLogger().error("Failed to process area settings", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.saveChanges"));
        }
    }

    private void handleMenuSelection(Player player, FormResponseSimple response, FormTrackingData areaData) {
        try {
            Area area = plugin.getArea(areaData.getFormId());
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                return;
            }

            int buttonId = response.getClickedButtonId();
            switch (buttonId) {
                case 0 -> // Basic Settings
                    plugin.getFormFactory().createBasicSettingsForm(area);
                case 1 -> // Protection Settings
                    plugin.getFormFactory().createProtectionSettingsForm(area);
                case 2 -> // LuckPerms Settings
                    handleLuckPermsSettings(player, area);
                case 3 -> // Back
                    plugin.getGuiManager().openMainMenu(player);
                default ->
                    player.sendMessage(plugin.getLanguageManager().get("messages.invalidSelection"));
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error handling menu selection", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.menuSelection"));
        }
    }

    private void handleLuckPermsSettings(Player player, Area area) {
        if (plugin.isLuckPermsEnabled()) {
            plugin.getFormFactory().createLuckPermsGroupListForm(area);
        } else {
            player.sendMessage(plugin.getLanguageManager().get("messages.luckperms.notAvailable"));
        }
    }
}
