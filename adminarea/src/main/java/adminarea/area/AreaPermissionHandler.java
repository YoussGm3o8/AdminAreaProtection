package adminarea.area;

import java.util.Map;
import java.util.List;
import adminarea.AdminAreaProtectionPlugin;

public class AreaPermissionHandler {
    private final Map<String, Map<String, Boolean>> groupPermissions;
    private final Map<String, Map<String, Boolean>> inheritedPermissions;

    public AreaPermissionHandler(
        Map<String, Map<String, Boolean>> groupPermissions,
        Map<String, Map<String, Boolean>> inheritedPermissions
    ) {
        this.groupPermissions = groupPermissions;
        this.inheritedPermissions = inheritedPermissions;
    }

    public boolean calculateEffectivePermission(String group, String permission) {
        // Check direct permissions first
        Map<String, Boolean> directPerms = groupPermissions.get(group);
        if (directPerms != null && directPerms.containsKey(permission)) {
            return directPerms.get(permission);
        }

        // Get inheritance chain and check inherited permissions
        List<String> inheritance = AdminAreaProtectionPlugin.getInstance()
            .getLuckPermsCache()
            .getInheritanceChain(group);

        for (String inheritedGroup : inheritance) {
            if (!inheritedGroup.equals(group)) {
                Map<String, Boolean> inheritedPerms = groupPermissions.get(inheritedGroup);
                if (inheritedPerms != null && inheritedPerms.containsKey(permission)) {
                    return inheritedPerms.get(permission);
                }
            }
        }

        return true; // Default to allowed
    }
}
