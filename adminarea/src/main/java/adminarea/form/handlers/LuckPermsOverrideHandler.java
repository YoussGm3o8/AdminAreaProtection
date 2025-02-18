package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.form.IFormHandler;
import adminarea.constants.AdminAreaConstants;
import adminarea.data.FormTrackingData;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import net.luckperms.api.track.Track;

import java.util.HashMap;
import java.util.Map;

import org.json.JSONObject;

public class LuckPermsOverrideHandler extends BaseFormHandler {
    public LuckPermsOverrideHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getFormId() {
        return AdminAreaConstants.FORM_OVERRIDE_TRACK;
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        if (player == null || response == null) {
            return;
        }

        // Add validation first
        if (!plugin.isLuckPermsEnabled()) {
            if (player != null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.luckpermsUnavailable"));
            }
            return;
        }

        try {
            FormTrackingData formData = plugin.getFormIdMap().get(player.getName() + "_editing");
            
            if (formData == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.form.invalidSession"));
                return;
            }

            Area area = plugin.getArea(formData.getFormId());
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                return;
            }

            handle(player, response, area);
        } catch (Exception e) {
            plugin.getLogger().error("Error handling LuckPerms form response", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error"));
        }
    }

    @Override 
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        // This handler doesn't use simple forms
        throw new UnsupportedOperationException("This handler only supports custom forms");
    }

    private void handle(Player player, FormResponseCustom response, Area area) {
        if (plugin.getLuckPermsApi() == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.luckpermsUnavailable"));
            return;
        }

        FormTrackingData formData = plugin.getFormIdMap().get(player.getName() + "_editing");
        String areaName = formData != null ? formData.getFormId() : null;
        Area areaN = plugin.getArea(areaName);
        
        if (areaN == null) return;

        FormTrackingData currentForm = plugin.getFormIdMap().get(player.getName());
        String formId = currentForm != null ? currentForm.getFormId() : null;
        switch (formId) {
            case AdminAreaConstants.FORM_OVERRIDE_TRACK:
                handleTrackSelection(player, response, area);
                break;
            case AdminAreaConstants.FORM_OVERRIDE_GROUP:
                handleGroupSelection(player, response, area);
                break;
            case AdminAreaConstants.FORM_OVERRIDE_EDIT:
                handleOverrideEdit(player, response, area);
                break;
        }
    }

    private void handleTrackSelection(Player player, FormResponseCustom response, Area area) {
        String trackName = response.getDropdownResponse(0).getElementContent();
        Track track = plugin.getLuckPermsApi().getTrackManager().getTrack(trackName);
        
        if (track == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.form.luckpermsNotFound"));
            return;
        }

        for (String groupName : track.getGroups()) {
            area.setGroupPermission(groupName, "access", true);
        }
        
        plugin.saveArea(area);
        player.sendMessage(plugin.getLanguageManager().get("messages.form.trackPermissionsApplied"));
    }

    private void handleGroupSelection(Player player, FormResponseCustom response, Area area) {
        String groupName = response.getDropdownResponse(0).getElementContent();
        boolean hasAccess = response.getToggleResponse(1);
        
        area.setGroupPermission(groupName, "access", hasAccess);
        plugin.saveArea(area);
        
        player.sendMessage(plugin.getLanguageManager().get("messages.form.groupPermissionsUpdated"));
    }

    private void handleOverrideEdit(Player player, FormResponseCustom response, Area area) {
        int index = 0;
        for (String groupName : plugin.getGroupNames()) {
            if (response.getToggleResponse(index)) {
                Map<String, Boolean> groupPerms = new HashMap<>();
                groupPerms.put("build", response.getToggleResponse(index + 1));
                groupPerms.put("break", response.getToggleResponse(index + 2));
                groupPerms.put("interact", response.getToggleResponse(index + 3));
                area.setGroupPermissions(groupName, groupPerms);
            }
            index += 4;
        }
        
        plugin.saveArea(area);
        player.sendMessage(plugin.getLanguageManager().get("messages.form.permissionOverridesUpdated"));
    }
}
