package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.form.IFormHandler;
import adminarea.constants.AdminAreaConstants;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.element.ElementToggle;
import org.json.JSONObject;

public class PermissionSettingsHandler implements IFormHandler {
    private final AdminAreaProtectionPlugin plugin;

    public PermissionSettingsHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getFormId() {
        return AdminAreaConstants.FORM_AREA_SETTINGS;
    }

    @Override
    public FormWindow createForm(Player player) {
        String areaName = plugin.getFormIdMap().get(player.getName() + "_editing");
        Area area = plugin.getArea(areaName);
        
        if (area == null) {
            return null;
        }

        FormWindowCustom form = new FormWindowCustom("Permission Settings: " + area.getName());
        
        // Add toggles for each permission
        for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
            boolean currentValue = area.getSettings()
                .optBoolean(toggle.getPermissionNode(), toggle.getDefaultValue());
            form.addElement(new ElementToggle(toggle.getDisplayName(), currentValue));
        }

        return form;
    }

    @Override
    public void handleResponse(Player player, Object response) {
        if (!(response instanceof FormResponseCustom)) return;

        String areaName = plugin.getFormIdMap().get(player.getName() + "_editing");
        Area area = plugin.getArea(areaName);
        
        if (area == null) {
            player.sendMessage(AdminAreaConstants.MSG_AREA_NOT_FOUND);
            return;
        }

        FormResponseCustom customResponse = (FormResponseCustom) response;
        JSONObject settings = new JSONObject();
        
        // Process each toggle response
        PermissionToggle[] toggles = PermissionToggle.getDefaultToggles();
        for (int i = 0; i < toggles.length; i++) {
            settings.put(toggles[i].getPermissionNode(), customResponse.getToggleResponse(i));
        }

        area.setSettings(settings);
        plugin.saveArea(area);
        player.sendMessage(String.format(AdminAreaConstants.MSG_AREA_UPDATED, area.getName()));
    }
}
