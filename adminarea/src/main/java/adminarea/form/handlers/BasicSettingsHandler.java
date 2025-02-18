package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.AdminAreaConstants;
import adminarea.data.FormTrackingData;
import adminarea.form.validation.FormValidator;
import adminarea.form.validation.ValidationResult;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;

public class BasicSettingsHandler extends BaseFormHandler {
    public BasicSettingsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getFormId() {
        return AdminAreaConstants.FORM_BASIC_SETTINGS;
    }

    @Override
    public FormWindow createForm(Player player) {
        if (player == null) return null;

        FormTrackingData trackingData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (trackingData == null) return null;

        Area area = plugin.getArea(trackingData.getFormId());
        if (area == null) return null;

        return plugin.getGuiManager().createBasicSettingsForm(area);
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        if (player == null || response == null) return;

        try {
            // Validate form
            ValidationResult validation = FormValidator.validate(response, getFormId());
            if (!validation.isValid()) {
                player.sendMessage("§c" + validation.getMessage());
                return;
            }

            // Get area being edited
            FormTrackingData trackingData = plugin.getFormIdMap().get(player.getName() + "_editing");
            if (trackingData == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.form.invalidSession"));
                return;
            }

            Area area = plugin.getArea(trackingData.getFormId());
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                return;
            }

            // Update area properties
            area.setName(response.getInputResponse(1))
                .setPriority(Integer.parseInt(response.getInputResponse(2)))
                .setShowTitle(response.getToggleResponse(4))
                .setEnterMessage(response.getInputResponse(5))
                .setLeaveMessage(response.getInputResponse(6));

            // Save changes
            plugin.saveArea(area);
            player.sendMessage(plugin.getLanguageManager().get("messages.areaUpdated", 
                java.util.Map.of("area", area.getName())));

            // Return to area settings
            plugin.getGuiManager().openAreaSettings(player, area);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling basic settings response", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error"));
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        throw new UnsupportedOperationException("This handler only supports custom forms");
    }
}
