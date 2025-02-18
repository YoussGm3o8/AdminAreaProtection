package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.event.PermissionChangeEvent;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import io.micrometer.core.instrument.Timer;

import java.util.Map;
import java.util.HashMap;

public class GroupPermissionHandler extends BaseFormHandler {

    public GroupPermissionHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getFormId() {
        return FormIds.GROUP_PERMISSIONS;
    }

    @Override
    public FormWindow createForm(Player player) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
            FormTrackingData groupData = plugin.getFormIdMap().get(player.getName() + "_group");

            if (areaData == null || groupData == null) {
                return null;
            }

            Area area = plugin.getArea(areaData.getFormId());
            String groupName = groupData.getFormId();

            if (area == null || groupName == null) {
                return null;
            }

            return plugin.getFormFactory().createGroupPermissionForm(area, groupName);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "create_group_permission_form");
        }
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Verify permissions
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

            // Store old permissions for comparison/event
            Map<String, Boolean> oldPerms = new HashMap<>(area.getGroupPermissions(groupName));
            Map<String, Boolean> newPerms = new HashMap<>();

            // Process each toggle response
            int toggleIndex = 0;
            for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
                String permission = toggle.getPermissionNode();
                boolean value = response.getToggleResponse(toggleIndex++);
                newPerms.put(permission, value);

                // Fire individual permission change events
                Boolean oldValue = oldPerms.get(permission);
                if (oldValue == null || oldValue != value) {
                    PermissionChangeEvent event = new PermissionChangeEvent(
                        area, groupName, permission, 
                        oldValue != null ? oldValue : false, 
                        value
                    );
                    plugin.getServer().getPluginManager().callEvent(event);
                    
                    if (event.isCancelled()) {
                        player.sendMessage(plugin.getLanguageManager().get("messages.form.permissionChangeDenied"));
                        return;
                    }
                }
            }

            // Update permissions
            area.setGroupPermissions(groupName, newPerms);
            plugin.getDatabaseManager().saveArea(area);

            // Fire group change event
            plugin.fireLuckPermsGroupChangeEvent(area, groupName, oldPerms, newPerms);

            // Send success message
            player.sendMessage(plugin.getLanguageManager().get("messages.form.groupPermissionsUpdated",
                Map.of(
                    "group", groupName,
                    "area", area.getName()
                )));

            // Return to group list
            plugin.getGuiManager().openLuckPermsGroupList(player, area);

        } catch (Exception e) {
            plugin.getLogger().error("Error processing group permissions form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error"));
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "handle_group_permission_response");
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        // Only handles custom forms
        throw new UnsupportedOperationException("Group permission forms do not use simple responses");
    }
}
