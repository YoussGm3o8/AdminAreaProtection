package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.area.AreaDTO;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.event.LuckPermsTrackChangeEvent;
import adminarea.form.handlers.BaseFormHandler;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementDropdown;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowSimple;
import net.luckperms.api.track.Track;

import java.util.*;
import java.util.stream.Collectors;

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
            // Show track selection form with detailed descriptions
            FormWindowCustom form = new FormWindowCustom(
                plugin.getLanguageManager().get("gui.luckperms.title.main")
            );

            // Add informative label at top of form
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.luckperms.header.tracks")));
            
            // Get all available tracks plus a "Custom" option
            List<String> trackOptions = new ArrayList<>();
            trackOptions.add(plugin.getLanguageManager().get("gui.luckperms.labels.selectTrack")); // Default option
            
            if (plugin.isLuckPermsEnabled()) {
                List<String> trackNames = plugin.getLuckPermsApi().getTrackManager().getLoadedTracks().stream()
                    .map(Track::getName)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
                
                if (trackNames.isEmpty()) {
                    form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.luckperms.labels.noTracks")));
                    return form;
                }
                
                trackOptions.addAll(trackNames);
            } else {
                player.sendMessage(plugin.getLanguageManager().get("gui.luckperms.messages.error.noLuckPerms"));
                return null;
            }
            
            // Add dropdown for track selection
            form.addElement(new ElementDropdown(
                plugin.getLanguageManager().get("gui.luckperms.labels.selectTrack"),
                trackOptions
            ));
            
            // Show existing track permissions for reference
            StringBuilder trackInfo = new StringBuilder(plugin.getLanguageManager().get("gui.luckperms.header.permissions") + "\n");
            Map<String, Map<String, Boolean>> trackPerms = area.getTrackPermissions();
            
            if (trackPerms.isEmpty()) {
                trackInfo.append(plugin.getLanguageManager().get("gui.luckperms.messages.noPermissionsSelected"));
            } else {
                for (Map.Entry<String, Map<String, Boolean>> entry : trackPerms.entrySet()) {
                    trackInfo.append("\n§f").append(entry.getKey()).append("§7: ");
                    Map<String, Boolean> perms = entry.getValue();
                    if (perms.isEmpty()) {
                        trackInfo.append(plugin.getLanguageManager().get("gui.luckperms.messages.noPermissionsSelected"));
                    } else {
                        trackInfo.append(perms.size()).append(" permissions configured");
                    }
                }
            }
            
            form.addElement(new ElementLabel(trackInfo.toString()));
            
            return form;
        } else {
            // Show permission editing form for selected track
            return createTrackPermissionForm(area, trackData.getFormId());
        }
    }

    private FormWindowCustom createTrackPermissionForm(Area area, String trackName) {
        FormWindowCustom form = new FormWindowCustom(
            plugin.getLanguageManager().get("gui.luckperms.title.track", Map.of("track", trackName))
        );
        
        // Add track info at top of form
        List<String> groups = plugin.getGroupsByTrack(trackName);
        StringBuilder groupList = new StringBuilder();
        for (String group : groups) {
            groupList.append("\n- §f").append(group);
        }
        
        form.addElement(new ElementLabel(
            plugin.getLanguageManager().get("gui.luckperms.header.permissions", 
                Map.of("target", trackName, "groups", groupList.toString()))
        ));

        // Add group selection dropdown
        List<String> groupOptions = new ArrayList<>();
        groupOptions.add(plugin.getLanguageManager().get("gui.luckperms.labels.allGroups")); // Option to apply to all groups
        groupOptions.addAll(groups); // Add individual groups
        form.addElement(new ElementDropdown(
            plugin.getLanguageManager().get("gui.luckperms.labels.selectGroups"),
            groupOptions
        ));
        
        // Add toggles for relevant permissions
        Map<String, Boolean> currentPerms = area.getTrackPermissions().getOrDefault(trackName, new HashMap<>());
        
        for (PermissionToggle toggle : PermissionToggle.getPlayerToggles()) {
            boolean currentValue = currentPerms.getOrDefault(toggle.getPermissionNode(), area.getToggleState(toggle.getPermissionNode()));
            form.addElement(new ElementToggle(toggle.getDisplayName(), currentValue));
        }

        return form;
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

            if (areaData == null) {
                player.sendMessage(plugin.getLanguageManager().get("gui.luckperms.messages.error.invalidSession"));
                return;
            }

            Area area = plugin.getArea(areaData.getFormId());
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.error.areaNotFound"));
                return;
            }

            // Get current DTO
            AreaDTO currentDTO = area.toDTO();
            
            // If we're selecting a track (no track data yet)
            if (trackData == null) {
                int selectedIndex = response.getDropdownResponse(1).getElementID();
                if (selectedIndex == 0) {
                    // User didn't select a valid track
                    player.sendMessage(plugin.getLanguageManager().get("gui.luckperms.messages.noTrackSelected"));
                    return;
                }
                
                List<String> trackNames = new ArrayList<>();
                if (plugin.isLuckPermsEnabled()) {
                    trackNames = plugin.getLuckPermsApi().getTrackManager().getLoadedTracks().stream()
                        .map(Track::getName)
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .collect(Collectors.toList());
                }
                
                if (selectedIndex - 1 < trackNames.size()) {
                    String selectedTrack = trackNames.get(selectedIndex - 1);
                    plugin.getFormIdMap().put(
                        player.getName() + TRACK_DATA_KEY,
                        new FormTrackingData(selectedTrack, System.currentTimeMillis())
                    );
                    plugin.getGuiManager().openFormById(player, FormIds.LUCKPERMS_SETTINGS, area);
                } else {
                    player.sendMessage(plugin.getLanguageManager().get("gui.luckperms.messages.error.invalidTrack"));
                }
                return;
            }
            
            // Process permissions for track
            Map<String, Boolean> newPerms = new HashMap<>();
            int startIndex = 2; // Skip header and dropdown
            
            for (int i = 0; i < PermissionToggle.getPlayerToggles().length; i++) {
                PermissionToggle toggle = PermissionToggle.getPlayerToggles()[i];
                Boolean value = response.getToggleResponse(startIndex + i);
                newPerms.put(toggle.getPermissionNode(), value != null ? value : false);
            }

            // Update track permissions
            String trackName = trackData.getFormId();
            Map<String, Map<String, Boolean>> trackPerms = new HashMap<>(currentDTO.trackPermissions());
            Map<String, Boolean> oldPerms = trackPerms.getOrDefault(trackName, new HashMap<>());
            trackPerms.put(trackName, newPerms);
            
            // Create updated area
            Area updatedArea = AreaBuilder.fromDTO(currentDTO)
                .trackPermissions(trackPerms)
                .build();
            
            // Get selected group option
            int selectedGroupIndex = response.getDropdownResponse(1).getElementID();
            List<String> trackGroups = plugin.getGroupsByTrack(trackName);
            List<String> groupsToUpdate = new ArrayList<>();
            
            if (selectedGroupIndex == 0) {
                // "All Groups" selected - update all groups in track
                groupsToUpdate.addAll(trackGroups);
            } else if (selectedGroupIndex <= trackGroups.size()) {
                // Single group selected
                groupsToUpdate.add(trackGroups.get(selectedGroupIndex - 1));
            }
            
            // Update group permissions
            if (!groupsToUpdate.isEmpty()) {
                Map<String, Map<String, Boolean>> groupPerms = new HashMap<>(currentDTO.groupPermissions());
                
                for (String group : groupsToUpdate) {
                    Map<String, Boolean> currentGroupPerms = groupPerms.getOrDefault(group, new HashMap<>());
                    Map<String, Boolean> mergedPerms = new HashMap<>(newPerms);
                    
                    // Add any existing group permissions that aren't in the track permissions
                    for (Map.Entry<String, Boolean> entry : currentGroupPerms.entrySet()) {
                        if (!mergedPerms.containsKey(entry.getKey())) {
                            mergedPerms.put(entry.getKey(), entry.getValue());
                        }
                    }
                    
                    groupPerms.put(group, mergedPerms);
                }
                
                // Update the area with merged group permissions
                updatedArea = AreaBuilder.fromDTO(updatedArea.toDTO())
                    .groupPermissions(groupPerms)
                    .build();
            }
            
            // Fire event
            LuckPermsTrackChangeEvent event = new LuckPermsTrackChangeEvent(
                area, trackName, "track_permission_update", oldPerms.getOrDefault("adminarea.enter", false), newPerms.getOrDefault("adminarea.enter", false));
            plugin.getServer().getPluginManager().callEvent(event);
            
            // IMPORTANT: Explicitly save track permissions to the permission database first
            try {
                plugin.getPermissionOverrideManager().setTrackPermissions(
                    updatedArea.getName(), 
                    trackName, 
                    newPerms
                );
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Explicitly saved track permissions for " + trackName);
                }
                
                // If we're updating group permissions too, save those explicitly
                if (!groupsToUpdate.isEmpty()) {
                    Map<String, Map<String, Boolean>> groupPerms = updatedArea.getGroupPermissions();
                    for (String group : groupsToUpdate) {
                        Map<String, Boolean> groupPermMap = groupPerms.get(group);
                        if (groupPermMap != null) {
                            plugin.getPermissionOverrideManager().setGroupPermissions(
                                updatedArea.getName(),
                                group,
                                groupPermMap
                            );
                            
                            if (plugin.isDebugMode()) {
                                plugin.debug("Explicitly saved group permissions for " + group + " from track update");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().error("Failed to explicitly save track/group permissions", e);
                throw e;
            }
            
            // Save area to database
            try {
                plugin.getDatabaseManager().saveArea(updatedArea);
                
                // Force synchronize permissions to ensure they're saved
                plugin.getPermissionOverrideManager().synchronizeFromArea(updatedArea);
            } catch (Exception e) {
                plugin.getLogger().error("Failed to save area to database", e);
                throw e;
            }
            
            // Update in plugin
            plugin.updateArea(updatedArea);
            
            // Notify player of updated permissions
            if (groupsToUpdate.size() > 1) {
                player.sendMessage(plugin.getLanguageManager().get("gui.luckperms.messages.trackUpdated",
                    Map.of(
                        "track", trackName,
                        "area", area.getName(),
                        "count", String.valueOf(groupsToUpdate.size())
                    )
                ));
            } else if (groupsToUpdate.size() == 1) {
                player.sendMessage(plugin.getLanguageManager().get("gui.luckperms.messages.groupUpdated",
                    Map.of(
                        "group", groupsToUpdate.get(0),
                        "area", area.getName()
                    )
                ));
            }

            // Clear selection data and return to edit menu
            plugin.getFormIdMap().remove(player.getName() + TRACK_DATA_KEY);
            plugin.getFormIdMap().remove(player.getName() + GROUP_DATA_KEY);
            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, updatedArea);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling LuckPerms settings response", e);
            player.sendMessage(plugin.getLanguageManager().get("gui.luckperms.messages.error.updateFailed",
                Map.of("error", e.getMessage())));
            handleCancel(player);
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        throw new UnsupportedOperationException("LuckPerms settings form does not use simple responses");
    }

    @Override
    public void handleCancel(Player player) {
        plugin.getFormIdMap().remove(player.getName() + TRACK_DATA_KEY);
        plugin.getFormIdMap().remove(player.getName() + GROUP_DATA_KEY);
        plugin.getGuiManager().openMainMenu(player);
    }
} 