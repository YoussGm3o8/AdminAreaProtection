package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;

import java.util.Map;
import java.util.HashMap;

public class OverrideEditHandler extends BaseFormHandler {

    public OverrideEditHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getFormId() {
        return FormIds.OVERRIDE_EDIT;
    }

    @Override
    public FormWindow createForm(Player player) {
        FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        FormTrackingData groupData = plugin.getFormIdMap().get(player.getName() + "_group");
        
        if (areaData == null || groupData == null) return null;

        Area area = plugin.getArea(areaData.getFormId());
        String groupName = groupData.getFormId();

        if (area == null || groupName == null) return null;

        // Create custom form for editing overrides
        FormWindowCustom form = new FormWindowCustom("Edit Overrides - " + area.getName());
        
        // Add toggle buttons for each permission
        for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
            boolean currentValue = area.getGroupPermission(groupName, toggle.getPermissionNode());
            form.addElement(new cn.nukkit.form.element.ElementToggle(
                toggle.getDisplayName(), 
                currentValue
            ));
        }

        return form;
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        try {
            if (!player.hasPermission("adminarea.luckperms.edit")) {
                player.sendMessage(plugin.getLanguageManager().get("messages.noPermission"));
                return;
            }

            FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
            FormTrackingData groupData = plugin.getFormIdMap().get(player.getName() + "_group");

            if (areaData == null || groupData == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.form.invalidSession"));
                return;
            }

            Area area = plugin.getArea(areaData.getFormId());
            String groupName = groupData.getFormId();

            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                return;
            }

            // Process overrides
            Map<String, Boolean> overrides = new HashMap<>();
            int index = 0;
            for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
                overrides.put(toggle.getPermissionNode(), response.getToggleResponse(index++));
            }

            // Update overrides in database
            plugin.getOverrideManager().setGroupOverrides(groupName, overrides);
            
            player.sendMessage(plugin.getLanguageManager().get("messages.form.permissionOverridesUpdated"));
            
            plugin.getGuiManager().openLuckPermsGroupList(player, area);

        } catch (Exception e) {
            plugin.getLogger().error("Error processing override edit form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error"));
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        throw new UnsupportedOperationException("Override edit form does not use simple responses");
    }
}
