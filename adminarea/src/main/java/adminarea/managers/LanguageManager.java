package adminarea.managers;

import adminarea.AdminAreaProtectionPlugin;
import cn.nukkit.utils.Config;
import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LanguageManager {
    private final AdminAreaProtectionPlugin plugin;
    private Config languageConfig;
    private final Pattern placeholderPattern = Pattern.compile("\\{([^}]+)}");
    private Map<String, Object> messages;
    private String prefix;
    private File langFile;  // Add langFile field
    private boolean isDirty = false;  // Track if we need to save
            
    public LanguageManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.messages = new HashMap<>();
        this.prefix = "§8[§bAreaProtect§8] §r";
        loadLanguage();
    }
            
    private void loadLanguage() {
        String language = plugin.getConfig().getString("language", "en_US");
        langFile = new File(plugin.getDataFolder() + "/lang", language + ".yml");

        // Create parent directory if it doesn't exist
        File langDir = langFile.getParentFile();
        if (!langDir.exists()) {
            langDir.mkdirs();
        }

        // Always save the default resource to ensure we have the latest version
        try {
            plugin.saveResource("lang/" + language + ".yml", true);
            if (plugin.isDebugMode()) {
                plugin.debug("Saved default language file to: " + langFile.getAbsolutePath());
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to save default language file", e);
        }

        // Load the language file
        try {
            languageConfig = new Config(langFile, Config.YAML);
            if (plugin.isDebugMode()) {
                plugin.debug("Loaded language file from: " + langFile.getAbsolutePath());
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to load language file", e);
            languageConfig = new Config();
        }
        
        // Load all messages from the file
        messages = new LinkedHashMap<>();
        Map<String, Object> allMessages = new LinkedHashMap<>(languageConfig.getAll());
        
        if (plugin.isDebugMode()) {
            plugin.debug("Initial config data: " + allMessages);
            if (allMessages.containsKey("gui")) {
                plugin.debug("Initial GUI section: " + allMessages.get("gui"));
            }
        }

        // Load all messages first
        loadNestedMessages("", allMessages);

        // Then process GUI sections to ensure they're properly loaded
        processGuiSections();
        
        // Get prefix from messages or use default
        prefix = (String) getNestedValue(messages, "messages.prefix", "§8[§bAreaProtect§8] §r");
        
        if (plugin.isDebugMode()) {
            plugin.debug("Loaded " + messages.size() + " messages");
            plugin.debug("Loaded prefix: " + prefix);
            
            // Debug print all GUI sections
            debugPrintGuiSections();
        }
        
        // Save any changes back to the file
        languageConfig.setAll(new LinkedHashMap<>(allMessages));
        try {
            languageConfig.save(langFile);
            if (plugin.isDebugMode()) {
                plugin.debug("Saved updated language file");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to save updated language file", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void processGuiSections() {
        // Get or create GUI section
        Map<String, Object> guiSection = (Map<String, Object>) messages.computeIfAbsent("gui", k -> new LinkedHashMap<>());

        // Process each GUI section
        processMainMenu(guiSection);
        processCreateArea(guiSection);
        processEditArea(guiSection);
        processDeleteArea(guiSection);
        processBasicSettings(guiSection);
        processPluginSettings(guiSection);
        processCategorySettings(guiSection);
        processLuckPermsSettings(guiSection);
    }

    @SuppressWarnings("unchecked")
    private void processMainMenu(Map<String, Object> guiSection) {
        Map<String, Object> mainMenu = (Map<String, Object>) guiSection.computeIfAbsent("mainMenu", k -> new LinkedHashMap<>());
        mainMenu.putIfAbsent("title", "§6Area Protection");
        mainMenu.putIfAbsent("content", "§7Select an option to manage protected areas:");
        
        Map<String, Object> buttons = (Map<String, Object>) mainMenu.computeIfAbsent("buttons", k -> new LinkedHashMap<>());
        buttons.putIfAbsent("createArea", "§a➕ Create Area\n§7Create a new protected area");
        buttons.putIfAbsent("editArea", "§e✏ Edit Area\n§7Modify an existing area");
        buttons.putIfAbsent("deleteArea", "§c❌ Delete Area\n§7Remove a protected area");
        buttons.putIfAbsent("pluginSettings", "§b⚙ Settings\n§7Configure plugin options");
        buttons.putIfAbsent("back", "§7« Back");
    }

    @SuppressWarnings("unchecked")
    private void processCreateArea(Map<String, Object> guiSection) {
        Map<String, Object> createArea = (Map<String, Object>) guiSection.computeIfAbsent("createArea", k -> new LinkedHashMap<>());
        createArea.putIfAbsent("title", "Create Protected Area");
        createArea.putIfAbsent("header", "§2Enter Area Details\n§7Fill in the required information below");
        
        Map<String, Object> labels = (Map<String, Object>) createArea.computeIfAbsent("labels", k -> new LinkedHashMap<>());
        labels.putIfAbsent("name", "Area Name");
        labels.putIfAbsent("namePlaceholder", "Enter a unique name for this area");
        labels.putIfAbsent("priority", "Priority");
        labels.putIfAbsent("priorityPlaceholder", "Enter priority (0-100)");
        labels.putIfAbsent("defaultPriority", "50");
        labels.putIfAbsent("showTitle", "Show Area Title");
        labels.putIfAbsent("protectWorld", "Protect Entire World");
        labels.putIfAbsent("bounds", "\n§2Area Bounds\n§7Current selection coordinates");
        labels.putIfAbsent("pos1x", "First Position X");
        labels.putIfAbsent("pos1y", "First Position Y");
        labels.putIfAbsent("pos1z", "First Position Z");
        labels.putIfAbsent("pos2x", "Second Position X");
        labels.putIfAbsent("pos2y", "Second Position Y");
        labels.putIfAbsent("pos2z", "Second Position Z");
        labels.putIfAbsent("coordPlaceholder", "Enter {axis} coordinate");
        labels.putIfAbsent("protection", "\n§2Protection Settings\n§7Configure area permissions");
        labels.putIfAbsent("enterMessage", "Enter Message");
        labels.putIfAbsent("enterPlaceholder", "Message shown when entering area");
        labels.putIfAbsent("leaveMessage", "Leave Message");
        labels.putIfAbsent("leavePlaceholder", "Message shown when leaving area");
    }

    @SuppressWarnings("unchecked")
    private void processEditArea(Map<String, Object> guiSection) {
        Map<String, Object> editArea = (Map<String, Object>) guiSection.computeIfAbsent("editArea", k -> new LinkedHashMap<>());
        editArea.putIfAbsent("title", "Edit Area: {area}");
        editArea.putIfAbsent("content", "Select what to edit:");
        editArea.putIfAbsent("selectPrompt", "Choose an area to edit:");
        
        Map<String, Object> buttons = (Map<String, Object>) editArea.computeIfAbsent("buttons", k -> new LinkedHashMap<>());
        buttons.putIfAbsent("basicSettings", "Basic Settings\n§7Name, priority, messages");
        buttons.putIfAbsent("protectionSettings", "Protection Settings\n§7Core protection flags");
        buttons.putIfAbsent("buildingSettings", "Building Settings\n§7Building and blocks");
        buttons.putIfAbsent("environmentSettings", "Environment Settings\n§7Natural events");
        buttons.putIfAbsent("entitySettings", "Entity Settings\n§7Mobs and entities");
        buttons.putIfAbsent("technicalSettings", "Technical Settings\n§7Redstone, pistons");
        buttons.putIfAbsent("specialSettings", "Special Settings\n§7Other permissions");
        buttons.putIfAbsent("groupPermissions", "Group Permissions\n§7LuckPerms groups");
    }

    @SuppressWarnings("unchecked")
    private void processDeleteArea(Map<String, Object> guiSection) {
        Map<String, Object> deleteArea = (Map<String, Object>) guiSection.computeIfAbsent("deleteArea", k -> new LinkedHashMap<>());
        deleteArea.putIfAbsent("title", "Delete Area");
        deleteArea.putIfAbsent("content", "Select an area to delete:");
        deleteArea.putIfAbsent("confirmTitle", "Confirm Delete");
        deleteArea.putIfAbsent("confirmPrompt", "Are you sure you want to delete area '{area}'?");
        
        Map<String, Object> buttons = (Map<String, Object>) deleteArea.computeIfAbsent("buttons", k -> new LinkedHashMap<>());
        buttons.putIfAbsent("confirm", "§cConfirm Delete");
        buttons.putIfAbsent("cancel", "§aCancel");
    }

    @SuppressWarnings("unchecked")
    private void processBasicSettings(Map<String, Object> guiSection) {
        Map<String, Object> basicSettings = (Map<String, Object>) guiSection.computeIfAbsent("basicSettings", k -> new LinkedHashMap<>());
        basicSettings.putIfAbsent("title", "Basic Settings: {area}");
        
        Map<String, Object> sections = (Map<String, Object>) basicSettings.computeIfAbsent("sections", k -> new LinkedHashMap<>());
        sections.putIfAbsent("general", "§2General Settings");
        sections.putIfAbsent("display", "\n§2Display Settings");
        
        Map<String, Object> labels = (Map<String, Object>) basicSettings.computeIfAbsent("labels", k -> new LinkedHashMap<>());
        labels.putIfAbsent("name", "Area Name");
        labels.putIfAbsent("namePlaceholder", "Enter area name");
        labels.putIfAbsent("priority", "Priority");
        labels.putIfAbsent("priorityPlaceholder", "Enter priority (0-100)");
        labels.putIfAbsent("showTitle", "Show Area Title");
        labels.putIfAbsent("enterMessage", "Enter Message");
        labels.putIfAbsent("enterPlaceholder", "Message shown when entering");
        labels.putIfAbsent("leaveMessage", "Leave Message");
        labels.putIfAbsent("leavePlaceholder", "Message shown when leaving");
    }

    @SuppressWarnings("unchecked")
    private void processPluginSettings(Map<String, Object> guiSection) {
        Map<String, Object> pluginSettings = (Map<String, Object>) guiSection.computeIfAbsent("pluginSettings", k -> new LinkedHashMap<>());
        pluginSettings.putIfAbsent("title", "Plugin Settings");
        
        // Initialize sections with default values
        Map<String, Object> sections = (Map<String, Object>) pluginSettings.computeIfAbsent("sections", k -> new LinkedHashMap<>());
        Map<String, String> defaultSections = new LinkedHashMap<>();
        defaultSections.put("general", "§2General Settings");
        defaultSections.put("area", "\n§2Area Settings");
        defaultSections.put("tools", "\n§2Tool Settings");
        defaultSections.put("cache", "\n§2Cache Settings");
        defaultSections.put("integrations", "\n§2Integrations");

        // Add any missing sections
        boolean needsSave = false;
        for (Map.Entry<String, String> entry : defaultSections.entrySet()) {
            if (!sections.containsKey(entry.getKey())) {
                sections.put(entry.getKey(), entry.getValue());
                needsSave = true;
            }
        }
        
        Map<String, Object> labels = (Map<String, Object>) pluginSettings.computeIfAbsent("labels", k -> new LinkedHashMap<>());
        
        // Define all labels in a map
        Map<String, String> defaultLabels = new LinkedHashMap<>();
        defaultLabels.put("debugMode", "Debug Mode");
        defaultLabels.put("debugStackTraces", "Debug Stack Traces");
        defaultLabels.put("enableMessages", "Enable Messages");
        defaultLabels.put("allowRegularAreaCreation", "Allow Regular Area Creation");
        defaultLabels.put("maxAreaPriority", "Max Area Priority");
        defaultLabels.put("maxAreaPriorityPlaceholder", "1-1000");
        defaultLabels.put("mostRestrictiveMerge", "Most Restrictive Area Merge");
        defaultLabels.put("wandItemType", "Wand Item Type");
        defaultLabels.put("wandItemTypePlaceholder", "Item ID");
        defaultLabels.put("particleVisualization", "Particle Visualization");
        defaultLabels.put("visualizationDuration", "Visualization Duration");
        defaultLabels.put("visualizationDurationPlaceholder", "Seconds");
        defaultLabels.put("selectionCooldown", "Selection Cooldown");
        defaultLabels.put("selectionCooldownPlaceholder", "Milliseconds");
        defaultLabels.put("cacheExpiryTime", "Cache Expiry Time");
        defaultLabels.put("cacheExpiryTimePlaceholder", "Minutes");
        defaultLabels.put("undoHistorySize", "Undo History Size");
        defaultLabels.put("undoHistorySizePlaceholder", "1-50");
        defaultLabels.put("luckpermsIntegration", "LuckPerms Integration");
        defaultLabels.put("inheritLuckpermsPermissions", "Inherit LuckPerms Permissions");
        defaultLabels.put("luckpermsUpdateInterval", "LuckPerms Update Interval");
        defaultLabels.put("luckpermsUpdateIntervalPlaceholder", "Minutes");

        // Add all labels and mark for saving if any are missing
        for (Map.Entry<String, String> entry : defaultLabels.entrySet()) {
            if (!labels.containsKey(entry.getKey())) {
                labels.put(entry.getKey(), entry.getValue());
                needsSave = true;
            }
        }

        // Save to YAML if any sections or labels were missing
        if (needsSave) {
            try {
                // Update the config with new values
                languageConfig.set("gui.pluginSettings.sections", sections);
                languageConfig.set("gui.pluginSettings.labels", labels);
                // Save to file
                languageConfig.save(langFile);
                if (plugin.isDebugMode()) {
                    plugin.debug("Added missing plugin settings sections/labels to language file");
                }
            } catch (Exception e) {
                plugin.getLogger().error("Failed to save updated language file", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void processCategorySettings(Map<String, Object> guiSection) {
        Map<String, Object> categorySettings = (Map<String, Object>) guiSection.computeIfAbsent("categorySettings", k -> new LinkedHashMap<>());
        categorySettings.putIfAbsent("title", "{category}: {area}");
        categorySettings.putIfAbsent("header", "§2Configure {category} Permissions\n§7Toggle settings below");
    }

    @SuppressWarnings("unchecked")
    private void processLuckPermsSettings(Map<String, Object> guiSection) {
        Map<String, Object> luckperms = (Map<String, Object>) guiSection.computeIfAbsent("luckperms", k -> new LinkedHashMap<>());
        luckperms.putIfAbsent("title", "Edit {group} Permissions");
        luckperms.putIfAbsent("groupSelect", "Select Group");
        luckperms.putIfAbsent("trackSelect", "Select Track");
        luckperms.putIfAbsent("groupPerms", "Group Permissions");
        luckperms.putIfAbsent("inheritedFrom", "Inherited from: {groups}");
    }

    private void debugPrintGuiSections() {
        String[] sections = {
            "gui.mainMenu",
            "gui.createArea",
            "gui.editArea",
            "gui.deleteArea",
            "gui.basicSettings",
            "gui.pluginSettings",
            "gui.categorySettings",
            "gui.luckperms"
        };

        for (String section : sections) {
            Object value = getNestedValue(messages, section, null);
            plugin.debug(section + " content: " + value);
            
            // Also check for common subsections
            String[] subsections = {"title", "content", "buttons", "labels", "sections"};
            for (String sub : subsections) {
                Object subValue = getNestedValue(messages, section + "." + sub, null);
                if (subValue != null) {
                    plugin.debug(section + "." + sub + " content: " + subValue);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void loadNestedMessages(String prefix, Map<String, Object> map) {
        if (plugin.isDebugMode()) {
            plugin.debug("Loading nested messages for prefix: " + prefix);
        }

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            
            if (value instanceof Map) {
                // Store the map itself first
                messages.put(key, new LinkedHashMap<>((Map<String, Object>) value));
                // Then recursively process its contents
                loadNestedMessages(key, (Map<String, Object>) value);
            } else {
                if (plugin.isDebugMode()) {
                    plugin.debug("Loading message: " + key + " = " + value);
                }
                // For non-map values, store them directly
                messages.put(key, value);
            }
        }

        // After loading, verify critical GUI sections exist
        if (prefix.isEmpty()) {
            ensureGuiSectionsExist();
        }
    }

    private void ensureGuiSectionsExist() {
        // Define critical GUI paths that must exist
        String[][] criticalPaths = {
            {"gui", "mainMenu", "sections"},
            {"gui", "pluginSettings", "sections"},
            {"gui", "basicSettings", "sections"},
            {"gui", "protectionSettings", "sections"}
        };

        for (String[] pathParts : criticalPaths) {
            String fullPath = String.join(".", pathParts);
            Object value = getNestedValue(messages, fullPath, null);
            if (value == null) {
                // Create the nested structure if it doesn't exist
                Map<String, Object> current = messages;
                for (String part : pathParts) {
                    current = (Map<String, Object>) current.computeIfAbsent(part, k -> new LinkedHashMap<>());
                }
                isDirty = true;
            }
        }

        // If any critical paths were missing, save the changes
        if (isDirty) {
            saveLanguageFile();
        }
    }

    public void reload() {
        if (isDirty) {
            saveLanguageFile();
        }
        loadLanguage();
    }

    private void addMissingMessage(String path, String defaultValue) {
        // Don't add missing messages for GUI strings
        if (path.startsWith("gui.")) {
            return;
        }
        
        // Split path into parts to create nested structure
        String[] parts = path.split("\\.");
        Map<String, Object> current = messages;
        
        // Build nested structure
        for (int i = 0; i < parts.length - 1; i++) {
            current = (Map<String, Object>) current.computeIfAbsent(parts[i], k -> new LinkedHashMap<String, Object>());
        }
        
        // Only add if the message doesn't exist
        if (!current.containsKey(parts[parts.length - 1])) {
            current.put(parts[parts.length - 1], defaultValue);
            isDirty = true;

            // Save immediately in debug mode
            if (plugin.isDebugMode()) {
                saveLanguageFile();
                plugin.getLogger().debug("Added missing message: " + path + " = " + defaultValue);
            }
        }
    }

    private void saveLanguageFile() {
        if (!isDirty) return;

        try {
            // Get the current file content
            Map<String, Object> currentContent = new LinkedHashMap<>(languageConfig.getAll());
            
            // Merge new messages, preserving existing ones
            mergeMessages(currentContent, messages);
            
            // Save back to file, converting to LinkedHashMap
            languageConfig.setAll(new LinkedHashMap<>(currentContent));
            languageConfig.save(langFile);
            isDirty = false;
            
            if (plugin.isDebugMode()) {
                plugin.getLogger().debug("Saved language file with " + messages.size() + " messages");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to save language file", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeMessages(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            if (value instanceof Map) {
                // If target doesn't have this key or it's not a map, create new map
                if (!(target.get(key) instanceof Map)) {
                    target.put(key, new LinkedHashMap<String, Object>());
                }
                mergeMessages((Map<String, Object>) target.get(key), (Map<String, Object>) value);
            } else if (!target.containsKey(key)) {
                // Only add if key doesn't exist
                target.put(key, value);
            }
        }
    }

    public String get(String path) {
        if (plugin.isDebugMode()) {
            plugin.debug("Looking for message: " + path);
        }

        // First try to get from our loaded messages
        Object value = getNestedValue(messages, path, null);
        if (value != null) {
            if (plugin.isDebugMode()) {
                plugin.debug("Found in messages map: " + value);
            }
            return value.toString().replace("{prefix}", Matcher.quoteReplacement(prefix));
        }

        // If not found, try to get from language config
        value = languageConfig.getNested(path);
        if (value != null) {
            if (plugin.isDebugMode()) {
                plugin.debug("Found in language config: " + value);
            }
            // Add to our messages map for future use
            String[] parts = path.split("\\.");
            Map<String, Object> current = messages;
            for (int i = 0; i < parts.length - 1; i++) {
                current = (Map<String, Object>) current.computeIfAbsent(parts[i], k -> new LinkedHashMap<>());
            }
            current.put(parts[parts.length - 1], value);
            return value.toString().replace("{prefix}", Matcher.quoteReplacement(prefix));
        }

        // If still not found and it's a GUI string, return a formatted version of the key
        if (path.startsWith("gui.")) {
            if (plugin.isDebugMode()) {
                plugin.debug("Missing GUI message key: " + path);
            }
            plugin.getLogger().warning("Missing GUI language key: " + path);
            return path.substring(path.lastIndexOf('.') + 1)
                .replace(".", " ")
                .replace("_", " ");
        }

        // For non-GUI strings, create a default message
        String defaultMessage = "{" + path.substring(path.lastIndexOf('.') + 1) + "}";
        addMissingMessage(path, defaultMessage);
        if (plugin.isDebugMode()) {
            plugin.debug("Created default message for " + path + ": " + defaultMessage);
        }
        return defaultMessage.replace("{prefix}", prefix);
    }

    public String get(String path, Map<String, String> placeholders) {
        String message = get(path); // This will handle prefix replacement

        // Replace custom placeholders
        Matcher matcher = placeholderPattern.matcher(message);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String replacement = placeholders.getOrDefault(placeholder, matcher.group(0));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    public String getMessage(String key, Object... args) {
        String message = get(key);
        if (args.length > 0 && args[0] instanceof Map) {
            return formatMessage(message, (Map<String, String>)args[0]);
        }
        return formatMessage(message, new HashMap<>());
    }

    public String getProtectionMessage(String action, String area) {
        return getMessage("protection.denied", Map.of(
            "action", get("protection.actions." + action),
            "area", area
        ));
    }

    public String getErrorMessage(String message) {
        return getMessage("error", Map.of("message", message));
    }

    public String getSuccessMessage(String message) {
        return getMessage("success", Map.of("message", message));
    }

    private String formatMessage(String message, Map<String, String> placeholders) {
        Matcher matcher = placeholderPattern.matcher(message);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String replacement = placeholders.getOrDefault(placeholder, matcher.group(0));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    private Object getNestedValue(Map<String, Object> map, String path, Object defaultValue) {
        if (plugin.isDebugMode()) {
            plugin.debug("Getting nested value for path: " + path);
        }

        // First try direct lookup
        Object directValue = map.get(path);
        if (directValue != null) {
            if (plugin.isDebugMode()) {
                plugin.debug("Found direct value: " + directValue);
            }
            return directValue;
        }

        // If not found, try nested lookup
        String[] parts = path.split("\\.");
        Object current = map;

        for (String part : parts) {
            if (!(current instanceof Map)) {
                if (plugin.isDebugMode()) {
                    plugin.debug("Not a map at part: " + part);
                }
                return defaultValue;
            }
            current = ((Map<?, ?>) current).get(part);
            if (current == null) {
                if (plugin.isDebugMode()) {
                    plugin.debug("Null value at part: " + part);
                }
                return defaultValue;
            }
        }

        if (plugin.isDebugMode()) {
            plugin.debug("Found nested value: " + current);
        }
        return current;
    }

    @Override
    protected void finalize() throws Throwable {
        saveLanguageFile(); // Save any pending changes before garbage collection
        super.finalize();
    }
}
