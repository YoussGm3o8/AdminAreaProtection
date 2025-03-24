package adminarea.managers;

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
    private Config areaTitlesConfig;
    private final Map<String, Object> defaults;

    public ConfigManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.defaults = new HashMap<>();
        initializeDefaults();
    }

    private void initializeDefaults() {
        defaults.put("enableMessages", true);
        defaults.put("debug", false);
        defaults.put("wandItemType", 280); // Stick by default
        
        // Particle visualization settings
        Map<String, Object> particleVisualization = new HashMap<>();
        particleVisualization.put("enabled", true);
        particleVisualization.put("duration", 20); // Changed from 10 to 20 seconds
        defaults.put("particleVisualization", particleVisualization);
        
        // Add flat defaults for numeric validation
        defaults.put("visualizationDuration", 20); // Changed from 10 to 20 seconds
        defaults.put("cacheExpiry", 5); // minutes
        defaults.put("undoHistorySize", 10);
        defaults.put("selectionCooldown", 250); // milliseconds

        // Title configurations removed - now handled by areaTitles.yml

        // LuckPerms integration settings
        defaults.put("luckperms.enabled", true);
        defaults.put("luckperms.inheritPermissions", true);
        defaults.put("luckperms.updateInterval", 300); // seconds

        // Area merging settings
        defaults.put("areaSettings.useMostRestrictiveMerge", true);
        defaults.put("areaSettings.description.mergeBehavior", 
            "When true, overlapping areas use AND logic (most restrictive). " +
            "When false, uses OR logic (least restrictive).");
    }

    public void load() {
        // Make sure the data folder exists
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        
        // Load main config.yml
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            // Try to save the default config file
            try {
                plugin.saveResource("config.yml", false);
                plugin.getLogger().info("Created default config.yml");
            } catch (Exception e) {
                plugin.getLogger().error("Failed to create default config.yml", e);
            }
            
            // If the file still doesn't exist, create a basic one
            if (!configFile.exists()) {
                try {
                    configFile.createNewFile();
                    plugin.getLogger().info("Created empty config.yml");
                } catch (Exception e) {
                    plugin.getLogger().error("Failed to create empty config.yml", e);
                }
            }
        }

        config = new Config(configFile, Config.YAML);
        
        // Load area titles from areaTitles.yml
        File areaTitlesFile = new File(plugin.getDataFolder(), "areaTitles.yml");
        if (!areaTitlesFile.exists()) {
            // Try to save the default areaTitles.yml file
            try {
                plugin.saveResource("areaTitles.yml", false);
                plugin.getLogger().info("Created default areaTitles.yml");
            } catch (Exception e) {
                plugin.getLogger().error("Failed to create default areaTitles.yml", e);
                
                // If the file still doesn't exist, create a basic one
                if (!areaTitlesFile.exists()) {
                    try {
                        areaTitlesFile.createNewFile();
                        plugin.getLogger().info("Created empty areaTitles.yml");
                    } catch (Exception ex) {
                        plugin.getLogger().error("Failed to create empty areaTitles.yml", ex);
                    }
                }
            }
        }
        
        areaTitlesConfig = new Config(areaTitlesFile, Config.YAML);
        
        // Initialize default area titles if empty
        if (areaTitlesConfig.getKeys(false).isEmpty()) {
            // Create default structure
            Map<String, Object> defaultEntry = new HashMap<>();
            
            Map<String, Object> enterDefaults = new HashMap<>();
            enterDefaults.put("main", "§6Welcome to {area}");
            enterDefaults.put("subtitle", "§eEnjoy your stay!");
            enterDefaults.put("fadeIn", 20);
            enterDefaults.put("stay", 40);
            enterDefaults.put("fadeOut", 20);
            
            Map<String, Object> leaveDefaults = new HashMap<>();
            leaveDefaults.put("main", "§6Leaving {area}");
            leaveDefaults.put("subtitle", "§eThank you for visiting!");
            leaveDefaults.put("fadeIn", 20);
            leaveDefaults.put("stay", 40);
            leaveDefaults.put("fadeOut", 20);
            
            Map<String, Object> defaultSection = new HashMap<>();
            defaultSection.put("enter", enterDefaults);
            defaultSection.put("leave", leaveDefaults);
            
            areaTitlesConfig.set("default", defaultSection);
            saveAreaTitles();
        }
        
        // Load debug mode first before any other operations
        boolean debugMode = config.getBoolean("debug", false);
        plugin.setDebugMode(debugMode);
        
        // Migrate old config format if needed
        migrateOldConfig();
        validate();
    }

    public void reload() {
        load();
        plugin.reloadConfigValues();
    }

    public void save() {
        try {
            config.save();
            if (plugin.isDebugMode()) {
                plugin.debug("Configuration saved successfully");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to save configuration", e);
        }
    }
    
    public void saveAreaTitles() {
        try {
            areaTitlesConfig.save();
            if (plugin.isDebugMode()) {
                plugin.debug("Area titles saved successfully");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to save area titles", e);
        }
    }

    /**
     * Gets area title configuration for the specified area
     * 
     * @param areaName The name of the area
     * @return Map containing title configuration or null if not found
     */
    public Map<String, Object> getAreaTitleConfig(String areaName) {
        // Check if the area has configuration in areaTitles.yml
        if (areaTitlesConfig.exists(areaName)) {
            return areaTitlesConfig.getSection(areaName).getAllMap();
        }
        
        // Return null if no specific configuration exists
        return null;
    }
    
    /**
     * Gets the title text for an area's enter or leave event
     * 
     * @param areaName The area name
     * @param type "enter" or "leave"
     * @param field "main" or "subtitle"
     * @param defaultValue Default value if not found
     * @return The configured title text
     */
    public String getAreaTitleText(String areaName, String type, String field, String defaultValue) {
        // Check specific area config first
        String specificPath = areaName + "." + type + "." + field;
        if (areaTitlesConfig.exists(specificPath)) {
            return areaTitlesConfig.getString(specificPath);
        }
        
        // Fall back to default config
        String defaultPath = "default." + type + "." + field;
        if (areaTitlesConfig.exists(defaultPath)) {
            String template = areaTitlesConfig.getString(defaultPath);
            return template.replace("{area}", areaName);
        }
        
        // Use provided default if nothing else is available
        return defaultValue;
    }
    
    /**
     * Updates area title configuration in areaTitles.yml
     * 
     * @param areaName The area name
     * @param type "enter" or "leave"
     * @param field "main" or "subtitle"
     * @param value The new value to set
     */
    public void setAreaTitleText(String areaName, String type, String field, String value) {
        areaTitlesConfig.set(areaName + "." + type + "." + field, value);
        saveAreaTitles();
    }
    
    /**
     * Checks if area title configuration exists for the specified area
     * 
     * @param areaName The area name
     * @return true if configuration exists, false otherwise
     */
    public boolean hasAreaTitleConfig(String areaName) {
        return areaTitlesConfig.exists(areaName);
    }
    
    /**
     * Removes area title configuration for the specified area
     * 
     * @param areaName The area name
     */
    public void removeAreaTitleConfig(String areaName) {
        areaTitlesConfig.remove(areaName);
        saveAreaTitles();
    }

    /**
     * Gets all area names with custom title configurations
     * 
     * @return Set of area names
     */
    public Set<String> getAreaTitleNames() {
        Set<String> result = new HashSet<>(areaTitlesConfig.getKeys(false));
        result.remove("default"); // Remove default section
        return result;
    }

    /**
     * Check if a path exists in the areaTitles configuration
     * @param path The path to check
     * @return True if the path exists
     */
    public boolean existsInAreaTitles(String path) {
        return areaTitlesConfig.exists(path);
    }

    /**
     * Get a value from areaTitles.yml
     * @param path The path to get
     * @param defaultValue The default value if not found
     * @return The value or default
     */
    public String getAreaTitlesSetting(String path, String defaultValue) {
        return areaTitlesConfig.getString(path, defaultValue);
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
        validateNumericRange("cacheExpiry", 1, 60);
        validateNumericRange("undoHistorySize", 1, 50);
        validateNumericRange("selectionCooldown", 50, 5000);
        validateNumericRange("particleVisualization.duration", 1, 120); // Allow up to 2 minutes
        
        // Validate title timings in areaTitles.yml
        validateTitleTimings("default.enter");
        validateTitleTimings("default.leave");
        
        // Validate all custom area title timings
        for (String areaName : getAreaTitleNames()) {
            validateTitleTimings(areaName + ".enter");
            validateTitleTimings(areaName + ".leave");
        }

        if (needsSave) {
            config.save();
        }
    }

    private void migrateOldConfig() {
        // Migrate old particleVisualization boolean to new section format
        if (config.exists("particleVisualization") && !(config.get("particleVisualization") instanceof ConfigSection)) {
            boolean oldValue = config.getBoolean("particleVisualization", true);
            config.set("particleVisualization", null); // Remove old value
            
            // Create new section with old enabled value and default duration
            Map<String, Object> newSection = new HashMap<>();
            newSection.put("enabled", oldValue);
            newSection.put("duration", 10);
            config.set("particleVisualization", newSection);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Migrated particleVisualization from boolean to section");
            }
        }
    }

    private void validateNumericRange(String key, int min, int max) {
        try {
            // Handle nested keys (e.g. particleVisualization.duration)
            String[] parts = key.split("\\.");
            int value;
            
            if (parts.length > 1) {
                // Get the default value first
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedDefaults = (Map<String, Object>) defaults.get(parts[0]);
                int defaultValue = nestedDefaults != null ? (Integer) nestedDefaults.get(parts[1]) : 10;
                
                // Try to get the actual value
                Object section = config.get(parts[0]);
                if (section instanceof ConfigSection) {
                    ConfigSection configSection = (ConfigSection) section;
                    value = configSection.getInt(parts[1], defaultValue);
                } else {
                    // If not a section, use default and create the section
                    value = defaultValue;
                    Map<String, Object> newSection = new HashMap<>();
                    if (parts[0].equals("particleVisualization")) {
                        newSection.put("enabled", true);
                        newSection.put("duration", value);
                    }
                    config.set(parts[0], newSection);
                }
            } else {
                value = config.getInt(key, (Integer) defaults.get(key));
            }
            
            if (value < min || value > max) {
                int defaultValue = (Integer) defaults.get(key);
                plugin.getLogger().warning("Config key '" + key + "' had invalid value " + value + 
                    " (must be between " + min + " and " + max + "). Reset to default: " + defaultValue);
                if (parts.length > 1) {
                    ConfigSection section = config.getSection(parts[0]);
                    section.set(parts[1], defaultValue);
                } else {
                    config.set(key, defaultValue);
                }
            }
        } catch (Exception e) {
            // If any error occurs, set to default value
            if (plugin.isDebugMode()) {
                plugin.debug("Error validating " + key + ": " + e.getMessage());
            }
            setDefaultValue(key);
        }
    }

    private void setDefaultValue(String key) {
        String[] parts = key.split("\\.");
        if (parts.length > 1) {
            @SuppressWarnings("unchecked")
            Map<String, Object> nestedDefaults = (Map<String, Object>) defaults.get(parts[0]);
            if (nestedDefaults != null) {
                Map<String, Object> newSection = new HashMap<>(nestedDefaults);
                config.set(parts[0], newSection);
            }
        } else {
            config.set(key, defaults.get(key));
        }
    }

    private void validateTitleTimings(String path) {
        // Skip if path doesn't exist in areaTitles.yml
        if (!areaTitlesConfig.exists(path)) return;
        
        for (String timing : new String[]{"fadeIn", "stay", "fadeOut"}) {
            String timingPath = path + "." + timing;
            // Get as string first to handle both string and integer values
            String rawValue = areaTitlesConfig.getString(timingPath, "20");
            int value;
            try {
                value = Integer.parseInt(rawValue);
            } catch (NumberFormatException e) {
                value = 20; // Default to 20 if not a valid number
            }
            
            if (value < 0 || value > 100) {
                areaTitlesConfig.set(timingPath, 20);
            }
        }
        
        // Save changes if any were made
        saveAreaTitles();
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
        // Handle null or missing values by returning the default if available
        if (feature == null) return false;
        
        Boolean defaultValue = (Boolean) defaults.get(feature);
        return config.getBoolean(feature, defaultValue != null ? defaultValue : false);
    }

    // Add debug mode checker
    public boolean isDebugEnabled() {
        return config.getBoolean("debug", false);
    }

    public void setDebug(boolean enabled) {
        config.set("debug", enabled);
        save();
        plugin.setDebugMode(enabled);
    }
 
    public Config getConfig() {
        return config;
    }

    public ConfigSection getSection(String path) {
        return config.getSection(path);
    }

    public Map<String, Object> getTitleConfig(String type) {
        // Check if the type is "enter" or "leave" and return from areaTitles.yml default section
        if ("enter".equals(type) || "leave".equals(type)) {
            if (areaTitlesConfig.exists("default." + type)) {
                return areaTitlesConfig.getSection("default." + type).getAllMap();
            }
        }
        
        // Fallback to empty map if not found
        return new HashMap<>();
    }

    /**
     * Gets the maximum allowed priority value for areas.
     * Area priority determines which area's settings take precedence when areas overlap.
     * Higher priority areas (up to 100) override lower priority areas in the same space.
     *
     * @return The maximum allowed priority value (100)
     */
    public int getMaxAreaPriority() {
        return 100;
    }

    public int getWandItemType() {
        return config.getInt("wandItemType", 280);
    }

    public boolean isParticleVisualizationEnabled() {
        try {
            Object section = config.get("particleVisualization");
            
            // Handle different types of the config value
            if (section instanceof ConfigSection) {
                return ((ConfigSection) section).getBoolean("enabled", true);
            } else if (section instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) section;
                Object enabled = map.get("enabled");
                return enabled instanceof Boolean ? (Boolean) enabled : true;
            } else if (section instanceof Boolean) {
                // Legacy format
                return (Boolean) section;
            }
            
            // If we get here, either the section doesn't exist or is invalid
            // Set default values and return default
            Map<String, Object> defaultSection = new HashMap<>();
            defaultSection.put("enabled", true);
            defaultSection.put("duration", 10);
            config.set("particleVisualization", defaultSection);
            return true;
            
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error getting particle visualization enabled state: " + e.getMessage());
            }
            return true; // Default to enabled on error
        }
    }

    public int getVisualizationDuration() {
        try {
            Object section = config.get("particleVisualization");
            
            // Handle different types of the config value
            if (section instanceof ConfigSection) {
                return ((ConfigSection) section).getInt("duration", 10);
            } else if (section instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) section;
                Object duration = map.get("duration");
                return duration instanceof Number ? ((Number) duration).intValue() : 10;
            }
            
            // If we get here, either the section doesn't exist or is invalid
            // Set default values and return default
            Map<String, Object> defaultSection = new HashMap<>();
            defaultSection.put("enabled", true);
            defaultSection.put("duration", 10);
            config.set("particleVisualization", defaultSection);
            return 10;
            
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error getting visualization duration: " + e.getMessage());
            }
            return 10; // Default duration on error
        }
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
     * Sets a configuration value and saves the config.
     * @param path The configuration path
     * @param value The value to set
     */
    public void set(String path, Object value) {
        if (path == null) return;
        
        try {
            config.set(path, value);
            
            // Save immediately to ensure changes are persisted
            save();
            
            // If this is the debug setting, update plugin's debug mode
            if (path.equals("debug")) {
                plugin.setDebugMode(value instanceof Boolean ? (Boolean)value : false);
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("Updated config value " + path + " to: " + value);
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to save config value: " + path, e);
        }
    }

    /**
     * Gets a boolean value from config with default fallback
     */
    public boolean getBoolean(String path, boolean defaultValue) {
        if (!config.exists(path)) {
            set(path, defaultValue);
            return defaultValue;
        }
        return config.getBoolean(path);
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

    /**
     * Removes a config path
     * @param path The path to remove
     */
    public void remove(String path) {
        if (path == null || path.isEmpty()) return;
        
        try {
            // For nested paths, we need to create a mutable config
            String[] parts = path.split("\\.");
            if (parts.length <= 1) {
                config.remove(path);
                return;
            }
            
            // For nested paths, traverse parent sections
            String[] parentParts = new String[parts.length - 1];
            System.arraycopy(parts, 0, parentParts, 0, parentParts.length);
            String parent = String.join(".", parentParts);
            String key = parts[parts.length - 1];
            
            // Get parent section and remove child entry
            if (config.exists(parent)) {
                ConfigSection section = config.getSection(parent);
                if (section != null) {
                    section.remove(key);
                    
                    // Check if section is now empty and remove it if so
                    if (section.isEmpty()) {
                        // Try to remove parent section recursively
                        remove(parent);
                    }
                }
            }
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error removing config path " + path + ": " + e.getMessage());
            }
        }
    }

    /**
     * Check if a path exists in the configuration
     * @param path The path to check
     * @return True if the path exists
     */
    public boolean exists(String path) {
        return config.exists(path);
    }

    /**
     * Gets an integer value from config with default fallback
     */
    public int getInt(String path, int defaultValue) {
        if (!config.exists(path)) {
            set(path, defaultValue);
            return defaultValue;
        }
        return config.getInt(path);
    }

    /**
     * Gets a string value from config with default fallback
     */
    public String getSettingString(String path, String defaultValue) {
        if (!config.exists(path)) {
            set(path, defaultValue);
            return defaultValue;
        }
        return config.getString(path, defaultValue);
    }
}
