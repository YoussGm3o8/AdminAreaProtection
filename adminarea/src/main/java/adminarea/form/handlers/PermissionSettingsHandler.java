package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.AdminAreaConstants;
import adminarea.data.FormTrackingData;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import org.json.JSONObject;

public class PermissionSettingsHandler extends BaseFormHandler {
    
    public PermissionSettingsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getFormId() {
        return AdminAreaConstants.FORM_AREA_SETTINGS;
    }

    @Override
    public FormWindow createForm(Player player) {
        // Safely handle null player
        if (player == null) {
            return null;
        }

        // Get area being edited
        FormTrackingData trackingData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (trackingData == null) {
            return null;
        }

        Area area = plugin.getArea(trackingData.getFormId());
        if (area == null) {
            return null;
        }

        // Create form with area settings
        FormWindowCustom form = new FormWindowCustom("Permission Settings: " + area.getName());
        form.addElement(new ElementLabel("§2Configure area permissions"));

        // Add permission toggles
        JSONObject settings = area.getSettings();
        for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
            boolean currentValue = settings.optBoolean(
                toggle.getPermissionNode(), 
                toggle.getDefaultValue()
            );
            form.addElement(new ElementToggle(
                toggle.getDisplayName(), 
                currentValue
            ));
        }

        return form;
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        // Validate inputs
        if (player == null || response == null) {
            return;
        }

        try {
            // Store form data
            plugin.getFormIdMap().put(player.getName(), 
                new FormTrackingData(AdminAreaConstants.FORM_AREA_SETTINGS, System.currentTimeMillis()));

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

            // Update permissions
            JSONObject settings = area.getSettings();
            int index = 1; // Skip label element
            for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
                settings.put(toggle.getPermissionNode(), response.getToggleResponse(index++));
            }

            // Save changes
            area.setSettings(settings);
            plugin.saveArea(area);
            player.sendMessage(plugin.getLanguageManager().get("messages.areaUpdated", 
                java.util.Map.of("area", area.getName())));

            // Return to edit menu
            plugin.getGuiManager().openEditForm(player, area);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling permission settings response", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error"));
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        // This handler doesn't use simple forms
        throw new UnsupportedOperationException("This handler only supports custom forms");
    }
}
