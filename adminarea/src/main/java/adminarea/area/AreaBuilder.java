package adminarea.area;

import org.json.JSONObject;

import adminarea.AdminAreaProtectionPlugin;

import java.util.HashMap;
import java.util.Map;

public class AreaBuilder {
    private String name;
    private String world;
    private int xMin, xMax, yMin, yMax, zMin, zMax;
    private int priority;
    private boolean showTitle;
    private JSONObject settings;
    private Map<String, Map<String, Boolean>> groupPermissions;
    private Map<String, Map<String, Boolean>> inheritedPermissions;
    private JSONObject toggleStates;
    private JSONObject defaultToggleStates;
    private JSONObject inheritedToggleStates;
    private String enterMessage;
    private String leaveMessage;
    private Map<String, Boolean> permissions;

    public AreaBuilder() {
        this.settings = new JSONObject();
        this.groupPermissions = new HashMap<>();
        this.inheritedPermissions = new HashMap<>();
        this.toggleStates = new JSONObject();
        this.defaultToggleStates = new JSONObject();
        this.inheritedToggleStates = new JSONObject();
        this.permissions = new HashMap<>();
        this.priority = 0;
        this.showTitle = true;
        initializeDefaultPermissions();
    }

    private void initializeDefaultPermissions() {
        permissions.put("allowBlockBreak", false);
        permissions.put("allowBlockPlace", false);
        permissions.put("allowInteract", false);
        permissions.put("allowPvP", false);
        permissions.put("allowChorusFruit", false);
        // Add any other default permissions
    }

    // Basic setters
    public AreaBuilder name(String name) {
        this.name = name;
        return this;
    }

    public AreaBuilder world(String world) {
        this.world = world;
        return this;
    }

    public AreaBuilder coordinates(int xMin, int xMax, int yMin, int yMax, int zMin, int zMax) {
        this.xMin = Math.min(xMin, xMax);
        this.xMax = Math.max(xMin, xMax);
        this.yMin = Math.min(yMin, yMax);
        this.yMax = Math.max(yMin, yMax);
        this.zMin = Math.min(zMin, zMax);
        this.zMax = Math.max(zMin, zMax);
        return this;
    }

    public AreaBuilder priority(int priority) {
        this.priority = priority;
        return this;
    }

    public AreaBuilder showTitle(boolean showTitle) {
        this.showTitle = showTitle;
        return this;
    }

    // Settings and states
    public AreaBuilder settings(JSONObject settings) {
        this.settings = settings;
        return this;
    }

    public AreaBuilder groupPermissions(Map<String, Map<String, Boolean>> permissions) {
        this.groupPermissions = permissions;
        return this;
    }

    public AreaBuilder inheritedPermissions(Map<String, Map<String, Boolean>> permissions) {
        this.inheritedPermissions = permissions;
        return this;
    }

    public AreaBuilder toggleStates(JSONObject states) {
        this.toggleStates = states;
        return this;
    }

    public AreaBuilder defaultToggleStates(JSONObject states) {
        this.defaultToggleStates = states;
        return this;
    }

    public AreaBuilder inheritedToggleStates(JSONObject states) {
        this.inheritedToggleStates = states;
        return this;
    }

    // Messages
    public AreaBuilder enterMessage(String message) {
        this.enterMessage = message;
        return this;
    }

    public AreaBuilder leaveMessage(String message) {
        this.leaveMessage = message;
        return this;
    }

    // Permission setters
    public AreaBuilder setPermission(String permission, boolean value) {
        permissions.put(permission, value);
        return this;
    }

    public AreaBuilder applyTemplate(String templateName) {
        AdminAreaProtectionPlugin plugin = AdminAreaProtectionPlugin.getInstance();
        JSONObject template = plugin.getConfigManager().getPermissionTemplate(templateName);
        
        if (template != null) {
            template.keySet().forEach(key -> 
                setPermission(key, template.getBoolean(key)));
        }
        return this;
    }

    public AreaBuilder applyDefaults() {
        AdminAreaProtectionPlugin plugin = AdminAreaProtectionPlugin.getInstance();
        JSONObject defaults = plugin.getConfigManager().getDefaultPermissions();
        
        defaults.keySet().forEach(key -> 
            setPermission(key, defaults.getBoolean(key)));
        return this;
    }

    public Area build() {
        if (name == null || world == null) {
            throw new IllegalStateException("Area name and world must be set");
        }

        // Create bounds record
        AreaDTO.Bounds bounds = new AreaDTO.Bounds(xMin, xMax, yMin, yMax, zMin, zMax);

        // Create permissions record
        AreaDTO.Permissions permissionsRecord = AreaDTO.Permissions.fromMap(permissions);

        // Create DTO
        AreaDTO dto = new AreaDTO(
            name,
            world,
            bounds,
            priority,
            showTitle,
            settings,
            groupPermissions,
            inheritedPermissions,
            toggleStates,
            defaultToggleStates,
            inheritedToggleStates,
            permissionsRecord,
            enterMessage != null ? enterMessage : "",
            leaveMessage != null ? leaveMessage : ""
        );

        // Create Area instance
        return new Area(dto);
    }

    public static AreaBuilder fromDTO(AreaDTO dto) {
        AreaBuilder builder = new AreaBuilder();
        builder.name(dto.name())
              .world(dto.world())
              .coordinates(
                  dto.bounds().xMin(),
                  dto.bounds().xMax(),
                  dto.bounds().yMin(),
                  dto.bounds().yMax(),
                  dto.bounds().zMin(),
                  dto.bounds().zMax()
              )
              .priority(dto.priority())
              .showTitle(dto.showTitle())
              .settings(dto.settings())
              .groupPermissions(dto.groupPermissions())
              .inheritedPermissions(dto.inheritedPermissions())
              .toggleStates(dto.toggleStates())
              .defaultToggleStates(dto.defaultToggleStates())
              .inheritedToggleStates(dto.inheritedToggleStates())
              .enterMessage(dto.enterMessage())
              .leaveMessage(dto.leaveMessage());

        // Set permissions from the DTO's permission record
        builder.setPermission("allowBlockBreak", dto.permissions().allowBlockBreak());
        builder.setPermission("allowBlockPlace", dto.permissions().allowBlockPlace());
        builder.setPermission("allowInteract", dto.permissions().allowInteract());
        builder.setPermission("allowPvP", dto.permissions().allowPvP());
        builder.setPermission("allowChorusFruit", dto.permissions().allowChorusFruit());

        return builder;
    }
}
