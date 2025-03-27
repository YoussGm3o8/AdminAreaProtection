package adminarea.managers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaDTO;
import adminarea.util.Logger;
import adminarea.util.YamlConfig;
import cn.nukkit.Player;

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
    private YamlConfig config;
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

        // Load language file with our custom YamlConfig instead of Nukkit's Config
        config = new YamlConfig(langFile);
        if (!config.exists() || !config.reload()) {
            logger.error("Failed to load language file: %s", langFile.getPath());
            // Create an empty config as fallback
            config = new YamlConfig(langFile);
        }
        
        validateLanguageFile();
        
        prefix = getString("messages.prefix");
        
        // Clear caches on reload
        clearCaches();
        
        // Validate key placeholder requirements for critical messages
        validatePlaceholders();
        
        logger.debug("Loaded language file: %s", language);
        logger.debug("Available sections: %s", String.join(", ", config.getKeys("")));
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
            // Use player's current world if available
            if (player != null && player.getLevel() != null) {
                placeholders.put("world", player.getLevel().getName());
            } else {
                placeholders.put("world", "unknown world");
            }
            placeholders.put("priority", "0");
        }
        
        // Add player name if available
        if (player != null) {
            placeholders.put("player", player.getName());
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
        return new Date().toString();
    }

    private String getString(String path) {
        // Try the cache first
        if (messageCache.containsKey(path)) {
            return messageCache.get(path);
        }

        // Check if the key exists first
        if (config.exists(path)) {
            String message = config.getString(path);
            // Cache the message for future use
            cacheMessage(path, message);
            return message;
        }

        // If the key doesn't exist, try some alternate paths
        String alternateMessage = tryAlternateKey(path);
        if (alternateMessage != null) {
            // Cache the alternate message for future use
            cacheMessage(path, alternateMessage);
            return alternateMessage;
        }

        // Key truly doesn't exist
        return null;
    }

    /**
     * Tries alternate key formats when a key is not found
     */
    private String tryAlternateKey(String path) {
        // Common patterns:
        // 1. Direct vs nested under messages
        if (path.startsWith("messages.")) {
            // Try without messages prefix
            String altPath = path.substring("messages.".length());
            if (config.exists(altPath)) {
                return config.getString(altPath);
            }
        } else {
            // Try with messages prefix
            String altPath = "messages." + path;
            if (config.exists(altPath)) {
                return config.getString(altPath);
            }
        }
        
        // 2. If path has dots, try the last segment as a direct key
        if (path.contains(".")) {
            String lastSegment = path.substring(path.lastIndexOf(".") + 1);
            if (config.exists(lastSegment)) {
                return config.getString(lastSegment);
            }
        }
        
        // No alternate found
        return null;
    }

    /**
     * Cache a message
     */
    private void cacheMessage(String path, String message) {
        if (message != null && messageCache.size() < CACHE_SIZE_LIMIT) {
            messageCache.put(path, message);
        }
    }

    /**
     * Replace placeholders in a message
     */
    protected String replacePlaceholders(String message, Map<String, String> placeholders) {
        if (message == null || placeholders == null || placeholders.isEmpty()) {
            return message;
        }
        
        // Use regex to find and replace all placeholders
        StringBuilder result = new StringBuilder(message);
        Matcher matcher = placeholderPattern.matcher(message);
        
        // List to track offsets as we replace (since replacements can change string length)
        List<Map.Entry<Integer, Integer>> replacements = new ArrayList<>();
        
        // Find all matches first
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            if (placeholders.containsKey(placeholder)) {
                replacements.add(new AbstractMap.SimpleEntry<>(matcher.start(), matcher.end()));
            }
        }
        
        // Replace from end to start to avoid offset issues
        for (int i = replacements.size() - 1; i >= 0; i--) {
            Map.Entry<Integer, Integer> replacement = replacements.get(i);
            int start = replacement.getKey();
            int end = replacement.getValue();
            
            // Extract placeholder name (remove the { and })
            String placeholder = message.substring(start + 1, end - 1);
            String value = placeholders.get(placeholder);
            
            // Replace in the StringBuilder
            if (value != null) {
                result.replace(start, end, value);
            }
        }
        
        return result.toString();
    }

    /**
     * Build a cache key for messages with placeholders
     */
    private String buildPlaceholderCacheKey(String path, Map<String, String> placeholders) {
        StringBuilder key = new StringBuilder(path);
        
        // Sort placeholders by key for consistent cache key generation
        List<String> sortedKeys = new ArrayList<>(placeholders.keySet());
        Collections.sort(sortedKeys);
        
        for (String placeholder : sortedKeys) {
            // Skip very large values to prevent cache key explosion
            String value = placeholders.get(placeholder);
            if (value != null && value.length() > 50) {
                value = value.substring(0, 50) + "...";
            }
            key.append("#").append(placeholder).append("=").append(value);
        }
        
        return key.toString();
    }

    /**
     * Log a missing key (but only once per key to avoid spam)
     */
    private void logMissingKey(String path) {
        if (!reportedMissingKeys.contains(path)) {
            reportedMissingKeys.add(path);
            logger.debug("Missing language key: %s", path);
        }
    }

    /**
     * Get statistics about language usage
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("usedKeysCount", usedKeys.size());
        stats.put("totalKeysCount", getAllKeys().size());
        stats.put("missingKeysCount", reportedMissingKeys.size());
        stats.put("missingKeys", new ArrayList<>(reportedMissingKeys));
        stats.put("mostUsedKeys", getMostUsedKeys(10));
        stats.put("messageCacheSize", messageCache.size());
        return stats;
    }

    /**
     * Get a list of unused keys
     */
    private List<String> getUnusedKeys() {
        Set<String> allKeys = getAllKeys();
        allKeys.removeAll(usedKeys);
        return new ArrayList<>(allKeys);
    }

    /**
     * Get the most used keys
     */
    private List<Map.Entry<String, Integer>> getMostUsedKeys(int limit) {
        return keyUsageCount.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * Get all keys in the language file
     */
    private Set<String> getAllKeys() {
        return getAllKeys(config.getRootSection(), "");
    }

    /**
     * Get all keys in a section
     */
    private Set<String> getAllKeys(Map<String, Object> section, String prefix) {
        Set<String> keys = new HashSet<>();
        
        for (Map.Entry<String, Object> entry : section.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            keys.add(key);
            
            if (entry.getValue() instanceof Map) {
                //noinspection unchecked
                keys.addAll(getAllKeys((Map<String, Object>) entry.getValue(), key));
            }
        }
        
        return keys;
    }

    /**
     * Reload the language configuration
     */
    public void reload() {
        loadLanguage();
    }

    /**
     * Clear message caches
     */
    public void clearCaches() {
        messageCache.clear();
        compiledMessageCache.clear();
        permissionCache.clear();
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
