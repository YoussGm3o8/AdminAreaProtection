package adminarea.form.handlers;

import java.util.Map;

import org.json.JSONObject;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
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
            // Get values from form response
            boolean showTitle = response.getToggleResponse(0);
            String enterMessage = response.getInputResponse(1);
            String leaveMessage = response.getInputResponse(2);

            // Create builder with current settings
            AreaBuilder builder = Area.builder()
                .name(area.getName())
                .world(area.getWorld())
                .coordinates(area.getXMin(), area.getXMax(), 
                            area.getYMin(), area.getYMax(),
                            area.getZMin(), area.getZMax())
                .priority(area.getPriority())
                .showTitle(showTitle)
                .enterMessage(enterMessage)
                .leaveMessage(leaveMessage);

            // Apply permission settings from form
            JSONObject settings = area.getSettings();
            int toggleIndex = 3; // Start after basic settings
            for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
                boolean value = response.getToggleResponse(toggleIndex++);
                settings.put(toggle.getPermissionNode(), value);
            }
            builder.settings(settings);

            // Build and save updated area
            Area updatedArea = builder.build();
            plugin.getAreaManager().updateArea(updatedArea);
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
                    plugin.getGuiManager().openBasicSettings(player, area);
                case 1 -> // Protection Settings
                    plugin.getGuiManager().openProtectionSettings(player, area);
                case 2 -> // Building Settings
                    plugin.getGuiManager().openBuildingPermissions(player, area);
                case 3 -> // Environment Settings  
                    plugin.getGuiManager().openEnvironmentSettings(player, area);
                case 4 -> // Entity Settings
                    plugin.getGuiManager().openEntityControls(player, area);
                case 5 -> // Technical Settings
                    plugin.getGuiManager().openRedstoneAndMechanics(player, area);
                case 6 -> // Special Settings
                    plugin.getGuiManager().openSpecialPermissions(player, area);
                case 7 -> { // LuckPerms Group Permissions
                    if (plugin.isLuckPermsEnabled()) {
                        plugin.getGuiManager().openLuckPermsGroupList(player, area);
                    } else {
                        player.sendMessage(plugin.getLanguageManager().get("messages.luckperms.notAvailable"));
                    }
                }
                case 8 -> // Back to Main Menu
                    plugin.getGuiManager().openMainMenu(player);
                default ->
                    player.sendMessage(plugin.getLanguageManager().get("messages.invalidSelection"));
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error handling menu selection", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.menuSelection"));
        }
    }
}
