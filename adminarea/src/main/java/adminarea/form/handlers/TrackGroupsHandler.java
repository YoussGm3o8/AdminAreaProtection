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
import io.micrometer.core.instrument.Timer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TrackGroupsHandler extends BaseFormHandler {

    public TrackGroupsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getFormId() {
        return FormIds.TRACK_GROUPS;
    }

    @Override
    public FormWindow createForm(Player player) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
            FormTrackingData trackData = plugin.getFormIdMap().get(player.getName() + "_track");
            
            if (areaData == null || trackData == null) return null;

            Area area = plugin.getArea(areaData.getFormId());
            String trackName = trackData.getFormId();
            
            if (area == null || trackName == null) return null;

            return plugin.getFormFactory().createTrackGroupsForm(trackName);
            
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "create_track_groups_form");
        }
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Validate permissions
            if (!player.hasPermission("adminarea.luckperms.edit")) {
                player.sendMessage(plugin.getLanguageManager().get("messages.noPermission"));
                return;
            }

            FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
            FormTrackingData trackData = plugin.getFormIdMap().get(player.getName() + "_track");
            
            if (areaData == null || trackData == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.form.invalidSession"));
                return;
            }

            Area area = plugin.getArea(areaData.getFormId());
            String trackName = trackData.getFormId();

            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                return;
            }

            // Process track group permissions 
            List<String> groups = plugin.getLuckPermsCache().getTrackGroups(trackName).stream().toList();
            Map<String, Map<String, Boolean>> groupPerms = new HashMap<>();

            // Get permissions for each group
            for (int i = 0; i < groups.size(); i++) {
                String groupName = groups.get(i);
                Map<String, Boolean> permissions = new HashMap<>();
                
                for (int j = 0; j < PermissionToggle.getDefaultToggles().size(); j++) {
                    PermissionToggle toggle = PermissionToggle.getDefaultToggles().get(j);
                    permissions.put(toggle.getPermissionNode(), 
                        response.getToggleResponse(i * PermissionToggle.getDefaultToggles().size() + j));
                }
                
                groupPerms.put(groupName, permissions);
            }

            // Apply permissions to each group
            for (Map.Entry<String, Map<String, Boolean>> entry : groupPerms.entrySet()) {
                area.setGroupPermissions(entry.getKey(), entry.getValue());
            }

            // Save changes
            plugin.saveArea(area);

            player.sendMessage(plugin.getLanguageManager().get("messages.form.trackPermissionsApplied"));
            plugin.getGuiManager().openAreaSettings(player, area);

        } catch (Exception e) {
            plugin.getLogger().error("Error processing track groups form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error"));
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "handle_track_groups_response"); 
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        // Track groups only uses custom forms
        throw new UnsupportedOperationException("Track groups form does not use simple responses");
    }
}
