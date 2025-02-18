package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.AdminAreaConstants;
import adminarea.data.FormTrackingData;
import adminarea.event.LuckPermsGroupChangeEvent;
import adminarea.form.IFormHandler;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;

import java.util.Map;
import java.util.HashMap;
import java.util.List;

import org.json.JSONObject;

public class LuckPermsGroupPermHandler extends BaseFormHandler {
    public LuckPermsGroupPermHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getFormId() {
        return AdminAreaConstants.FORM_GROUP_PERMISSIONS;
    }

    @Override
    public FormWindow createForm(Player player) {
        // Handle null player case
        if (player == null) {
            return null;
        }

        // Check view permission
        if (!player.hasPermission("adminarea.luckperms.view")) {
            plugin.getLogger().debug("Player lacks adminarea.luckperms.view permission");
            return null;
        }

        FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        FormTrackingData groupData = plugin.getFormIdMap().get(player.getName() + "_group");
        
        String areaName = areaData != null ? areaData.getFormId() : null;
        String groupName = groupData != null ? groupData.getFormId() : null;
        
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
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        if (!player.hasPermission("adminarea.luckperms.edit")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.noPermission"));
            return;
        }

        // Store form data
        plugin.getFormIdMap().put(player.getName(), 
            new FormTrackingData(AdminAreaConstants.FORM_GROUP_PERMISSIONS, System.currentTimeMillis()));

        FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        FormTrackingData groupData = plugin.getFormIdMap().get(player.getName() + "_group");
        processGroupPermissions(player, response, areaData, groupData);
    }

    @Override 
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        throw new UnsupportedOperationException("This handler only supports custom forms");
    }

    private void processGroupPermissions(Player player, FormResponseCustom response, 
            FormTrackingData areaData, FormTrackingData groupData) {
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

        // Process inheritance chain and permissions
        Map<String, Boolean> currentPerms = processInheritanceChain(groupName);
        processDirectPermissions(response, currentPerms);
        savePermissions(player, area, groupName, currentPerms);
    }

    private Map<String, Boolean> processInheritanceChain(String groupName) {
        Map<String, Boolean> perms = new HashMap<>();
        
        // Get inheritance chain from LuckPerms cache
        List<String> chain = plugin.getLuckPermsCache().getInheritanceChain(groupName);
        
        // Process permissions in order from highest parent to lowest
        for (String parent : chain) {
            // Get parent group permissions and merge
            Map<String, Boolean> parentPerms = plugin.getOverrideManager().getGroupPermissions(parent);
            perms.putAll(parentPerms);
        }
        
        return perms;
    }

    private void processDirectPermissions(FormResponseCustom response, Map<String, Boolean> currentPerms) {
        PermissionToggle[] toggles = PermissionToggle.getPlayerToggles();
        // Skip the header label element
        for (int i = 0; i < toggles.length; i++) {
            boolean value = response.getToggleResponse(i);
            currentPerms.put(toggles[i].getPermissionNode(), value);
        }
    }

    private void savePermissions(Player player, Area area, String groupName, Map<String, Boolean> permissions) {
        try {
            // Convert permissions map to JSON for storage
            JSONObject permsJson = new JSONObject();
            permissions.forEach(permsJson::put);
            
            // Update area group permissions
            area.getGroupPermissions().put(groupName, permsJson);
            
            // Save changes to database
            plugin.getDatabaseManager().saveArea(area);
            
            // Call event
            plugin.getServer().getPluginManager().callEvent(new LuckPermsGroupChangeEvent(
                area,
                groupName,
                plugin.getOverrideManager().getGroupPermissions(groupName), // old perms
                permissions // new perms
            ));
            
            // Send success message
            player.sendMessage(plugin.getLanguageManager().get("messages.form.groupPermissionsUpdated", 
                Map.of(
                    "group", groupName,
                    "area", area.getName()
                )));
                
            // Return to area settings
            plugin.getGuiManager().openAreaSettings(player, area);
            
        } catch (Exception e) {
            plugin.getLogger().error("Error saving group permissions", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.saveChanges"));
        }
    }

    @Override
    public void handleResponse(Player player, Object response) {
        // Handle null inputs
        if (player == null || response == null) {
            plugin.debug("Null player or response in LuckPermsGroupPermHandler");
            return;
        }

        try {
            if (response instanceof FormResponseCustom) {
                handleCustomResponse(player, (FormResponseCustom) response);
            } else {
                plugin.debug("Invalid response type received: " + response.getClass().getName());
                throw new UnsupportedOperationException("This handler only supports custom forms");
            }
        } catch (Exception e) {
            handleError(player, e);
        }
    }

    protected void handleError(Player player, Exception e) {
            plugin.getLogger().error("Error in LuckPerms group permission handler", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.generic", 
                Map.of("error", e.getMessage())));
        }
}
