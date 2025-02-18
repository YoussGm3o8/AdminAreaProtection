package adminarea.form.handlers;

import java.util.Map;
import java.util.HashMap;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.AdminAreaConstants;
import adminarea.data.FormTrackingData;
import adminarea.form.IFormHandler;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;

public class LuckPermsGroupListHandler extends BaseFormHandler {
    private final AdminAreaProtectionPlugin plugin;

    /**
     * Creates a new LuckPermsGroupListHandler
     * @param plugin The AdminAreaProtectionPlugin instance
     */
    public LuckPermsGroupListHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        this.plugin = plugin;
    }

    @Override
    public String getFormId() {
        return AdminAreaConstants.FORM_GROUP_SELECT;
    }

    @Override
    public FormWindow createForm(Player player) {
        // Check view permission
        if (!player.hasPermission("adminarea.luckperms.view")) {
            plugin.getLogger().debug("Player lacks adminarea.luckperms.view permission");
            return null;
        }

        FormTrackingData formData = plugin.getFormIdMap().get(player.getName() + "_editing");
        String areaName = formData != null ? formData.getFormId() : null;
        Area area = plugin.getArea(areaName);
        
        if (area == null) {
            return null;
        }

        return plugin.getFormFactory().createGroupSelectionForm(area);
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        // Back button handling
        FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData != null) {
            Area area = plugin.getArea(areaData.getFormId());
            if (area != null) {
                plugin.getGuiManager().openLuckPermsGroupList(player, area);
            }
        }
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        if (!player.hasPermission("adminarea.luckperms.edit")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.noPermission"));
            return;
        }

        FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        FormTrackingData groupData = plugin.getFormIdMap().get(player.getName() + "_group");
        processGroupPermissions(player, response, areaData, groupData);
    }

    /**
     * Process group permissions from form response
     * @param player The player submitting the form
     * @param response The form response containing permission toggles
     * @param areaData The area tracking data
     * @param groupData The group tracking data
     */
    private void processGroupPermissions(Player player, FormResponseCustom response, 
            FormTrackingData areaData, FormTrackingData groupData) {
        try {
            // Validate tracking data
            if (areaData == null || groupData == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.form.invalidSession"));
                return;
            }

            // Get area and group
            Area area = plugin.getArea(areaData.getFormId());
            String groupName = groupData.getFormId();

            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                return;
            }

            // Store old permissions for event
            Map<String, Boolean> oldPerms = area.getGroupPermissions(groupName);
            Map<String, Boolean> newPerms = new HashMap<>();

            // Process permission toggles
            int index = 0;
            for (PermissionToggle toggle : PermissionToggle.getPlayerToggles()) {
                boolean value = response.getToggleResponse(index++);
                newPerms.put(toggle.getPermissionNode(), value);
            }

            // Update permissions
            area.setGroupPermissions(groupName, newPerms);
            
            // Fire event
            plugin.fireLuckPermsGroupChangeEvent(area, groupName, oldPerms, newPerms);

            // Save changes
            plugin.saveArea(area);
            
            // Show success message
            player.sendMessage(plugin.getLanguageManager().get("messages.form.groupPermissionsUpdated",
                Map.of(
                    "group", groupName,
                    "area", area.getName()
                )));

            // Return to group list
            plugin.getGuiManager().openLuckPermsGroupList(player, area);

        } catch (Exception e) {
            plugin.getLogger().error("Error processing group permissions", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.saveChanges"));
        }
    }

    @Override
    public void handleResponse(Player player, Object response) {
        // Check edit permission
        if (!player.hasPermission("adminarea.luckperms.edit")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.noPermission"));
            return;
        }

        if (!(response instanceof FormResponseSimple)) {
            return;
        }

        FormResponseSimple simpleResponse = (FormResponseSimple) response;
        FormTrackingData formData = plugin.getFormIdMap().get(player.getName() + "_editing");
        String areaName = formData != null ? formData.getFormId() : null;
        Area area = plugin.getArea(areaName);

        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
            return;
        }

        // Get selected group (accounting for "Back" button)
        int selectedButton = simpleResponse.getClickedButtonId();
        if (selectedButton >= plugin.getGroupNames().size()) {
            // Back button clicked
            plugin.getGuiManager().openAreaSettings(player, area);
            return;
        }

        // Get the selected group name
        String groupName = plugin.getGroupNames().toArray(new String[0])[selectedButton];
        
        if (groupName == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.form.groupNotFound", 
                Map.of("group", groupName)));
            return;
        }

        plugin.getFormIdMap().put(player.getName() + "_group", 
            new FormTrackingData(groupName, System.currentTimeMillis()));

        // Open the group permissions form
        FormWindow permForm = plugin.getFormFactory().createGroupPermissionForm(area, groupName);
        if (permForm != null) {
            plugin.getGuiManager().sendForm(player, permForm, AdminAreaConstants.FORM_GROUP_PERMISSIONS);
        } else {
            player.sendMessage(plugin.getLanguageManager().get("messages.form.errorCreatingForm"));
        }
    }
}
