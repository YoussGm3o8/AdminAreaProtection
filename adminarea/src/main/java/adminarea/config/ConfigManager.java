package adminarea.config;

import adminarea.AdminAreaProtectionPlugin;
import cn.nukkit.utils.Config;
import cn.nukkit.utils.ConfigSection;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

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
    }

    public void load() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }

        config = new Config(configFile, Config.YAML);
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
}
