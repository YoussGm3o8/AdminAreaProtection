package adminarea.form;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.AdminAreaConstants;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowSimple;
import net.luckperms.api.model.group.Group;
import org.json.JSONObject;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
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

        Set<Group> groups = plugin.getLuckPermsApi().getGroupManager().getLoadedGroups();
        for (Group group : groups) {
            form.addButton(new ElementButton(group.getName()));
        }

        return form;
    }

    public FormWindowCustom createGroupPermissionForm(Area area, String groupName) {
        FormWindowCustom form = new FormWindowCustom("Edit " + groupName + " Permissions");

        // Add toggles for each permission
        for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
            boolean currentValue = area.getGroupPermission(groupName, toggle.getPermissionNode());
            form.addElement(new ElementToggle(toggle.getDisplayName(), currentValue));
        }

        return form;
    }

    public void saveGroupPermissions(Area area, String groupName, JSONObject permissions) {
        Map<String, Boolean> permissionMap = new HashMap<>();
        for (String key : permissions.keySet()) {
            permissionMap.put(key, permissions.getBoolean(key));
        }
        area.setGroupPermissions(groupName, permissionMap);
        plugin.updateArea(area);
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
