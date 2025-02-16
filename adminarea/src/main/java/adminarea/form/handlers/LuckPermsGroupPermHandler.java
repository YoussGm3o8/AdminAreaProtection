package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.AdminAreaConstants;
import adminarea.form.IFormHandler;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import org.json.JSONObject;

public class LuckPermsGroupPermHandler implements IFormHandler {
    private final AdminAreaProtectionPlugin plugin;

    public LuckPermsGroupPermHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getFormId() {
        return AdminAreaConstants.FORM_GROUP_PERMISSIONS;
    }

    @Override
    public FormWindow createForm(Player player) {
        String areaName = plugin.getFormIdMap().get(player.getName() + "_editing");
        String groupName = plugin.getFormIdMap().get(player.getName() + "_group");
        Area area = plugin.getArea(areaName);
        
        if (area == null || groupName == null) {
            return null;
        }

        FormWindowCustom form = new FormWindowCustom("Group Permissions: " + groupName);
        JSONObject groupPerms = area.getGroupPermissions()
            .optJSONObject(groupName, new JSONObject());

        // Add toggles for each group permission
        for (PermissionToggle toggle : PermissionToggle.getPlayerToggles()) {
            boolean current = groupPerms.optBoolean(toggle.getPermissionNode(), toggle.getDefaultValue());
            form.addElement(new ElementToggle(toggle.getDisplayName(), current));
        }

        return form;
    }

    @Override
    public void handleResponse(Player player, Object response) {
        if (!(response instanceof FormResponseCustom)) return;

        String areaName = plugin.getFormIdMap().get(player.getName() + "_editing");
        String groupName = plugin.getFormIdMap().get(player.getName() + "_group");
        Area area = plugin.getArea(areaName);
        
        if (area == null || groupName == null) {
            player.sendMessage(AdminAreaConstants.MSG_AREA_NOT_FOUND);
            return;
        }

        FormResponseCustom customResponse = (FormResponseCustom) response;
        JSONObject groupPerms = new JSONObject();
        
        // Process each toggle response
        PermissionToggle[] toggles = PermissionToggle.getPlayerToggles();
        for (int i = 0; i < toggles.length; i++) {
            groupPerms.put(toggles[i].getPermissionNode(), customResponse.getToggleResponse(i));
        }

        // Update group permissions
        JSONObject allGroupPerms = area.getGroupPermissions();
        allGroupPerms.put(groupName, groupPerms);
        area.setGroupPermissions(allGroupPerms);
        
        plugin.saveArea(area);
        player.sendMessage(String.format(AdminAreaConstants.MSG_GROUP_PERMS_UPDATED, groupName, area.getName()));
    }
}
