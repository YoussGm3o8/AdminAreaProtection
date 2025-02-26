package adminarea.managers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.permissions.PermissionToggle;
import adminarea.util.Logger;
import cn.nukkit.utils.Config;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Arrays;

public class LanguageManager {
    private final AdminAreaProtectionPlugin plugin;
    private final Pattern placeholderPattern = Pattern.compile("\\{([^}]+)}");
    private final Logger logger;
    private Config config;
    private String prefix;

    public LanguageManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.logger = new Logger(plugin, "LanguageManager");
        loadLanguage();
    }

    private void loadLanguage() {
        String language = plugin.getConfig().getString("language", "en_US");
        File langFile = new File(plugin.getDataFolder() + "/lang", language + ".yml");

        if (!langFile.exists() || langFile.length() == 0) {
            plugin.saveResource("lang/" + language + ".yml", true);
            logger.debug("Created default language file: %s", langFile.getPath());
        }

        config = new Config(langFile, Config.YAML);
        logger.debug("Loaded language file with sections: %s", String.join(", ", config.getRootSection().getKeys(false)));
        
        prefix = getString("messages.prefix", "§8[§bAreaProtect§8] §r");
    }

    private String getString(String path, String defaultValue) {
        if (path == null || path.isEmpty()) {
            return defaultValue;
        }

        if (config.exists(path)) {
            return config.getString(path, defaultValue);
        }

        String[] parts = path.split("\\.");
        Object current = config.getRootSection();

        for (String part : parts) {
            if (!(current instanceof Map)) {
                logger.debug("Path traversal failed at: %s", part);
                return defaultValue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) current;

            if (!map.containsKey(part)) {
                logger.debug("Key not found: %s in path: %s", part, path);
                return defaultValue;
            }

            current = map.get(part);
        }

        return current != null ? current.toString() : defaultValue;
    }

    public void reload() {
        loadLanguage();
    }

    public String get(String path) {
        String value = getString(path, null);
        
        if (value == null) {
            logger.debug("Missing translation key: %s", path);
            return formatMissingKey(path);
        }

        return value.replace("{prefix}", prefix);
    }

    private String traverseGuiSection(Map<String, Object> section, String[] remainingPath) {
        if (remainingPath.length == 0) {
            return section.toString();
        }

        Object current = section;
        for (String part : remainingPath) {
            if (!(current instanceof Map)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) current;
            current = map.get(part);
            if (current == null) {
                return null;
            }
        }
        return current.toString();
    }

    private String formatMissingKey(String path) {
        logger.debug("Using fallback formatting for: %s", path);
        String key = path.substring(path.lastIndexOf('.') + 1);
        return "§c" + key.replace("_", " ")
                        .replace(".", " ")
                        .trim();
    }

    public String get(String path, Map<String, String> placeholders) {
        String message = get(path);
        if (message == null || message.isEmpty()) {
            return "";
        }

        Map<String, String> mutablePlaceholders = new HashMap<>(placeholders);
        handleSpecialPlaceholders(path, mutablePlaceholders);
        return formatMessage(message, mutablePlaceholders);
    }

    private void handleSpecialPlaceholders(String path, Map<String, String> placeholders) {
        if (path.startsWith("gui.permissions.toggles.") && placeholders.containsKey("toggle")) {
            handleTogglePlaceholders(placeholders);
        }

        if (placeholders.containsKey("category")) {
            handleCategoryPlaceholder(placeholders);
        }

        if (placeholders.containsKey("area")) {
            handleAreaPlaceholders(placeholders);
        }
    }

    private void handleTogglePlaceholders(Map<String, String> placeholders) {
        String toggleName = placeholders.get("toggle");
        PermissionToggle toggle = PermissionToggle.getToggle(toggleName);
        if (toggle != null) {
            placeholders.put("name", toggle.getDisplayName());
            if (!placeholders.containsKey("description")) {
                placeholders.put("description", get("gui.permissions.toggles." + toggle.getPermissionNode()));
            }
        }
    }

    private void handleCategoryPlaceholder(Map<String, String> placeholders) {
        try {
            String categoryName = placeholders.get("category");
            PermissionToggle.Category category = PermissionToggle.Category.valueOf(categoryName.toUpperCase());
            placeholders.put("category", category.getDisplayName());
        } catch (IllegalArgumentException ignored) {
            // Keep original value if not a valid category
        }
    }

    private void handleAreaPlaceholders(Map<String, String> placeholders) {
        String areaName = placeholders.get("area");
        Area area = plugin.getArea(areaName);
        
        if (area != null) {
            if (!placeholders.containsKey("world")) {
                placeholders.put("world", area.getWorld());
            }
            if (!placeholders.containsKey("priority")) {
                placeholders.put("priority", String.valueOf(area.getPriority()));
            }
        } else {
            if (!placeholders.containsKey("world")) {
                placeholders.put("world", "unknown");
            }
            if (!placeholders.containsKey("priority")) {
                placeholders.put("priority", "0");
            }
        }
        
        // Make sure player placeholder is handled
        if (!placeholders.containsKey("player")) {
            placeholders.put("player", "players");
        }
    }

    private String formatMessage(String message, Map<String, String> placeholders) {
        if (message == null) return "";

        message = message.replace("{prefix}", prefix);
        Matcher matcher = placeholderPattern.matcher(message);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String replacement = placeholders.getOrDefault(placeholder, matcher.group(0));

            // Handle special cases
            if ("player".equals(placeholder) && !placeholders.containsKey("player")) {
                replacement = "players";
            } else if ("player".equals(placeholder) && placeholders.containsKey("player")) {
                // Make sure player name is properly handled when provided
                String playerName = placeholders.get("player");
                if (playerName != null && !playerName.isEmpty()) {
                    replacement = playerName;
                }
            }
            
            // Handle position placeholder specifically
            if ("position".equals(placeholder) && !placeholders.containsKey("position")) {
                replacement = "position"; // Default fallback
            }

            if (replacement.matches("-?\\d+")) {
                try {
                    replacement = String.format("%,d", Integer.parseInt(replacement));
                } catch (NumberFormatException ignored) {}
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    public String getToggleDescription(String toggleName, Map<String, String> placeholders) {
        PermissionToggle toggle = PermissionToggle.getToggle(toggleName);
        if (toggle == null) return toggleName;

        Map<String, String> allPlaceholders = new HashMap<>(placeholders);
        allPlaceholders.put("toggle", toggleName);
        allPlaceholders.put("name", toggle.getDisplayName());

        return get("gui.permissions.toggles." + toggle.getPermissionNode(), allPlaceholders);
    }

    public String getFormattedToggle(String toggleName, Map<String, String> placeholders) {
        PermissionToggle toggle = PermissionToggle.getToggle(toggleName);
        if (toggle == null) return toggleName;

        Map<String, String> allPlaceholders = new HashMap<>(placeholders);
        allPlaceholders.put("name", toggle.getDisplayName());
        allPlaceholders.put("description", getToggleDescription(toggleName, placeholders));

        String format = getString("gui.permissions.toggle.format", "§e{name}\n§8{description}");
        return formatMessage(format, allPlaceholders);
    }
}
