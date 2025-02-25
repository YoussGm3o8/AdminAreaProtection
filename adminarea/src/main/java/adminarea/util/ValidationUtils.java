package adminarea.util;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.level.Position;

import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ValidationUtils {
    private static final Pattern AREA_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,32}$");
    private static final int MIN_PRIORITY = 0;
    private static final int MAX_PRIORITY = 100;
    
    // Cache common patterns to avoid recompilation
    private static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>(8);
    
    // Toggle dependency map
    private static final Map<String, List<String>> TOGGLE_DEPENDENCIES;
    static {
        Map<String, List<String>> deps = new HashMap<>();
        deps.put("container", Collections.singletonList("interact")); 
        deps.put("itemFrame", Collections.singletonList("interact"));
        deps.put("armorStand", Collections.singletonList("interact"));
        deps.put("hopper", Collections.singletonList("redstone"));
        deps.put("dispenser", Collections.singletonList("redstone"));
        deps.put("piston", Collections.singletonList("redstone")); 
        deps.put("breeding", Collections.singletonList("interact"));
        TOGGLE_DEPENDENCIES = Collections.unmodifiableMap(deps);
    }
    // Toggle conflict map (made unmodifiable for safety)
    private static final Map<String, List<String>> TOGGLE_CONFLICTS;
    static {
        Map<String, List<String>> conflicts = new HashMap<>();
        conflicts.put("pvp", Collections.singletonList("damageEntities"));
        conflicts.put("monsterSpawn", Collections.singletonList("peaceful"));
        conflicts.put("fire", Collections.singletonList("fireProtection"));
        TOGGLE_CONFLICTS = Collections.unmodifiableMap(conflicts);
    }

    // Cache of valid toggle names for faster lookups
    private static Set<String> validToggles;
    static {
        initValidToggles();
    }
    
    private static void initValidToggles() {
        validToggles = PermissionToggle.getTogglesByCategory().values().stream()
            .flatMap(List::stream)
            .map(PermissionToggle::getPermissionNode)
            .collect(Collectors.toSet());
    }

    public static void validateAreaName(String name) {
        if (name == null || !AREA_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid area name. Use only letters, numbers, hyphens, and underscores (3-32 characters).");
        }
    }

    public void validateCoordinates(int x, int y, int z) {
        if (y < -64 || y > 320) {
            throw new IllegalArgumentException("Y coordinate must be between -64 and 320");
        }
        // X and Z coordinates have much larger bounds in Minecraft
        if (x < -30000000 || x > 30000000) {
            throw new IllegalArgumentException("X coordinate must be between -30000000 and 30000000"); 
        }
        if (z < -30000000 || z > 30000000) {
            throw new IllegalArgumentException("Z coordinate must be between -30000000 and 30000000");
        }
    }

    public static void validatePriority(int priority) {
        if (priority < MIN_PRIORITY || priority > MAX_PRIORITY) {
            throw new IllegalArgumentException("Priority must be between " + MIN_PRIORITY + " and " + MAX_PRIORITY);
        }
    }

    // Optimized validation by avoiding redundant string checks
    public static void validateAreaSettings(JSONObject settings) {
        if (settings == null) {
            throw new IllegalArgumentException("Settings cannot be null");
        }

        // Cache keys to avoid calling settings.keySet() multiple times
        Set<String> keys = settings.keySet();

        // Validate each toggle setting
        for (String key : keys) {
            if (!isValidToggle(key)) {
                throw new IllegalArgumentException("Invalid toggle setting: " + key);
            }
            
            // Check that value is boolean
            if (!settings.isNull(key) && !(settings.get(key) instanceof Boolean)) {
                throw new IllegalArgumentException("Toggle value must be boolean: " + key);
            }
        }

        // Validate toggle dependencies and conflicts in a single pass through settings
        validateToggleRelations(settings);
    }

    public static boolean isValidToggle(String toggleName) {
        // Use cached set of valid toggle names
        return validToggles.contains(toggleName);
    }

    // Combined method to validate both dependencies and conflicts in one pass
    private static void validateToggleRelations(JSONObject settings) {
        // Check all enabled toggles for dependencies and conflicts
        for (String toggle : settings.keySet()) {
            if (settings.optBoolean(toggle, false)) {
                // Check dependencies
                List<String> dependencies = TOGGLE_DEPENDENCIES.get(toggle);
                if (dependencies != null) {
                    for (String dependency : dependencies) {
                        if (!settings.optBoolean(dependency, false)) {
                            throw new IllegalArgumentException(
                                "Toggle '" + toggle + "' requires '" + dependency + "' to be enabled");
                        }
                    }
                }
                
                // Check conflicts
                List<String> conflicts = TOGGLE_CONFLICTS.get(toggle);
                if (conflicts != null) {
                    for (String conflict : conflicts) {
                        if (settings.optBoolean(conflict, false)) {
                            throw new IllegalArgumentException(
                                "Toggle '" + toggle + "' conflicts with '" + conflict + "'");
                        }
                    }
                }
            }
        }
    }

    public static void validateToggleDependencies(JSONObject settings) {
        for (Map.Entry<String, List<String>> entry : TOGGLE_DEPENDENCIES.entrySet()) {
            String toggle = entry.getKey();
            if (settings.has(toggle) && settings.getBoolean(toggle)) {
                // If a toggle is enabled, check its dependencies
                for (String dependency : entry.getValue()) {
                    if (!settings.has(dependency) || !settings.getBoolean(dependency)) {
                        throw new IllegalArgumentException(
                            "Toggle '" + toggle + "' requires '" + dependency + "' to be enabled");
                    }
                }
            }
        }
    }

    public static void validateToggleConflicts(JSONObject settings) {
        for (Map.Entry<String, List<String>> entry : TOGGLE_CONFLICTS.entrySet()) {
            String toggle = entry.getKey();
            if (settings.has(toggle) && settings.getBoolean(toggle)) {
                // If a toggle is enabled, check for conflicts
                for (String conflict : entry.getValue()) {
                    if (settings.has(conflict) && settings.getBoolean(conflict)) {
                        throw new IllegalArgumentException(
                            "Toggle '" + toggle + "' conflicts with '" + conflict + "'");
                    }
                }
            }
        }
    }

    public static List<String> getDependencies(String toggle) {
        return TOGGLE_DEPENDENCIES.getOrDefault(toggle, Collections.emptyList());
    }

    public static List<String> getConflicts(String toggle) {
        return TOGGLE_CONFLICTS.getOrDefault(toggle, Collections.emptyList());
    }

    public static boolean hasDependencies(String toggle) {
        return TOGGLE_DEPENDENCIES.containsKey(toggle);
    }

    public static boolean hasConflicts(String toggle) {
        return TOGGLE_CONFLICTS.containsKey(toggle);
    }

    public static void validateToggleState(String toggle, boolean newState, JSONObject currentSettings) {
        // If enabling toggle
        if (newState) {
            // Check dependencies
            List<String> deps = TOGGLE_DEPENDENCIES.get(toggle);
            if (deps != null) {
                for (String dep : deps) {
                    if (!currentSettings.optBoolean(dep, false)) {
                        throw new IllegalArgumentException(
                            "Toggle '" + toggle + "' requires '" + dep + "' to be enabled");
                    }
                }
            }

            // Check conflicts  
            List<String> conflicts = TOGGLE_CONFLICTS.get(toggle);
            if (conflicts != null) {
                for (String conflict : conflicts) {
                    if (currentSettings.optBoolean(conflict, false)) {
                        throw new IllegalArgumentException(
                            "Toggle '" + toggle + "' conflicts with enabled toggle '" + conflict + "'");
                    }
                }
            }
        }
        // If disabling toggle
        else {
            // Check if any enabled toggles depend on this
            for (Map.Entry<String, List<String>> entry : TOGGLE_DEPENDENCIES.entrySet()) {
                if (entry.getValue().contains(toggle) &&
                    currentSettings.optBoolean(entry.getKey(), false)) {
                    throw new IllegalArgumentException(
                        "Cannot disable '" + toggle + "' because '" + entry.getKey() + "' depends on it");
                }
            }
        }
    }

    public static void validateString(String value, int minLength, int maxLength, String pattern, String fieldName) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be null or empty");
        }
        if (value.length() < minLength || value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " length must be between " + minLength + " and " + maxLength);
        }
        if (!Pattern.compile(pattern).matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " contains invalid characters");
        }
    }

    public static void validatePermission(String permission) {
        if (permission == null || permission.isEmpty()) {
            throw new IllegalArgumentException("Permission cannot be null or empty");
        }
        if (!Pattern.compile("^[a-z]+(\\.[a-z]+)*$").matcher(permission).matches()) {
            throw new IllegalArgumentException("Invalid permission format. Use lowercase letters and dots only");
        }
    }

    /**
     * Validates a map of permissions
     */
    public static void validatePermissionMap(Map<String, Boolean> permissions) {
        if (permissions == null) {
            throw new IllegalArgumentException("Permission map cannot be null");
        }
        
        for (String permission : permissions.keySet()) {
            validatePermission(permission);
        }
    }

    /**
     * Validates a category of permissions
     */
    public static void validateCategoryPermissions(PermissionToggle.Category category, 
            Map<String, Boolean> permissions) {
        validatePermissionMap(permissions);
        
        // Verify permissions belong to category
        List<PermissionToggle> categoryToggles = PermissionToggle.getTogglesByCategory().get(category);
        Set<String> validNodes = categoryToggles.stream()
            .map(PermissionToggle::getPermissionNode)
            .collect(Collectors.toSet());
            
        for (String permission : permissions.keySet()) {
            if (!validNodes.contains(permission)) {
                throw new IllegalArgumentException(
                    "Permission " + permission + " does not belong to category " + category);
            }
        }
    }

    public static void validateNumericRange(int value, int min, int max, String fieldName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " must be between " + min + " and " + max);
        }
    }

    public void validatePositions(Position[] positions) {
        if (positions == null || positions.length != 2 || positions[0] == null || positions[1] == null) {
            throw new IllegalArgumentException("Invalid positions array");
        }
        
        // Fix: Add proper world validation
        if (!positions[0].getLevel().getName().equals(positions[1].getLevel().getName())) {
            throw new IllegalArgumentException("Positions must be in the same world");
        }
    }

    public void validatePlayerState(Player player, String action) {
        if (player == null) {
            throw new IllegalArgumentException("Player cannot be null");
        }
        if (!player.isOnline()) {
            throw new IllegalStateException("Player must be online");
        }
    }

    public void validateCrossFields(Map<String, String> fields1, Map<String, String> fields2) {
        if (fields1 == null || fields2 == null) {
            throw new IllegalArgumentException("Field maps cannot be null");
        }
    }

    public String sanitizeFormInput(String input, int maxLength) {
        if (input == null) {
            return "";
        }
        // Fix: Trim properly
        String trimmed = input.trim(); 
        if (trimmed.length() > maxLength) {
            return trimmed.substring(0, maxLength);
        }
        return trimmed;
    }
}
