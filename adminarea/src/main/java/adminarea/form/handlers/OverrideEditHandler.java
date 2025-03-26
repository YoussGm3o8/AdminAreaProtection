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
import cn.nukkit.form.element.ElementToggle;

import java.util.Map;
import java.util.HashMap;

public class OverrideEditHandler extends BaseFormHandler {

    public OverrideEditHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        validateFormId();
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
        
        // Add toggle for deleting permissions
        form.addElement(new ElementToggle(
            plugin.getLanguageManager().get("gui.permissions.deletePermissions", 
                Map.of("default", plugin.getLanguageManager().get("gui.labels.delete"))),
            false
        ));

        return form;
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        // Check for null response (form closed without submitting)
        if (response == null) {
            handleCancel(player);
            return;
        }
        
        try {
            if (!player.hasPermission("adminarea.luckperms.edit")) {
                player.sendMessage(plugin.getLanguageManager().get("messages.noPermission"));
                handleCancel(player);
                return;
            }

            FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
            FormTrackingData groupData = plugin.getFormIdMap().get(player.getName() + "_group");

            if (areaData == null || groupData == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.form.invalidSession"));
                handleCancel(player);
                return;
            }

            Area area = plugin.getArea(areaData.getFormId());
            String groupName = groupData.getFormId();

            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                handleCancel(player);
                return;
            }

            // Get current DTO
            AreaDTO currentDTO = area.toDTO();
            
            // Check if we should delete permissions (last toggle)
            boolean shouldDeletePermissions = response.getToggleResponse(PermissionToggle.getDefaultToggles().size());
            
            if (shouldDeletePermissions) {
                // Create updated permissions map with the group removed
                Map<String, Map<String, Boolean>> updatedGroupPerms = new HashMap<>(currentDTO.groupPermissions());
                updatedGroupPerms.remove(groupName);
                
                // Create updated area using builder
                AreaBuilder areaBuilder = AreaBuilder.fromDTO(currentDTO);
                areaBuilder.groupPermissions(updatedGroupPerms);
                Area updatedArea = areaBuilder.build();
                
                try {
                    if (plugin.isDebugMode()) {
                        plugin.debug("Deleting group permissions for group " + groupName + " in area " + area.getName());
                    }
                    
                    // Remove permissions from database
                    plugin.getPermissionOverrideManager().setGroupPermissions(
                        area.getName(),
                        groupName,
                        new HashMap<>() // Empty map effectively removes permissions
                    );
                    
                    // Save area to database
                    plugin.getDatabaseManager().saveArea(updatedArea);
                    
                    // Update in plugin
                    plugin.updateArea(updatedArea);
                    
                    player.sendMessage(plugin.getLanguageManager().get("messages.permissions.groupPermissionsDeleted",
                        Map.of("group", groupName, "area", area.getName())));
                } catch (Exception e) {
                    plugin.getLogger().error("Failed to delete group permissions", e);
                    throw e;
                }
            } else {
                // Process overrides
                Map<String, Boolean> overrides = new HashMap<>();
                int index = 0;
                for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
                    Boolean value = response.getToggleResponse(index);
                    overrides.put(toggle.getPermissionNode(), value != null ? value : false);
                    index++;
                }

                // Create updated permissions map
                Map<String, Map<String, Boolean>> updatedGroupPerms = new HashMap<>(currentDTO.groupPermissions());
                updatedGroupPerms.put(groupName, overrides);

                // Create updated area using builder - FIXED: Use safer AreaBuilder approach
                AreaBuilder areaBuilder = AreaBuilder.fromDTO(currentDTO);
                areaBuilder.groupPermissions(updatedGroupPerms);
                // We don't need to explicitly set player or track permissions as they're already part of the DTO
                Area updatedArea = areaBuilder.build();

                // Directly save to permission database first
                try {
                    if (plugin.isDebugMode()) {
                        plugin.debug("Explicitly saving group permissions for " + groupName + " in area " + area.getName());
                        plugin.debug("Permission count: " + overrides.size());
                    }
                    
                    // Save directly to the permission database using PermissionOverrideManager
                    plugin.getPermissionOverrideManager().setGroupPermissions(area.getName(), groupName, overrides);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Successfully saved group permissions to database");
                        
                        // Verify permissions were saved
                        Map<String, Boolean> verifyPerms = plugin.getPermissionOverrideManager().getGroupPermissions(
                            area.getName(), groupName);
                        plugin.debug("Verification - retrieved permissions: " + 
                                   (verifyPerms != null ? verifyPerms.size() : "null") + " permissions");
                    }
                    
                    // Force flush permissions to disk
                    plugin.getPermissionOverrideManager().forceFlushPermissions();
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Forced permissions to be flushed to disk");
                    }
                    
                    // Force clear all area caches
                    plugin.getAreaManager().invalidateAreaCache(area.getName());
                    plugin.getPermissionOverrideManager().invalidateCache(area.getName());
                } catch (Exception e) {
                    plugin.getLogger().error("Failed to explicitly save group permissions", e);
                    if (plugin.isDebugMode()) {
                        e.printStackTrace();
                    }
                }

                // Update area in plugin
                plugin.updateArea(updatedArea);
                
                player.sendMessage(plugin.getLanguageManager().get("messages.form.permissionOverridesUpdated"));
            }
            
            // Clean up form tracking data before opening the next form
            plugin.getFormIdMap().remove(player.getName() + "_group");
            
            plugin.getGuiManager().openLuckPermsGroupList(player, area);

        } catch (Exception e) {
            plugin.getLogger().error("Error processing override edit form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error"));
            handleCancel(player);
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        handleCancel(player);
    }
    
    @Override
    public void handleCancel(Player player) {
        // Clean up form data
        FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        plugin.getFormIdMap().remove(player.getName() + "_group");
        
        // If we have a valid area, go back to the group list
        if (areaData != null) {
            Area area = plugin.getArea(areaData.getFormId());
            if (area != null) {
                plugin.getGuiManager().openLuckPermsGroupList(player, area);
                return;
            }
        }
        
        // If we don't have a valid area, perform standard cleanup
        cleanup(player);
    }
}
