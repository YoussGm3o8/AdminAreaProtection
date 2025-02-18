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

        if (!langFile.exists()) {
            plugin.saveResource("lang/" + language + ".yml", false);
        }

        languageConfig = new Config(langFile, Config.YAML);
        messages = new HashMap<>(languageConfig.getAll());
        prefix = (String) getNestedValue(messages, "messages.prefix", "§8[§bAreaProtect§8] §r");
    }

    public void reload() {
        if (isDirty) {
            saveLanguageFile();
        }
        loadLanguage();
    }

    private void addMissingMessage(String path, String defaultValue) {
        // Split path into parts to create nested structure
        String[] parts = path.split("\\.");
        Map<String, Object> current = messages;
        
        // Build nested structure
        for (int i = 0; i < parts.length - 1; i++) {
            current = (Map<String, Object>) current.computeIfAbsent(parts[i], k -> new HashMap<String, Object>());
        }
        
        // Add the message
        current.put(parts[parts.length - 1], defaultValue);
        isDirty = true;

        // Save immediately in debug mode
        if (plugin.isDebugMode()) {
            saveLanguageFile();
            plugin.getLogger().debug("Added missing message: " + path + " = " + defaultValue);
        }
    }

    private void saveLanguageFile() {
        if (!isDirty) return;

        try {
            languageConfig.setAll(new LinkedHashMap<>(messages));
            languageConfig.save(langFile);
            isDirty = false;
            if (plugin.isDebugMode()) {
                plugin.getLogger().debug("Saved language file with new messages");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to save language file", e);
        }
    }

    public String get(String path) {
        Object value = getNestedValue(messages, path, null);
        if (value == null) {
            // Create a default message based on the last part of the path
            String defaultMessage = "{" + path.substring(path.lastIndexOf('.') + 1) + "}";
            addMissingMessage(path, defaultMessage);
            plugin.getLogger().warning("Added missing language key: " + path + " with placeholder: " + defaultMessage);
            return defaultMessage.replace("{prefix}", prefix);
        }

        String message = value.toString();
        return message.replace("{prefix}", Matcher.quoteReplacement(prefix));
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
        String[] parts = path.split("\\.");
        Object current = map;

        for (String part : parts) {
            if (!(current instanceof Map)) {
                return defaultValue;
            }
            current = ((Map<?, ?>) current).get(part);
            if (current == null) {
                return defaultValue;
            }
        }

        return current;
    }

    @Override
    protected void finalize() throws Throwable {
        saveLanguageFile(); // Save any pending changes before garbage collection
        super.finalize();
    }
}
