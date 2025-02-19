package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.area.AreaDTO;
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
        
        // Get current DTO
        AreaDTO currentDTO = area.toDTO();

        // Get current group permissions
        Map<String, Boolean> currentPerms = currentDTO.groupPermissions()
            .getOrDefault(groupName, new HashMap<>());

        // Add toggle buttons for each permission
        for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
            boolean currentValue = currentPerms.getOrDefault(toggle.getPermissionNode(), false);
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

            // Get current DTO
            AreaDTO currentDTO = area.toDTO();

            // Process overrides
            Map<String, Boolean> overrides = new HashMap<>();
            int index = 0;
            for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
                overrides.put(toggle.getPermissionNode(), response.getToggleResponse(index++));
            }

            // Create updated permissions map
            Map<String, Map<String, Boolean>> updatedGroupPerms = new HashMap<>(currentDTO.groupPermissions());
            updatedGroupPerms.put(groupName, overrides);

            // Create updated area using builder
            Area updatedArea = AreaBuilder.fromDTO(currentDTO)
                .groupPermissions(updatedGroupPerms)
                .build();

            // Update area in plugin
            plugin.updateArea(updatedArea);
            
            player.sendMessage(plugin.getLanguageManager().get("messages.form.permissionOverridesUpdated"));
            
            plugin.getGuiManager().openLuckPermsGroupList(player, updatedArea);

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
