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

            // Create updated area using builder - FIXED: Ensure we preserve all permission types
            AreaBuilder areaBuilder = AreaBuilder.fromDTO(currentDTO);
            areaBuilder.groupPermissions(updatedGroupPerms);
            // We don't need to explicitly set player or track permissions as they're already part of the DTO
            // and will be preserved by the fromDTO() method
            Area updatedArea = areaBuilder.build();

            // Fire event
            LuckPermsGroupChangeEvent event = new LuckPermsGroupChangeEvent(area, groupName, oldPerms, newPerms);
            plugin.getServer().getPluginManager().callEvent(event);

            if (!event.isCancelled()) {
                // IMPORTANT: Explicitly save group permissions to the permission database first
                try {
                    if (plugin.isDebugMode()) {
                        plugin.debug("Explicitly saving group permissions for group " + groupName + " in area " + updatedArea.getName());
                        plugin.debug("  Permission count: " + newPerms.size());
                        plugin.debug("  Permissions: " + newPerms);
                    }
                    
                    plugin.getPermissionOverrideManager().setGroupPermissions(
                        updatedArea.getName(), 
                        groupName, 
                        newPerms
                    );
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Successfully saved group permissions to PermissionOverrideManager");
                        
                        // Verify permissions were saved
                        Map<String, Boolean> verifyPerms = plugin.getPermissionOverrideManager().getGroupPermissions(
                            updatedArea.getName(), groupName);
                        plugin.debug("  Verification - retrieved permissions: " + 
                                    (verifyPerms != null ? verifyPerms.size() : "null") + " permissions");
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Failed to explicitly save group permissions", e);
                    throw e;
                }
                
                // Save area to database
                try {
                    if (plugin.isDebugMode()) {
                        plugin.debug("Saving updated area to database: " + updatedArea.getName());
                    }
                    
                    plugin.getDatabaseManager().saveArea(updatedArea);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Area saved successfully");
                    }
                    
                    // Force synchronize permissions to ensure they're saved
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Now synchronizing all permissions from area to database");
                    }
                    
                    plugin.getPermissionOverrideManager().synchronizeFromArea(updatedArea);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Synchronization complete");
                        
                        // Verify area was updated correctly
                        Area verifyArea = plugin.getArea(updatedArea.getName());
                        if (verifyArea != null) {
                            Map<String, Map<String, Boolean>> groupPerms = verifyArea.getGroupPermissions();
                            plugin.debug("  Verification - area group permissions: " + 
                                        (groupPerms != null ? groupPerms.size() : "null") + " groups");
                            
                            if (groupPerms != null && groupPerms.containsKey(groupName)) {
                                plugin.debug("  Verification - permissions for " + groupName + ": " + 
                                           groupPerms.get(groupName).size() + " permissions");
                            } else {
                                plugin.debug("  Verification - group " + groupName + " not found in permissions");
                            }
                        } else {
                            plugin.debug("  Verification - area not found: " + updatedArea.getName());
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Failed to save area to database", e);
                    throw e;
                }
                
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
        cleanup(player);
    }
} 