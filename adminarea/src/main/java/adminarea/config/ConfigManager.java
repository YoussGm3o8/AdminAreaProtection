package adminarea.config;

import adminarea.AdminAreaProtectionPlugin;
import cn.nukkit.utils.Config;
import cn.nukkit.utils.ConfigSection;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import org.json.JSONObject;

public class ConfigManager {
    private final AdminAreaProtectionPlugin plugin;
    private Config config;
    private final Map<String, Object> defaults;

    public ConfigManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.defaults = new HashMap<>();
        initializeDefaults();
    }

    private void initializeDefaults() {
        defaults.put("enableMessages", true);
        defaults.put("allowRegularAreaCreation", false);
        defaults.put("debug", false);
        defaults.put("maxAreaPriority", 100);
        defaults.put("wandItemType", 280); // Stick by default
        defaults.put("particleVisualization", true);
        defaults.put("visualizationDuration", 10); // seconds
        defaults.put("cacheExpiry", 5); // minutes
        defaults.put("undoHistorySize", 10);
        defaults.put("selectionCooldown", 250); // milliseconds
        
        // Messages section
        defaults.put("messages.blockBreak", "§cYou cannot break blocks in {area}.");
        defaults.put("messages.blockPlace", "§cYou cannot place blocks in {area}.");
        defaults.put("messages.pvp", "§cPVP is disabled in {area}.");
        defaults.put("messages.interact", "§cYou cannot interact with that in {area}.");
        defaults.put("messages.container", "§cYou cannot access containers in {area}.");
        defaults.put("messages.command", "§cYou cannot use that command in {area}.");
        defaults.put("messages.noPermission", "§cYou don't have permission for that.");
        defaults.put("messages.areaCreated", "§aArea {area} created successfully.");
        defaults.put("messages.areaDeleted", "§aArea {area} deleted successfully.");
        defaults.put("messages.areaUpdated", "§aArea {area} updated successfully.");
        defaults.put("messages.selectionCleared", "§aSelection points cleared.");
        defaults.put("messages.selectionComplete", "§aBoth positions set! Use /area create to create your area.");
        defaults.put("messages.wandGiven", "§eYou have received the Area Wand!");
        defaults.put("messages.bypassEnabled", "§aBypass mode enabled.");
        defaults.put("messages.bypassDisabled", "§aBypass mode disabled.");

        // Title configurations with default fadeIn=20, stay=40, fadeOut=20
        Map<String, Object> titleConfig = new HashMap<>();
        
        Map<String, Object> enterTitle = new HashMap<>();
        enterTitle.put("main", "§aEntering {area}");
        enterTitle.put("subtitle", "Welcome to {area}!");
        enterTitle.put("fadeIn", 20);
        enterTitle.put("stay", 40);
        enterTitle.put("fadeOut", 20);
        titleConfig.put("enter", enterTitle);
        
        Map<String, Object> leaveTitle = new HashMap<>();
        leaveTitle.put("main", "§eLeaving {area}");
        leaveTitle.put("subtitle", "Goodbye from {area}!");
        leaveTitle.put("fadeIn", 20);
        leaveTitle.put("stay", 40);
        leaveTitle.put("fadeOut", 20);
        titleConfig.put("leave", leaveTitle);
        
        defaults.put("title", titleConfig);

        // LuckPerms integration settings
        defaults.put("luckperms.enabled", true);
        defaults.put("luckperms.inheritPermissions", true);
        defaults.put("luckperms.updateInterval", 300); // seconds

        // Area merging settings
        defaults.put("areaSettings.useMostRestrictiveMerge", true);
        defaults.put("areaSettings.description.mergeBehavior", 
            "When true, overlapping areas use AND logic (most restrictive). " +
            "When false, uses OR logic (least restrictive).");

        // Area priority determines which area's settings take precedence when areas overlap
        // Higher priority (0-100) overrides lower priority areas in the same space
        defaults.put("maxAreaPriority", 100);
        defaults.put("areaSettings.description.priority",
            "Priority (0-100) determines which area's settings apply when areas overlap. " +
            "Higher priority areas override lower priority areas in the same space. " +
            "Example: A priority 50 area will override a priority 25 area's settings.");

        // Default permissions configuration
        Map<String, Object> defaultPerms = new HashMap<>();
        defaultPerms.put("allowBuild", false);
        defaultPerms.put("allowBreak", false);
        defaultPerms.put("allowInteract", false);
        defaultPerms.put("allowContainer", false);
        defaultPerms.put("allowItemFrame", false);
        defaultPerms.put("allowPvP", false);
        defaultPerms.put("allowMobSpawn", true);
        defaultPerms.put("allowRedstone", true);
        
        defaults.put("defaultPermissions", defaultPerms);

        // Permission templates
        Map<String, Object> templates = new HashMap<>();
        
        // PvP arena template
        Map<String, Object> pvpTemplate = new HashMap<>();
        pvpTemplate.put("allowPvP", true);
        pvpTemplate.put("allowBuild", false);
        pvpTemplate.put("allowBreak", false);
        pvpTemplate.put("allowMobSpawn", false);
        templates.put("pvp_arena", pvpTemplate);

        // Creative building template  
        Map<String, Object> creativeTemplate = new HashMap<>();
        creativeTemplate.put("allowBuild", true);
        creativeTemplate.put("allowBreak", true);
        creativeTemplate.put("allowInteract", true);
        creativeTemplate.put("allowContainer", true);
        templates.put("creative_zone", creativeTemplate);

        // Safe spawn template
        Map<String, Object> spawnTemplate = new HashMap<>();
        spawnTemplate.put("allowBuild", false);
        spawnTemplate.put("allowBreak", false);
        spawnTemplate.put("allowPvP", false);
        spawnTemplate.put("allowMobSpawn", false);
        templates.put("safe_spawn", spawnTemplate);

        defaults.put("permissionTemplates", templates);

        // Template descriptions
        Map<String, String> templateDescs = new HashMap<>();
        templateDescs.put("pvp_arena", "PvP enabled, building disabled");
        templateDescs.put("creative_zone", "Full building permissions");
        templateDescs.put("safe_spawn", "Safe spawn area with no PvP/mobs");
        defaults.put("templateDescriptions", templateDescs);
    }

    public void load() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }

        config = new Config(configFile, Config.YAML);
        
        // Convert YAML maps to JSONObjects where needed
        if (config.exists("defaultToggleStates")) {
            Map<String, Object> defaultToggles = config.getSection("defaultToggleStates").getAllMap();
            defaults.put("defaultToggleStates", convertMapToJson(defaultToggles));
        }
        
        if (config.exists("inheritedToggles")) {
            Map<String, Object> inherited = config.getSection("inheritedToggles").getAllMap();
            defaults.put("inheritedToggles", convertMapToJson(inherited));
        }

        validate();
    }

    public void reload() {
        load();
        plugin.reloadConfigValues();
    }

    private void validate() {
        boolean needsSave = false;

        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            String key = entry.getKey();
            if (!config.exists(key)) {
                config.set(key, entry.getValue());
                plugin.getLogger().warning("Config key '" + key + "' was missing and has been set to default value.");
                needsSave = true;
            }
        }

        // Validate numeric values
        validateNumericRange("maxAreaPriority", 1, 1000);
        validateNumericRange("cacheExpiry", 1, 60);
        validateNumericRange("undoHistorySize", 1, 50);
        validateNumericRange("selectionCooldown", 50, 5000);
        validateNumericRange("visualizationDuration", 1, 60);
        
        // Validate title timings
        validateTitleTimings("title.enter");
        validateTitleTimings("title.leave");

        if (needsSave) {
            config.save();
        }
    }

    private void validateNumericRange(String key, int min, int max) {
        int value = config.getInt(key, (Integer) defaults.get(key));
        if (value < min || value > max) {
            plugin.getLogger().warning("Config key '" + key + "' had invalid value " + value + 
                " (must be between " + min + " and " + max + "). Reset to default: " + defaults.get(key));
            config.set(key, defaults.get(key));
        }
    }

    private void validateTitleTimings(String path) {
        if (!config.exists(path)) return;
        
        for (String timing : new String[]{"fadeIn", "stay", "fadeOut"}) {
            String timingPath = path + "." + timing;
            int value = config.getInt(timingPath, 20);
            if (value < 0 || value > 100) {
                config.set(timingPath, 20);
            }
        }
    }

    public String getMessage(String key) {
        return config.getString("messages." + key, (String) defaults.get("messages." + key));
    }

    public String getMessage(String key, Map<String, String> placeholders) {
        String message = getMessage(key);
        if (message == null) return "";
        
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return message;
    }

    public boolean isEnabled(String feature) {
        return config.getBoolean(feature, (Boolean) defaults.get(feature));
    }

    // Add debug mode checker
    public boolean isDebugEnabled() {
        return config.getBoolean("debug", false);
    }

    public Config getConfig() {
        return config;
    }

    public ConfigSection getSection(String path) {
        return config.getSection(path);
    }

    public Map<String, Object> getTitleConfig(String type) {
        return config.getSection("title." + type).getAllMap();
    }

    /**
     * Gets the maximum allowed priority value for areas.
     * Area priority determines which area's settings take precedence when areas overlap.
     * Higher priority areas (up to this maximum) override lower priority areas in the same space.
     *
     * @return The maximum allowed priority value (default: 100)
     */
    public int getMaxAreaPriority() {
        return config.getInt("maxAreaPriority", 100);
    }

    public int getWandItemType() {
        return config.getInt("wandItemType", 280);
    }

    public boolean isParticleVisualizationEnabled() {
        return config.getBoolean("particleVisualization", true);
    }

    public int getVisualizationDuration() {
        return config.getInt("visualizationDuration", 10);
    }

    public int getCacheExpiryMinutes() {
        return config.getInt("cacheExpiry", 5);
    }

    public int getUndoHistorySize() {
        return config.getInt("undoHistorySize", 10);
    }

    public int getSelectionCooldown() {
        return config.getInt("selectionCooldown", 250);
    }

    /**
     * Converts a configuration map to a JSONObject, handling nested structures.
     * Supports the following data types:
     * - Maps (converted to nested JSONObjects)
     * - Lists (converted to JSONArrays)
     * - Primitive types (String, Number, Boolean)
     * - null values
     *
     * @param map The configuration map to convert
     * @return A JSONObject containing the converted data
     * @throws IllegalArgumentException if an unsupported data type is encountered
     */
    private JSONObject convertMapToJson(Map<String, Object> map) {
        JSONObject json = new JSONObject();
        if (map == null) return json;

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                json.put(entry.getKey(), JSONObject.NULL);
            } else if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) value;
                json.put(entry.getKey(), convertMapToJson(nestedMap));
            } else if (value instanceof List) {
                json.put(entry.getKey(), convertListToJsonArray((List<?>) value));
            } else if (value instanceof String || value instanceof Number || 
                      value instanceof Boolean) {
                json.put(entry.getKey(), value);
            } else {
                // Log warning and store as string representation for unsupported types
                plugin.getLogger().warning("Unsupported config value type: " + 
                    value.getClass().getName() + " for key: " + entry.getKey());
                json.put(entry.getKey(), value.toString());
            }
        }
        return json;
    }

    /**
     * Converts a List to a JSONArray, handling nested structures.
     *
     * @param list The list to convert
     * @return A JSONArray containing the converted data
     */
    private org.json.JSONArray convertListToJsonArray(List<?> list) {
        org.json.JSONArray jsonArray = new org.json.JSONArray();
        
        for (Object item : list) {
            if (item == null) {
                jsonArray.put(JSONObject.NULL);
            } else if (item instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) item;
                jsonArray.put(convertMapToJson(map));
            } else if (item instanceof List) {
                jsonArray.put(convertListToJsonArray((List<?>) item));
            } else if (item instanceof String || item instanceof Number || 
                      item instanceof Boolean) {
                jsonArray.put(item);
            } else {
                // Log warning and store as string representation for unsupported types
                plugin.getLogger().warning("Unsupported list item type: " + 
                    item.getClass().getName());
                jsonArray.put(item.toString());
            }
        }
        return jsonArray;
    }

    /**
     * Gets a boolean config value with default fallback
     */
    public boolean getBoolean(String path, boolean defaultValue) {
        return config.getBoolean(path, defaultValue);
    }

    public JSONObject getDefaultPermissions() {
        ConfigSection section = config.getSection("defaultPermissions");
        return section != null ? convertMapToJson(section.getAllMap()) : new JSONObject();
    }

    public JSONObject getPermissionTemplate(String templateName) {
        ConfigSection templates = config.getSection("permissionTemplates");
        if (templates != null && templates.exists(templateName)) {
            return convertMapToJson(templates.getSection(templateName).getAllMap());
        }
        return null;
    }

    public Set<String> getAvailableTemplates() {
        ConfigSection templates = config.getSection("permissionTemplates");
        return templates != null ? templates.getKeys(false) : new HashSet<>();
    }

    public String getTemplateDescription(String templateName) {
        return config.getString("templateDescriptions." + templateName, "No description available");
    }
}
