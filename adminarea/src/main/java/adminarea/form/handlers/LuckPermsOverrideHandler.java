package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.form.IFormHandler;
import adminarea.constants.AdminAreaConstants;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.window.FormWindow;
import net.luckperms.api.track.Track;
import org.json.JSONObject;

public class LuckPermsOverrideHandler implements IFormHandler {
    private final AdminAreaProtectionPlugin plugin;

    public LuckPermsOverrideHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getFormId() {
        return AdminAreaConstants.FORM_OVERRIDE_TRACK;
    }

    @Override
    public FormWindow createForm(Player player) {
        // This method should be implemented based on your form creation needs
        return null;
    }

    @Override
    public void handleResponse(Player player, Object response) {
        if (response instanceof FormResponseCustom) {
            handle(player, (FormResponseCustom) response);
        }
    }

    private void handle(Player player, FormResponseCustom response) {
        if (plugin.getLuckPermsApi() == null) {
            player.sendMessage("§cLuckPerms is not available");
            return;
        }

        String areaName = plugin.getFormIdMap().get(player.getName() + "_editing");
        Area area = plugin.getArea(areaName);
        
        if (area == null) return;

        String formId = plugin.getFormIdMap().get(player.getName());
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
            player.sendMessage("§cTrack not found!");
            return;
        }

        for (String groupName : track.getGroups()) {
            area.setGroupPermission(groupName, "access", true);
        }
        
        plugin.saveArea(area);
        player.sendMessage("§aTrack permissions applied successfully!");
    }

    private void handleGroupSelection(Player player, FormResponseCustom response, Area area) {
        String groupName = response.getDropdownResponse(0).getElementContent();
        boolean hasAccess = response.getToggleResponse(1);
        
        area.setGroupPermission(groupName, "access", hasAccess);
        plugin.saveArea(area);
        
        player.sendMessage("§aGroup permissions updated successfully!");
    }

    private void handleOverrideEdit(Player player, FormResponseCustom response, Area area) {
        JSONObject permissions = area.getGroupPermissions();
        
        int index = 0;
        for (String groupName : plugin.getGroupNames()) {
            if (response.getToggleResponse(index)) {
                permissions.put(groupName, new JSONObject()
                    .put("build", response.getToggleResponse(index + 1))
                    .put("break", response.getToggleResponse(index + 2))
                    .put("interact", response.getToggleResponse(index + 3)));
            }
            index += 4;
        }
        
        area.setGroupPermissions(permissions);
        plugin.saveArea(area);
        player.sendMessage("§aPermission overrides updated successfully!");
    }
}
