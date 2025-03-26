package adminarea.managers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaDTO;
import adminarea.util.Logger;
import cn.nukkit.Player;
import cn.nukkit.utils.Config;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LanguageManager {
    private final AdminAreaProtectionPlugin plugin;
    private final Pattern placeholderPattern = Pattern.compile("\\{([^}]+)}");
    private final Logger logger;
    private Config config;
    private String prefix;
    
    // Thread-safe caches for better concurrent access
    private final Map<String, String> messageCache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> compiledMessageCache = new ConcurrentHashMap<>();
    private static final int CACHE_SIZE_LIMIT = 500; // Increased for better hit rate
    private final Map<String, Boolean> permissionCache = new ConcurrentHashMap<>();
    
    // Track missing and used keys for diagnostics
    private final Set<String> reportedMissingKeys = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> usedKeys = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Integer> keyUsageCount = new ConcurrentHashMap<>();

    public LanguageManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.logger = new Logger(plugin, "LanguageManager");
        loadLanguage();
    }

    private void loadLanguage() {
        String language = plugin.getConfig().getString("language", "en_US");
        File langFile = new File(plugin.getDataFolder() + "/lang", language + ".yml");

        // Ensure language file exists
        if (!langFile.exists() || langFile.length() == 0) {
            plugin.saveResource("lang/" + language + ".yml", true);
            logger.debug("Created default language file: %s", langFile.getPath());
        }

        // Load language file and validate
        config = new Config(langFile, Config.YAML);
        validateLanguageFile();
        
        prefix = getString("messages.prefix");
        
        // Clear caches on reload
        clearCaches();
        
        // Validate key placeholder requirements for critical messages
        validatePlaceholders();
        
        logger.debug("Loaded language file: %s", language);
        logger.debug("Available sections: %s", String.join(", ", config.getRootSection().getKeys(false)));
    }

    private void validateLanguageFile() {
        // Check required sections
        List<String> requiredSections = Arrays.asList(
            "messages",
            "validation",
            "success",
            "gui",
            "potionEffect"
        );
        
        List<String> missingSections = new ArrayList<>();
        
        for (String section : requiredSections) {
            // Check if section exists at top level
            boolean exists = config.exists(section);
            
            // If not at top level, try looking for it as a nested section under 'messages'
            if (!exists && section.equals("protection") && config.exists("messages.protection")) {
                exists = true;
                // Log a warning about section being in the wrong place
                logger.debug("Section '%s' found nested under 'messages' instead of at top level", section);
            }
            
            if (!exists) {
                missingSections.add(section);
            }
        }
            
        if (!missingSections.isEmpty()) {
            logger.warn("Missing required language sections: %s", String.join(", ", missingSections));
        }
    }

    /**
     * Validates that critical messages contain the required placeholders
     */
    private void validatePlaceholders() {
        // Area entry/exit messages must have {area} and {player} placeholders
        Map<String, List<String>> requiredPlaceholders = new HashMap<>();
        
        // Area entry/exit title and subtitle messages
        requiredPlaceholders.put("titles.enter.default.main", Arrays.asList("area"));
        requiredPlaceholders.put("titles.enter.default.subtitle", Arrays.asList("area", "player"));
        requiredPlaceholders.put("titles.leave.default.main", Arrays.asList("area"));
        requiredPlaceholders.put("titles.leave.default.subtitle", Arrays.asList("area", "player"));
        
        // Protection messages
        requiredPlaceholders.put("protection.blockBreak", Arrays.asList("area", "prefix"));
        requiredPlaceholders.put("protection.blockPlace", Arrays.asList("area", "prefix"));
        requiredPlaceholders.put("protection.interact", Arrays.asList("area", "prefix"));
        
        // Check each critical message
        requiredPlaceholders.forEach((path, required) -> {
            String message = getString(path);
            if (message != null) {
                List<String> missingPlaceholders = new ArrayList<>();
                for (String placeholder : required) {
                    if (!message.contains("{" + placeholder + "}")) {
                        missingPlaceholders.add(placeholder);
                    }
                }
                
                if (!missingPlaceholders.isEmpty()) {
                    logger.warn("Message '%s' is missing required placeholders: %s", 
                        path, String.join(", ", missingPlaceholders));
                }
            }
        });
    }

    /**
     * Gets a message with optional placeholder replacement
     */
    public String get(String path, Map<String, String> placeholders) {
        // Track key usage
        usedKeys.add(path);
        keyUsageCount.merge(path, 1, Integer::sum);
        
        // Try cache first for messages with placeholders
        if (placeholders != null && !placeholders.isEmpty()) {
            String cacheKey = buildPlaceholderCacheKey(path, placeholders);
            Map<String, String> cachedMap = compiledMessageCache.get(cacheKey);
            if (cachedMap != null && cachedMap.containsKey("message")) {
                return cachedMap.get("message");
            }
        }

        // Get base message
        String message = getString(path);
        if (message == null) {
            logMissingKey(path);
            return path; // Return key as fallback
        }

        // Create map with placeholders if null
        Map<String, String> allPlaceholders;
        if (placeholders != null) {
            allPlaceholders = new HashMap<>(placeholders);
        } else {
            allPlaceholders = new HashMap<>();
        }
        
        // Always add common placeholders
        allPlaceholders.put("prefix", prefix);
        allPlaceholders.put("time", getCurrentTimeFormatted());
        
        // Add default values for common placeholders if they don't exist
        addDefaultIfMissing(allPlaceholders, "area", "unknown area");
        addDefaultIfMissing(allPlaceholders, "player", "unknown player");
        addDefaultIfMissing(allPlaceholders, "world", "unknown world");
        addDefaultIfMissing(allPlaceholders, "priority", "0");
        
        // Replace placeholders
        message = replacePlaceholders(message, allPlaceholders);
        
        // Cache if not too large and has custom placeholders beyond prefix
        if (allPlaceholders.size() > 1 && compiledMessageCache.size() < CACHE_SIZE_LIMIT) {
            String cacheKey = buildPlaceholderCacheKey(path, allPlaceholders);
            Map<String, String> messageMap = new HashMap<>();
            messageMap.put("message", message);
            compiledMessageCache.put(cacheKey, messageMap);
        }

        // Return message (prefix already included via placeholder replacement)
        return message;
    }

    /**
     * Helper method to add a default value for a placeholder if it doesn't exist
     */
    private void addDefaultIfMissing(Map<String, String> placeholders, String key, String defaultValue) {
        if (!placeholders.containsKey(key)) {
            placeholders.put(key, defaultValue);
        }
    }

    /**
     * Gets a message with no placeholders
     */
    public String get(String path) {
        return get(path, null);
    }

    /**
     * Gets a raw message without prefix
     */
    public String getRaw(String path) {
        // Just get the string directly, no prefix handling
        return getString(path);
    }

    /**
     * Gets a protection message with area name placeholder
     */
    public String getProtectionMessage(String path, String areaName) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("area", areaName != null ? areaName : "unknown area");
        
        // Add common placeholders that might be in protection messages
        Area area = plugin.getArea(areaName);
        if (area != null) {
            placeholders.put("world", area.getWorld());
            placeholders.put("priority", String.valueOf(area.getPriority()));
        } else {
            placeholders.put("world", "unknown world");
            placeholders.put("priority", "0");
        }
        
        // Note: the prefix will be automatically added by the get() method
        return get("protection." + path, placeholders);
    }

    /**
     * Gets a protection message with area name placeholder and player's actual world
     */
    public String getProtectionMessage(String path, String areaName, Player player) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("area", areaName != null ? areaName : "unknown area");
        
        // Add common placeholders that might be in protection messages
        Area area = plugin.getArea(areaName);
        if (area != null) {
            // Use player's current world if available, otherwise fall back to area world
            if (player != null && player.getLevel() != null) {
                placeholders.put("world", player.getLevel().getName());
            } else {
                placeholders.put("world", area.getWorld());
            }
            placeholders.put("priority", String.valueOf(area.getPriority()));
        } else {
            // Use player's world if available
            if (player != null && player.getLevel() != null) {
                placeholders.put("world", player.getLevel().getName());
            } else {
                placeholders.put("world", "unknown world");
            }
            placeholders.put("priority", "0");
        }
        
        // Note: the prefix will be automatically added by the get() method
        return get("protection." + path, placeholders);
    }

    /**
     * Gets a message specifically for area entry/exit with both area and player placeholders
     * 
     * @param path The message path
     * @param areaName The name of the area
     * @param playerName The name of the player
     * @return The formatted message with all placeholders replaced
     */
    public String getAreaTransitionMessage(String path, String areaName, String playerName) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("area", areaName != null ? areaName : "unknown area");
        placeholders.put("player", playerName != null ? playerName : "unknown player");
        
        // Add area-specific placeholders if area exists
        Area area = plugin.getArea(areaName);
        if (area != null) {
            placeholders.put("world", area.getWorld());
            placeholders.put("priority", String.valueOf(area.getPriority()));
            
            // If there's a player with this name, get their actual world
            Player player = plugin.getServer().getPlayerExact(playerName);
            if (player != null) {
                placeholders.put("world", player.getLevel().getName());
            }
            
            // Calculate area size using the getBounds method
            try {
                AreaDTO.Bounds bounds = area.getBounds();
                if (bounds != null) {
                    // Calculate volume (size) of the area
                    long sizeX = Math.abs(bounds.xMax() - bounds.xMin()) + 1;
                    long sizeY = Math.abs(bounds.yMax() - bounds.yMin()) + 1;
                    long sizeZ = Math.abs(bounds.zMax() - bounds.zMin()) + 1;
                    
                    // For global areas, just show "global" to avoid overflow
                    if (area.isGlobal()) {
                        placeholders.put("size", "global");
                    } else {
                        // Cap size display at a reasonable value to avoid integer overflow
                        long volume = Math.min(sizeX * sizeY * sizeZ, Long.MAX_VALUE);
                        placeholders.put("size", String.valueOf(volume));
                    }
                } else {
                    placeholders.put("size", "unknown");
                }
            } catch (Exception e) {
                logger.debug("Could not calculate size for area %s: %s", areaName, e.getMessage());
                placeholders.put("size", "error");
            }
        } else {
            // If area is null, get world from player if possible
            Player player = plugin.getServer().getPlayerExact(playerName);
            if (player != null) {
                placeholders.put("world", player.getLevel().getName());
            } else {
                placeholders.put("world", "unknown world");
            }
            
            placeholders.put("priority", "0");
            placeholders.put("size", "0");
        }
        
        // Time is automatically added in the get method
        
        // Check if the path is an actual language key or a raw message
        String message;
        // First, check if this is a language key format (likely from config.yml)
        if (path.startsWith("titles.") || config.exists(path)) {
            // It's a language key path, get the translated message
            // For area transitions, don't include the prefix
            message = getRaw(path);
            if (message == null) {
                // If language key doesn't exist, use the path as the message
                message = path;
            }
            // Replace placeholders directly 
            message = replacePlaceholders(message, placeholders);
        } else {
            // It's a raw message with placeholders that needs direct replacement
            message = replacePlaceholders(path, placeholders);
            // Don't add prefix for area transition messages
        }
        
        return message;
    }
    
    /**
     * Gets current time formatted for messages
     * Uses configurable format and timezone if specified in config
     */
    private String getCurrentTimeFormatted() {
        try {
            String timeFormat = config.getString("timeFormat", "HH:mm:ss");
            String timezone = config.getString("timezone", "");
            
            java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat(timeFormat);
            
            // Set timezone if specified
            if (!timezone.isEmpty()) {
                dateFormat.setTimeZone(java.util.TimeZone.getTimeZone(timezone));
            }
            
            return dateFormat.format(new java.util.Date());
        } catch (Exception e) {
            // Fallback to basic format if any error occurs
            return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        }
    }

    private String getString(String path) {
        return getStringWithDepth(path, 0);
    }
    
    private String getStringWithDepth(String path, int depth) {
        // Prevent excessive recursion
        if (depth > 3) {
            logger.debug("Excessive recursion detected when getting string: %s (depth: %d)", path, depth);
            return null;
        }
        
        if (path == null || path.isEmpty()) {
            return null;
        }
        
        // Check cache first
        String cached = messageCache.get(path);
        if (cached != null) {
            return cached;
        }

        // Try direct path
        if (config.exists(path)) {
            String value = config.getString(path);
            cacheMessage(path, value);
            return value;
        }

        // Try alternate paths - but only once, with a "trying_alternate" flag to prevent recursion
        if (!path.startsWith("trying_alternate:")) {
            String alternate = tryAlternateKey("trying_alternate:" + path);
            if (alternate != null) {
                cacheMessage(path, alternate);
                return alternate;
            }
        }

        // Try traversing path parts
        String[] parts = path.split("\\.");
        Object current = config.getRootSection();

        for (String part : parts) {
            if (!(current instanceof Map)) {
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) current;
            current = map.get(part);
        }

        if (current instanceof String) {
            String value = (String) current;
            cacheMessage(path, value);
            return value;
        }

        return null;
    }

    private void cacheMessage(String path, String message) {
        if (messageCache.size() < CACHE_SIZE_LIMIT) {
            messageCache.put(path, message);
        }
    }

    protected String replacePlaceholders(String message, Map<String, String> placeholders) {
        if (message == null || placeholders == null || placeholders.isEmpty()) {
            return message;
        }

        StringBuilder result = new StringBuilder(message);
        Matcher matcher = placeholderPattern.matcher(message);
        
        boolean isDebug = plugin.isDebugMode();
        if (isDebug) {
            logger.debug("Replacing placeholders in message: \"%s\"", message);
            logger.debug("Available placeholders: %s", placeholders.keySet());
        }
        
        int replacements = 0;
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String value = placeholders.get(placeholder);
            
            if (value != null) {
                if (isDebug) {
                    logger.debug("  Replacing {%s} with \"%s\"", placeholder, value);
                }
                
                int start = matcher.start();
                int end = matcher.end();
                result.replace(start, end, value);
                matcher.reset(result);
                replacements++;
            } else if (isDebug) {
                logger.debug("  No value found for placeholder {%s}", placeholder);
            }
        }
        
        if (isDebug && replacements > 0) {
            logger.debug("Final message after %d replacements: \"%s\"", replacements, result.toString());
        }

        return result.toString();
    }

    private String buildPlaceholderCacheKey(String path, Map<String, String> placeholders) {
        StringBuilder key = new StringBuilder(path);
        // Sort placeholders by key for consistent cache keys
        List<Map.Entry<String, String>> entries = new ArrayList<>(placeholders.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        
        for (Map.Entry<String, String> entry : entries) {
            key.append(':').append(entry.getKey()).append('=').append(entry.getValue());
        }
        return key.toString();
    }

    /**
     * Attempts to find alternate keys for common message patterns
     */
    private String tryAlternateKey(String path) {
        // Remove the recursion prevention prefix if it exists
        String realPath = path.startsWith("trying_alternate:") ? path.substring("trying_alternate:".length()) : path;
        
        // Check common prefix variations
        String[] prefixes = {"messages.", "protection.", "gui.", "commands.", "errors."};
        for (String prefix : prefixes) {
            // Skip if the path already starts with this prefix to avoid recursion
            if (!realPath.startsWith(prefix)) {
                // Direct config access to avoid recursion
                String alternatePath = prefix + realPath;
                if (config.exists(alternatePath)) {
                    return config.getString(alternatePath);
                }
            }
        }
        return null;
    }

    private void logMissingKey(String path) {
        if (!reportedMissingKeys.contains(path)) {
            logger.warn("Missing language key: %s", path);
            reportedMissingKeys.add(path);
        }
    }

    /**
     * Gets statistics about language key usage
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalKeys", config.getRootSection().getAllMap().size());
        stats.put("cachedMessages", messageCache.size());
        stats.put("cachedCompiled", compiledMessageCache.size());
        stats.put("missingKeys", new ArrayList<>(reportedMissingKeys));
        stats.put("unusedKeys", getUnusedKeys());
        stats.put("mostUsed", getMostUsedKeys(10));
        return stats;
    }

    private List<String> getUnusedKeys() {
        return getAllKeys().stream()
            .filter(key -> !usedKeys.contains(key))
            .collect(Collectors.toList());
    }

    private List<Map.Entry<String, Integer>> getMostUsedKeys(int limit) {
        return keyUsageCount.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(limit)
            .collect(Collectors.toList());
    }

    private Set<String> getAllKeys() {
        return getAllKeys(config.getRootSection(), "");
    }

    private Set<String> getAllKeys(Map<String, Object> section, String prefix) {
        Set<String> keys = new HashSet<>();
        section.forEach((key, value) -> {
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> subSection = (Map<String, Object>) value;
                keys.addAll(getAllKeys(subSection, fullKey));
            } else {
                keys.add(fullKey);
            }
        });
        return keys;
    }

    /**
     * Reloads the language configuration
     */
    public void reload() {
        // Clear all caches first
        clearCaches();
        
        // Load language file again
        loadLanguage();
        
        logger.info("Language configuration reloaded successfully");
    }

    /**
     * Clears all caches
     */
    public void clearCaches() {
        messageCache.clear();
        compiledMessageCache.clear();
        reportedMissingKeys.clear();
        usedKeys.clear();
        keyUsageCount.clear();
        
        logger.debug("Language caches cleared");
    }

    private boolean checkPlayerSpecificPermissions(Player player, Area area, String normalizedPermission, String cacheKey) {
        Map<String, Map<String, Boolean>> playerPermissions = area.getPlayerPermissions();
        Map<String, Boolean> playerPerms = playerPermissions.get(player.getName());
        
        if (plugin.isDebugMode()) {
            plugin.debug("[PermissionChecker] Player permissions map: " + 
                       (playerPerms != null ? playerPerms.toString() : "null"));
        }
        
        if (playerPerms != null && playerPerms.containsKey(normalizedPermission)) {
            boolean playerAllowed = playerPerms.get(normalizedPermission);
            if (plugin.isDebugMode()) {
                plugin.debug("[PermissionChecker] Found player-specific permission: " + playerAllowed);
                plugin.debug("[PermissionChecker] DECISION: " + playerAllowed + " - storing in cache and returning");
            }
            permissionCache.put(cacheKey, playerAllowed);
            return true;
        }
        
        return false;
    }
}
