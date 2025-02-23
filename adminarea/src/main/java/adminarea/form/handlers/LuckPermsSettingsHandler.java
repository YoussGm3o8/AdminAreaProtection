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
import cn.nukkit.form.element.ElementDropdown;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowSimple;

import java.util.*;

public class LuckPermsSettingsHandler extends BaseFormHandler {
    private static final String TRACK_DATA_KEY = "_track";
    private static final String GROUP_DATA_KEY = "_group";

    public LuckPermsSettingsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        validateFormId();
    }

    @Override
    public String getFormId() {
        return FormIds.LUCKPERMS_SETTINGS;
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

        // Check if we're selecting a track, group, or editing permissions
        FormTrackingData trackData = plugin.getFormIdMap().get(player.getName() + TRACK_DATA_KEY);
        FormTrackingData groupData = plugin.getFormIdMap().get(player.getName() + GROUP_DATA_KEY);

        if (trackData == null) {
            // Show track selection form
            FormWindowSimple form = new FormWindowSimple(
                "Select Track - " + area.getName(),
                "Choose a track to edit permissions for, or select Global Track Settings:"
            );

            form.addButton(new ElementButton("Global Track Settings"));
            
            plugin.getLuckPermsApi().getTrackManager().getLoadedTracks().forEach(track -> {
                form.addButton(new ElementButton(track.getName()));
            });

            return form;
        } else if (groupData == null) {
            // Show group selection form for the selected track
            String trackName = trackData.getFormId();
            
            if ("global".equals(trackName)) {
                return createGlobalTrackSettingsForm(area);
            }
            
            FormWindowSimple form = new FormWindowSimple(
                "Select Group - " + trackName,
                "Choose a group from the track to edit permissions for:"
            );

            List<String> trackGroups = plugin.getGroupsByTrack(trackName);
            for (String group : trackGroups) {
                form.addButton(new ElementButton(group));
            }

            return form;
        } else {
            // Show permission editing form for selected group
            return createGroupPermissionForm(area, groupData.getFormId());
        }
    }

    private FormWindowCustom createGlobalTrackSettingsForm(Area area) {
        FormWindowCustom form = new FormWindowCustom("Global Track Settings - " + area.getName());
        
        // Add toggles for relevant permissions
        Map<String, Boolean> currentPerms = area.getTrackPermissions("global");
        
        for (PermissionToggle toggle : PermissionToggle.getPlayerToggles()) {
            boolean currentValue = currentPerms.getOrDefault(toggle.getPermissionNode(), area.getToggleState(toggle.getPermissionNode()));
            form.addElement(new ElementToggle(toggle.getDisplayName(), currentValue));
        }

        return form;
    }

    private FormWindowCustom createGroupPermissionForm(Area area, String groupName) {
        FormWindowCustom form = new FormWindowCustom("Edit " + groupName + " Permissions");

        Map<String, Boolean> currentPerms = area.getGroupPermissions(groupName);
        
        // Add toggles for player-relevant permissions
        for (PermissionToggle toggle : PermissionToggle.getPlayerToggles()) {
            boolean currentValue = currentPerms.getOrDefault(toggle.getPermissionNode(), area.getToggleState(toggle.getPermissionNode()));
            form.addElement(new ElementToggle(toggle.getDisplayName(), currentValue));
        }

        return form;
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

        FormTrackingData trackData = plugin.getFormIdMap().get(player.getName() + TRACK_DATA_KEY);
        
        if (trackData == null) {
            // Handle track selection
            String selectedTrack = response.getClickedButtonId() == 0 ? 
                "global" : 
                plugin.getLuckPermsApi().getTrackManager().getLoadedTracks()
                    .stream()
                    .skip(response.getClickedButtonId() - 1)
                    .findFirst()
                    .map(track -> track.getName())
                    .orElse(null);

            if (selectedTrack == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidTrack"));
                return;
            }

            plugin.getFormIdMap().put(
                player.getName() + TRACK_DATA_KEY,
                new FormTrackingData(selectedTrack, System.currentTimeMillis())
            );
        } else {
            // Handle group selection
            String trackName = trackData.getFormId();
            List<String> trackGroups = plugin.getGroupsByTrack(trackName);
            
            if (response.getClickedButtonId() >= 0 && response.getClickedButtonId() < trackGroups.size()) {
                String selectedGroup = trackGroups.get(response.getClickedButtonId());
                plugin.getFormIdMap().put(
                    player.getName() + GROUP_DATA_KEY,
                    new FormTrackingData(selectedGroup, System.currentTimeMillis())
                );
            } else {
                player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidGroup"));
                return;
            }
        }

        plugin.getGuiManager().openFormById(player, FormIds.LUCKPERMS_SETTINGS, area);
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        if (response == null) {
            handleCancel(player);
            return;
        }

        try {
            FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
            FormTrackingData trackData = plugin.getFormIdMap().get(player.getName() + TRACK_DATA_KEY);
            FormTrackingData groupData = plugin.getFormIdMap().get(player.getName() + GROUP_DATA_KEY);

            if (areaData == null || trackData == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.form.invalidSession"));
                return;
            }

            Area area = plugin.getArea(areaData.getFormId());
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                return;
            }

            // Get current DTO
            AreaDTO currentDTO = area.toDTO();
            
            // Process permissions
            Map<String, Boolean> newPerms = new HashMap<>();
            int index = 0;
            for (PermissionToggle toggle : PermissionToggle.getPlayerToggles()) {
                Boolean value = response.getToggleResponse(index);
                newPerms.put(toggle.getPermissionNode(), value != null ? value : false);
                index++;
            }

            Area updatedArea;
            if ("global".equals(trackData.getFormId())) {
                // Update global track permissions
                Map<String, Map<String, Boolean>> trackPerms = new HashMap<>(currentDTO.trackPermissions());
                trackPerms.put("global", newPerms);
                
                updatedArea = AreaBuilder.fromDTO(currentDTO)
                    .trackPermissions(trackPerms)
                    .build();
                    
                player.sendMessage(plugin.getLanguageManager().get("messages.form.trackPermissionsApplied"));
            } else if (groupData != null) {
                // Update specific group permissions
                String groupName = groupData.getFormId();
                Map<String, Map<String, Boolean>> groupPerms = new HashMap<>(currentDTO.groupPermissions());
                Map<String, Boolean> oldPerms = groupPerms.getOrDefault(groupName, new HashMap<>());
                groupPerms.put(groupName, newPerms);
                
                updatedArea = AreaBuilder.fromDTO(currentDTO)
                    .groupPermissions(groupPerms)
                    .build();

                // Fire event
                LuckPermsGroupChangeEvent event = new LuckPermsGroupChangeEvent(area, groupName, oldPerms, newPerms);
                plugin.getServer().getPluginManager().callEvent(event);

                if (event.isCancelled()) {
                    return;
                }
                
                player.sendMessage(plugin.getLanguageManager().get("messages.form.groupPermissionsUpdated",
                    Map.of("group", groupName, "area", area.getName())));
            } else {
                player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
                return;
            }

            // Update area in plugin
            plugin.updateArea(updatedArea);

            // Clear selection data and return to edit menu
            plugin.getFormIdMap().remove(player.getName() + TRACK_DATA_KEY);
            plugin.getFormIdMap().remove(player.getName() + GROUP_DATA_KEY);
            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, updatedArea);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling LuckPerms settings response", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            handleCancel(player);
        }
    }

    @Override
    public void handleCancel(Player player) {
        plugin.getFormIdMap().remove(player.getName() + TRACK_DATA_KEY);
        plugin.getFormIdMap().remove(player.getName() + GROUP_DATA_KEY);
        plugin.getGuiManager().openMainMenu(player);
    }
} 