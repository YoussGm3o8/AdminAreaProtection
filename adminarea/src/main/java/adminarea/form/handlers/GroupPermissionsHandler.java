package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.area.AreaDTO;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.event.LuckPermsGroupChangeEvent;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowSimple;
import net.luckperms.api.model.group.Group;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class GroupPermissionsHandler extends BaseFormHandler {

    public GroupPermissionsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getFormId() {
        return FormIds.GROUP_PERMISSIONS;
    }

    @Override
    public FormWindow createForm(Player player) {
        FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.noAreaSelected"));
            return null;
        }

        Area area = plugin.getArea(areaData.getFormId());
        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.areaNotFound"));
            return null;
        }

        // Check if we're selecting a group or editing permissions
        FormTrackingData groupData = plugin.getFormIdMap().get(player.getName() + "_group");
        if (groupData == null) {
            // Show group selection form
            FormWindowSimple form = new FormWindowSimple(
                "Select Group - " + area.getName(),
                "Choose a group to edit permissions for:"
            );

            Set<Group> groups = plugin.getLuckPermsApi().getGroupManager().getLoadedGroups();
            for (Group group : groups) {
                form.addButton(new ElementButton(group.getName()));
            }

            return form;
        } else {
            // Show permission editing form for selected group
            String groupName = groupData.getFormId();
            return createGroupPermissionForm(area, groupName);
        }
    }

    private FormWindow createGroupPermissionForm(Area area, String groupName) {
        FormWindowCustom form = new FormWindowCustom("Edit " + groupName + " Permissions - " + area.getName());

        // Get current DTO
        AreaDTO currentDTO = area.toDTO();
        Map<String, Map<String, Boolean>> groupPerms = currentDTO.groupPermissions();
        Map<String, Boolean> currentPerms = groupPerms.getOrDefault(groupName, new HashMap<>());

        // Add toggles for each permission
        for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
            String description = plugin.getLanguageManager().get(
                "gui.permissions.toggles." + toggle.getPermissionNode());
            form.addElement(new ElementToggle(
                toggle.getDisplayName() + "\n§7" + description,
                currentPerms.getOrDefault(toggle.getPermissionNode(), false)
            ));
        }

        return form;
    }

    @Override
    public FormWindow createForm(Player player, Area area) {
        if (area == null) return null;
        return createForm(player);
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        if (response == null) {
            handleCancel(player);
            return;
        }

        FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) return;

        Area area = plugin.getArea(areaData.getFormId());
        if (area == null) return;

        // Get selected group
        String groupName = plugin.getLuckPermsApi().getGroupManager().getLoadedGroups()
            .stream()
            .skip(response.getClickedButtonId())
            .findFirst()
            .map(Group::getName)
            .orElse(null);

        if (groupName == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidGroup"));
            return;
        }

        // Store selected group and show permission form
        plugin.getFormIdMap().put(player.getName() + "_group",
            new FormTrackingData(groupName, System.currentTimeMillis()));
        plugin.getGuiManager().openFormById(player, FormIds.GROUP_PERMISSIONS, area);
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        if (response == null) {
            handleCancel(player);
            return;
        }

        try {
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

            // Get current group permissions
            Map<String, Map<String, Boolean>> currentGroupPerms = currentDTO.groupPermissions();
            Map<String, Boolean> oldPerms = currentGroupPerms.getOrDefault(groupName, new HashMap<>());

            // Process permissions
            Map<String, Boolean> newPerms = new HashMap<>();
            int index = 0;
            for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
                Boolean value = response.getToggleResponse(index);
                newPerms.put(toggle.getPermissionNode(), value != null ? value : false);
                index++;
            }

            // Create updated permissions map
            Map<String, Map<String, Boolean>> updatedGroupPerms = new HashMap<>(currentGroupPerms);
            updatedGroupPerms.put(groupName, newPerms);

            // Create updated area using builder
            Area updatedArea = AreaBuilder.fromDTO(currentDTO)
                .groupPermissions(updatedGroupPerms)
                .build();

            // Fire event
            LuckPermsGroupChangeEvent event = new LuckPermsGroupChangeEvent(area, groupName, oldPerms, newPerms);
            plugin.getServer().getPluginManager().callEvent(event);

            if (!event.isCancelled()) {
                // Update area in plugin
                plugin.updateArea(updatedArea);
                player.sendMessage(plugin.getLanguageManager().get("messages.permissions.groupUpdated",
                    Map.of("group", groupName, "area", area.getName())));
            }

            // Clear group selection and return to edit menu
            plugin.getFormIdMap().remove(player.getName() + "_group");
            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, updatedArea);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling group permissions response", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            handleCancel(player);
        }
    }

    @Override
    public void handleCancel(Player player) {
        plugin.getFormIdMap().remove(player.getName() + "_group");
        plugin.getGuiManager().openMainMenu(player);
    }
} 