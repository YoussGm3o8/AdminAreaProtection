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
    private String enterTitle;
    private String leaveTitle;
    private Map<String, Boolean> permissions;
    private Map<String, Map<String, Boolean>> trackPermissions;
    private Map<String, Map<String, Boolean>> playerPermissions;
    private JSONObject potionEffects;
    private boolean isGlobal = false;
    private AdminAreaProtectionPlugin plugin;

    public AreaBuilder() {
        this.settings = new JSONObject();
        this.groupPermissions = new HashMap<>(8, 1.0f);
        this.inheritedPermissions = new HashMap<>(4, 1.0f);
        this.toggleStates = new JSONObject();
        this.defaultToggleStates = new JSONObject();
        this.inheritedToggleStates = new JSONObject();
        this.permissions = new HashMap<>(8, 1.0f);
        this.priority = 0;
        this.showTitle = true;
        this.potionEffects = new JSONObject();
        initializeDefaultPermissions();
        this.trackPermissions = new HashMap<>(4, 1.0f);
        this.playerPermissions = new HashMap<>(8, 1.0f);
        this.plugin = AdminAreaProtectionPlugin.getInstance();
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
        this.xMin = xMin;
        this.xMax = xMax;
        this.yMin = yMin;
        this.yMax = yMax;
        this.zMin = zMin;
        this.zMax = zMax;
        
        // Auto-detect if this is a global area
        this.isGlobal = xMin <= -29000000 && xMax >= 29000000 &&
                       zMin <= -29000000 && zMax >= 29000000;
        
        return this;
    }

    public AreaBuilder global(String world) {
        this.world = world;
        this.isGlobal = true;
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
        
        // Sync toggle states with settings for "allow*" permissions
        if (settings != null) {
            for (String key : settings.keySet()) {
                if (key.startsWith("allow") || key.startsWith("gui.permissions.toggles.allow")) {
                    // Normalize key to have the prefix
                    String normalizedKey = key;
                    if (!key.startsWith("gui.permissions.toggles.")) {
                        normalizedKey = "gui.permissions.toggles." + key;
                    }
                    
                    // Update toggle states to match settings
                    if (toggleStates == null) {
                        toggleStates = new JSONObject();
                    }
                    
                    // Handle strength settings (which are integers) differently from toggle settings (which are booleans)
                    if (key.endsWith("Strength") || normalizedKey.endsWith("Strength")) {
                        // This is a strength setting, store it as an integer
                        toggleStates.put(normalizedKey, settings.optInt(key, 0));
                    } else {
                        // This is a regular toggle setting, store it as a boolean
                        toggleStates.put(normalizedKey, settings.optBoolean(key, false));
                    }
                }
            }
        }
        
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

    // Title messages
    public AreaBuilder enterTitle(String title) {
        this.enterTitle = title;
        return this;
    }

    public AreaBuilder leaveTitle(String title) {
        this.leaveTitle = title;
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

    public AreaBuilder trackPermissions(Map<String, Map<String, Boolean>> trackPermissions) {
        this.trackPermissions = new HashMap<>(trackPermissions);
        return this;
    }

    /**
     * Set the player-specific permissions for this area.
     * Each entry maps a player name to their permission map.
     * 
     * @param playerPermissions A map of player names to their permission maps
     * @return this builder
     */
    public AreaBuilder playerPermissions(Map<String, Map<String, Boolean>> playerPermissions) {
        if (playerPermissions == null) {
            this.playerPermissions = new HashMap<>(4, 1.0f);
            if (plugin.isDebugMode()) {
                plugin.debug("Player permissions set to empty map (null input)");
            }
        } else {
            this.playerPermissions = new HashMap<>(playerPermissions);
            if (plugin.isDebugMode()) {
                plugin.debug("Player permissions set to: " + this.playerPermissions);
            }
        }
        return this;
    }
    
    /**
     * Add permissions for a specific player
     * 
     * @param playerName The player's name
     * @param permissions The permission map
     * @return this builder
     */
    public AreaBuilder addPlayerPermissions(String playerName, Map<String, Boolean> permissions) {
        if (playerName == null || permissions == null) {
            return this;
        }
        
        if (this.playerPermissions == null) {
            this.playerPermissions = new HashMap<>(4, 1.0f);
        }
        
        this.playerPermissions.put(playerName, new HashMap<>(permissions));
        
        if (plugin.isDebugMode()) {
            plugin.debug("Added player permissions for " + playerName + ": " + permissions);
            plugin.debug("Updated player permissions map: " + this.playerPermissions);
        }
        
        return this;
    }

    public AreaBuilder potionEffects(JSONObject effects) {
        this.potionEffects = effects;
        return this;
    }

    public Area build() {
        if (name == null || world == null) {
            throw new IllegalStateException("Area name and world must be set");
        }

        // Create bounds record - use global bounds if this is a global area
        AreaDTO.Bounds bounds = isGlobal ? 
            AreaDTO.Bounds.createGlobal() : 
            new AreaDTO.Bounds(xMin, xMax, yMin, yMax, zMin, zMax);

        // Create permissions record
        AreaDTO.Permissions permissionsRecord = AreaDTO.Permissions.fromMap(permissions);

        // For global areas, optimize memory usage
        if (isGlobal) {
            // Use smaller maps for global areas since they typically have fewer permissions
            groupPermissions = new HashMap<>(4, 1.0f);
            trackPermissions = new HashMap<>(2, 1.0f);
            playerPermissions = new HashMap<>(4, 1.0f);
        }

        // Convert toggle states to JSONObject
        if (toggleStates == null) {
            toggleStates = new JSONObject();
        }
        
        // Ensure all settings are included in toggleStates
        if (settings != null) {
            for (String key : settings.keySet()) {
                if (key.startsWith("allow")) {
                    // Normalize key to have the prefix
                    String normalizedKey = key;
                    if (!key.startsWith("gui.permissions.toggles.")) {
                        normalizedKey = "gui.permissions.toggles." + key;
                    }
                    
                    // Handle strength settings (which are integers) differently from toggle settings (which are booleans)
                    if (key.endsWith("Strength") || normalizedKey.endsWith("Strength")) {
                        // This is a strength setting, store it as an integer
                        toggleStates.put(normalizedKey, settings.optInt(key, 0));
                    } else {
                        // This is a regular toggle setting, store it as a boolean
                        toggleStates.put(normalizedKey, settings.optBoolean(key, false));
                    }
                }
            }
        }

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
            toggleStates,  // Use the populated toggleStates
            defaultToggleStates,
            inheritedToggleStates,
            permissionsRecord,
            enterMessage != null ? enterMessage : "",
            leaveMessage != null ? leaveMessage : "",
            enterTitle != null ? enterTitle : "",
            leaveTitle != null ? leaveTitle : "",
            trackPermissions,
            playerPermissions,
            potionEffects
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
              .leaveMessage(dto.leaveMessage())
              .enterTitle(dto.enterTitle())
              .leaveTitle(dto.leaveTitle())
              .trackPermissions(dto.trackPermissions())
              .playerPermissions(dto.playerPermissions())
              .potionEffects(dto.potionEffects());

        // Set permissions from the DTO's permission record
        builder.setPermission("allowBlockBreak", dto.permissions().allowBlockBreak());
        builder.setPermission("allowBlockPlace", dto.permissions().allowBlockPlace());
        builder.setPermission("allowInteract", dto.permissions().allowInteract());
        builder.setPermission("allowPvP", dto.permissions().allowPvP());
        builder.setPermission("allowChorusFruit", dto.permissions().allowChorusFruit());

        return builder;
    }
}
