package adminarea.form;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.area.AreaDTO;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowSimple;
import org.json.JSONObject;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.HashMap;

/**
 * Handles multi-step form management for LuckPerms permission overrides.
 * Supports track selection, group management, and permission configuration.
 */
public class LuckPermsOverrideForm {
    private static final int CACHE_DURATION_MINUTES = 30;
    private final AdminAreaProtectionPlugin plugin;
    private final ConcurrentHashMap<String, FormState> formStates;
    private final ConcurrentHashMap<String, PermissionCacheEntry> permissionCache;

    public LuckPermsOverrideForm(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.formStates = new ConcurrentHashMap<>();
        this.permissionCache = new ConcurrentHashMap<>();
    }

    public FormWindowSimple createGroupSelectionForm(Area area) {
        FormWindowSimple form = new FormWindowSimple(
            "Select Group - " + area.getName(),
            "Choose a group to edit permissions for:"
        );

        try {
            // Use reflection to safely access the group manager
            Object luckPermsApi = plugin.getLuckPermsApi();
            if (luckPermsApi != null) {
                Object groupManager = luckPermsApi.getClass().getMethod("getGroupManager").invoke(luckPermsApi);
                if (groupManager != null) {
                    // Cast the result to the appropriate collection type
                    Set<?> groups = (Set<?>) groupManager.getClass().getMethod("getLoadedGroups").invoke(groupManager);
                    if (groups != null) {
                        for (Object group : groups) {
                            String groupName = (String) group.getClass().getMethod("getName").invoke(group);
                            form.addButton(new ElementButton(groupName));
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error accessing LuckPerms groups", e);
            form.addButton(new ElementButton("No groups available"));
        }

        return form;
    }

    public FormWindowCustom createGroupPermissionForm(Area area, String groupName) {
        FormWindowCustom form = new FormWindowCustom("Edit " + groupName + " Permissions");

        // Get current DTO
        AreaDTO currentDTO = area.toDTO();
        Map<String, Map<String, Boolean>> groupPerms = currentDTO.groupPermissions();
        Map<String, Boolean> currentPerms = groupPerms.getOrDefault(groupName, new HashMap<>());

        // Add toggles for each permission
        for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
            boolean currentValue = currentPerms.getOrDefault(toggle.getPermissionNode(), false);
            form.addElement(new ElementToggle(toggle.getDisplayName(), currentValue));
        }

        return form;
    }

    public void saveGroupPermissions(Area area, String groupName, JSONObject permissions) {
        // Get current DTO
        AreaDTO currentDTO = area.toDTO();

        // Convert permissions to map
        Map<String, Boolean> permissionMap = new HashMap<>();
        for (String key : permissions.keySet()) {
            permissionMap.put(key, permissions.getBoolean(key));
        }

        // Update group permissions
        Map<String, Map<String, Boolean>> updatedGroupPerms = new HashMap<>(currentDTO.groupPermissions());
        updatedGroupPerms.put(groupName, permissionMap);

        // Create updated area using builder
        Area updatedArea = AreaBuilder.fromDTO(currentDTO)
            .groupPermissions(updatedGroupPerms)
            .build();

        plugin.updateArea(updatedArea);
    }

    // Add new cleanup method to handle resource cleanup
    public void cleanup() {
        long now = System.currentTimeMillis();
        
        // Clean up expired form states
        formStates.entrySet().removeIf(entry -> 
            now - entry.getValue().startTime() > 300000);
        
        // Clean up expired cache entries
        permissionCache.entrySet().removeIf(entry -> 
            now - entry.getValue().timestamp() > CACHE_DURATION_MINUTES * 60000);
    }
}
