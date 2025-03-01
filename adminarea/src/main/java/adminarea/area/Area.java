package adminarea.area;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.permissions.PermissionToggle;
import adminarea.exception.DatabaseException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cn.nukkit.math.Vector3;
import cn.nukkit.math.SimpleAxisAlignedBB;
import cn.nukkit.level.Position;
import org.json.JSONObject;

public final class Area {
    private final AreaDTO dto;
    private final Map<String, Boolean> effectivePermissionCache;
    private final AreaPermissionHandler permissionHandler;
    private final String name;
    private final String world;
    private final Vector3 pos1;
    private final Vector3 pos2;
    private final int priority;
    private final Map<String, Object> toggleStates;
    private final SimpleAxisAlignedBB boundingBox;
    private final Cache<String, Boolean> containsCache;
    // Increased cache size for better hit rate
    private static final int CONTAINS_CACHE_SIZE = 1000;
    private final AdminAreaProtectionPlugin plugin;
    
    // Common string constants to avoid repeated construction
    private static final String GUI_PERMISSIONS_PREFIX = "gui.permissions.toggles.";
    
    private final Map<String, Map<String, Boolean>> groupPermissions;
    private final Map<String, Map<String, Boolean>> trackPermissions;
    private final Map<String, Map<String, Boolean>> playerPermissions;
    private Map<String, Map<String, Boolean>> cachedPlayerPermissions;
    
    // Cache for toggle state lookups
    private final Cache<String, Boolean> toggleStateCache;
    private static final int TOGGLE_CACHE_SIZE = 200;
    
    // Store potion effects
    private final JSONObject potionEffects;
    
    Area(AreaDTO dto) {
        this.dto = dto;
        this.plugin = AdminAreaProtectionPlugin.getInstance();
        this.effectivePermissionCache = new ConcurrentHashMap<>(32, 0.75f, 2);
        this.permissionHandler = new AreaPermissionHandler(dto.groupPermissions(), dto.inheritedPermissions());
        this.name = dto.name();
        this.world = dto.world();
        
        // Get positions from bounds
        AreaDTO.Bounds bounds = dto.bounds();
        this.pos1 = new Vector3(bounds.xMin(), bounds.yMin(), bounds.zMin());
        this.pos2 = new Vector3(bounds.xMax(), bounds.yMax(), bounds.zMax());
        this.priority = dto.priority();
        
        // Convert toggle states
        this.toggleStates = new ConcurrentHashMap<>(32, 0.75f, 1);
        JSONObject toggles = dto.toggleStates();
        for (String key : toggles.keySet()) {
            try {
                // Normalize key during initialization
                String normalizedKey = normalizeToggleKey(key);
                
                // Handle strength settings (which are integers) differently from toggle settings (which are booleans)
                if (key.endsWith("Strength") || normalizedKey.endsWith("Strength")) {
                    // This is a strength setting, store it as an integer
                    this.toggleStates.put(normalizedKey, toggles.optInt(key, 0));
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Loaded strength setting: " + normalizedKey + " = " + toggles.optInt(key, 0));
                    }
                } else {
                    // This is a regular toggle setting, store it as a boolean
                    this.toggleStates.put(normalizedKey, toggles.optBoolean(key, false));
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Loaded toggle setting: " + normalizedKey + " = " + toggles.optBoolean(key, false));
                    }
                }
            } catch (Exception e) {
                if (plugin.isDebugMode()) {
                    plugin.debug("Error loading toggle: " + key + " - " + e.getMessage());
                }
                // Use a safe default if there's an error
                String normalizedKey = normalizeToggleKey(key);
                if (key.endsWith("Strength") || normalizedKey.endsWith("Strength")) {
                    this.toggleStates.put(normalizedKey, 0);
                } else {
                    this.toggleStates.put(normalizedKey, false);
                }
            }
        }
        
        // Load potion effects
        this.potionEffects = dto.potionEffects() != null ? dto.potionEffects() : new JSONObject();
        
        if (plugin.isDebugMode()) {
            plugin.debug("Loaded potion effects for area " + name + ": " + this.potionEffects.toString());
        }
        
        // Create bounding box for faster contains checks
        this.boundingBox = new SimpleAxisAlignedBB(
            bounds.xMin(), bounds.yMin(), bounds.zMin(),
            bounds.xMax() + 1, bounds.yMax() + 1, bounds.zMax() + 1
        );
        
        // Initialize contains cache with longer expiry for stable positions
        this.containsCache = Caffeine.newBuilder()
            .maximumSize(CONTAINS_CACHE_SIZE)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();
            
        // Initialize toggle state cache
        this.toggleStateCache = Caffeine.newBuilder()
            .maximumSize(TOGGLE_CACHE_SIZE)
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build();

        // Initialize permission maps from DTO
        this.groupPermissions = new ConcurrentHashMap<>(dto.groupPermissions());
        this.trackPermissions = new ConcurrentHashMap<>(dto.trackPermissions());
        this.playerPermissions = new ConcurrentHashMap<>(dto.playerPermissions());

        if (plugin.isDebugMode()) {
            plugin.debug("Created area " + name + ":");
            plugin.debug("  Player permissions from DTO: " + dto.playerPermissions());
            plugin.debug("  Player permissions in area: " + playerPermissions);
            plugin.debug("  Group permissions in area: " + groupPermissions);
            plugin.debug("  Track permissions in area: " + trackPermissions);
        }
    }

    public static AreaBuilder builder() {
        return new AreaBuilder();
    }

    /**
     * Fast check to see if coordinates are inside this area
     * Uses simplified bounds check for better performance
     */
    public boolean isInside(String world, double x, double y, double z) {
        // Fast world name check first
        if (!this.world.equals(world)) return false;
        
        // Fast path for global areas - avoid unnecessary bounds checks
        if (isGlobal()) {
            return true; // Global areas contain all points in their world
        }
        
        // Use a smaller epsilon for more precise boundary detection
        final double EPSILON = 0.0001;
        AreaDTO.Bounds bounds = dto.bounds();
        
        // Add a small buffer to ensure points very close to the boundary
        // are still considered inside the area
        boolean isInside = x >= (bounds.xMin() - EPSILON) && x <= (bounds.xMax() + EPSILON) &&
                         y >= (bounds.yMin() - EPSILON) && y <= (bounds.yMax() + EPSILON) &&
                         z >= (bounds.zMin() - EPSILON) && z <= (bounds.zMax() + EPSILON);
        
        // Check if point is near a boundary (for debugging)
        boolean isEdgeCase = Math.abs(x - bounds.xMin()) < EPSILON || 
                          Math.abs(x - bounds.xMax()) < EPSILON ||
                          Math.abs(z - bounds.zMin()) < EPSILON ||
                          Math.abs(z - bounds.zMax()) < EPSILON;
        
        // Log detailed information for edge cases
        if (isEdgeCase && plugin.isDebugMode()) {
            plugin.debug("EDGE CASE boundary check for area " + name + ":");
            plugin.debug("  Position: (" + x + ", " + y + ", " + z + ")");
            plugin.debug("  Bounds: (" + bounds.xMin() + "-" + bounds.xMax() + ", " + 
                                     bounds.yMin() + "-" + bounds.yMax() + ", " + 
                                     bounds.zMin() + "-" + bounds.zMax() + ")");
            plugin.debug("  Result: " + isInside);
        }
        
        return isInside;
    }

    /**
     * Checks if this is a global area (covers an entire world)
     * Global areas have extreme bounds that cover the entire world
     * 
     * @return true if this is a global area
     */
    public boolean isGlobal() {
        AreaDTO.Bounds bounds = dto.bounds();
        // Global areas have extreme bounds
        return bounds.xMin() <= -29000000 && bounds.xMax() >= 29000000 &&
               bounds.zMin() <= -29000000 && bounds.zMax() >= 29000000;
    }

    /**
     * Get effective permission for a group
     * Uses caching for better performance
     */
    public boolean getEffectivePermission(String group, String permission) {
        if (group == null || permission == null) return false;
        
        String cacheKey = group + ":" + permission;
        return effectivePermissionCache.computeIfAbsent(cacheKey,
            k -> permissionHandler.calculateEffectivePermission(group, permission));
    }

    /**
     * Normalizes a toggle key to ensure consistent format
     */
    private String normalizeToggleKey(String key) {
        if (key == null || key.isEmpty()) return GUI_PERMISSIONS_PREFIX + "default";
        
        // Already has prefix
        if (key.startsWith(GUI_PERMISSIONS_PREFIX)) {
            return key;
        }
        
        // Simple permission without dots
        if (!key.contains(".")) {
            return GUI_PERMISSIONS_PREFIX + key;
        }
        
        // Return as is for special permission types
        return key;
    }

    /**
     * Sets a toggle state with proper key normalization and immediate database update
     */
    public void setToggleState(String permission, boolean state) {
        String normalizedPermission = normalizeToggleKey(permission);
        
        // Update cache and storage
        toggleStates.put(normalizedPermission, state);
        toggleStateCache.invalidateAll();
        
        // We don't need to update settings in DTO since it's not stored in the database
        // Just keep the toggle states updated in memory
        
        // Update database asynchronously
        try {
            plugin.getDatabaseManager().updateAreaToggleState(name, normalizedPermission, state);
        } catch (Exception e) {
            plugin.getLogger().error("Failed to update toggle state in database", e);
        }
        
        // Invalidate protection cache in ProtectionListener
        if (plugin.getListenerManager() != null && 
            plugin.getListenerManager().getProtectionListener() != null) {
            // Cleanup will invalidate both protection listener cache and permission checker cache
            plugin.getListenerManager().getProtectionListener().cleanup();
            
            // Also invalidate environment listener cache
            if (plugin.getListenerManager().getEnvironmentListener() != null) {
                plugin.getListenerManager().getEnvironmentListener().invalidateCache();
            }
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("Setting toggle state for " + normalizedPermission + " to " + state + " in area " + name);
        }
    }

    /**
     * Sets a toggle state with proper key normalization for integer values
     */
    public void setToggleState(String permission, int value) {
        String normalizedPermission = normalizeToggleKey(permission);
        
        // Update cache and storage
        toggleStates.put(normalizedPermission, value);
        toggleStateCache.invalidateAll();
        
        // We don't need to update settings in DTO since it's not stored in the database
        // Just keep the toggle states updated in memory
        
        // Update database asynchronously
        try {
            plugin.getDatabaseManager().updateAreaToggleState(name, normalizedPermission, value);
        } catch (Exception e) {
            plugin.getLogger().error("Failed to update toggle state in database", e);
        }
        
        // Invalidate protection cache in ProtectionListener
        if (plugin.getListenerManager() != null && 
            plugin.getListenerManager().getProtectionListener() != null) {
            plugin.getListenerManager().getProtectionListener().cleanup();
            
            if (plugin.getListenerManager().getEnvironmentListener() != null) {
                plugin.getListenerManager().getEnvironmentListener().invalidateCache();
            }
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("Setting integer toggle state for " + normalizedPermission + " to " + value + " in area " + name);
        }
    }

    /**
     * Gets a toggle state with proper key normalization and caching
     */
    public boolean getToggleState(String permission) {
        // Normalize the permission name
        String normalizedPermission = normalizeToggleKey(permission);
        
        // Check cache first
        Boolean cachedValue = toggleStateCache.getIfPresent(normalizedPermission);
        if (cachedValue != null) {
            if (plugin.isDebugMode() && (
                permission.contains("allowBlockPlace") || 
                permission.contains("allowBlockGravity") ||
                plugin.getConfigManager().getBoolean("detailed_toggle_logging", false))) {
                plugin.debug("GetToggleState using CACHED value for " + normalizedPermission + ": " + cachedValue);
            }
            return cachedValue;
        }
        
        // Extra debugging for diagnosing toggle state issues
        boolean hasDetailedLogging = plugin.isDebugMode() && (
            permission.contains("allowBlockPlace") || 
            permission.contains("allowBlockGravity") ||
            plugin.getConfigManager().getBoolean("detailed_toggle_logging", false));
            
        if (hasDetailedLogging) {
            plugin.debug("DETAILED TOGGLE CHECK for " + permission);
            plugin.debug("  Normalized to: " + normalizedPermission);
            plugin.debug("  Toggle states: " + toggleStates);
            if (toggleStates.containsKey(normalizedPermission)) {
                plugin.debug("  Direct value: " + toggleStates.get(normalizedPermission));
            } else {
                plugin.debug("  No direct value found with normalized key");
            }
            // Check original key too
            if (toggleStates.containsKey(permission)) {
                plugin.debug("  Original key value: " + toggleStates.get(permission));
            } else {
                plugin.debug("  No value found with original key");
            }
        }
        
        if (plugin.isDebugMode() && (permission.contains("allowBlockPlace") ||
                                     permission.contains("allowBlockGravity"))) {
            plugin.debug("Getting toggle state for " + normalizedPermission + " in area " + name);
            plugin.debug("  Toggle states: " + toggleStates);
        }
        
        // Try with the normalized permission
        if (toggleStates.containsKey(normalizedPermission)) {
            Object valueObj = toggleStates.get(normalizedPermission);
            boolean value;
            
            // Check for strength values (ending with "Strength")
            if (normalizedPermission.endsWith("Strength")) {
                // Strength values are integers, and any value > 0 means the effect is enabled
                if (valueObj instanceof Integer) {
                    // If it's an integer strength value, return true if > 0
                    value = ((Integer) valueObj) > 0;
                } else if (valueObj instanceof Boolean) {
                    // If it's a boolean, use it directly
                    value = (Boolean) valueObj;
                } else {
                    // Default to false for unknown types
                    value = false;
                }
            } else {
                // Regular toggle values should be booleans
                if (valueObj instanceof Boolean) {
                    value = (Boolean) valueObj;
                } else if (valueObj instanceof Integer) {
                    // Handle integer values for regular toggles as true if > 0
                    value = ((Integer) valueObj) > 0;
                } else {
                    // Default to false for unknown types
                    value = false;
                }
            }
            
            toggleStateCache.put(normalizedPermission, value);
            
            if (hasDetailedLogging) {
                plugin.debug("  Value (from toggleStates): " + value);
            }
            return value;
        }
        
        // If the permission wasn't normalized correctly, try other formats
        // This is just a fallback for backward compatibility
        if (normalizedPermission.startsWith(GUI_PERMISSIONS_PREFIX)) {
            String permWithoutPrefix = normalizedPermission.substring(GUI_PERMISSIONS_PREFIX.length());
            if (toggleStates.containsKey(permWithoutPrefix)) {
                Object valueObj = toggleStates.get(permWithoutPrefix);
                boolean value = valueObj instanceof Boolean ? (Boolean) valueObj : (valueObj instanceof Integer && ((Integer) valueObj) > 0);
                toggleStateCache.put(normalizedPermission, value);
                
                if (hasDetailedLogging) {
                    plugin.debug("  Value (without prefix): " + value);
                }
                return value;
            }
        } else if (!normalizedPermission.startsWith(GUI_PERMISSIONS_PREFIX)) {
            String permWithPrefix = GUI_PERMISSIONS_PREFIX + normalizedPermission;
            if (toggleStates.containsKey(permWithPrefix)) {
                Object valueObj = toggleStates.get(permWithPrefix);
                boolean value = valueObj instanceof Boolean ? (Boolean) valueObj : (valueObj instanceof Integer && ((Integer) valueObj) > 0);
                toggleStateCache.put(normalizedPermission, value);
                
                if (hasDetailedLogging) {
                    plugin.debug("  Value (with prefix): " + value);
                }
                return value;
            }
        }
        
        // Special case for specific toggles that should default to true
        if (normalizedPermission.equals(GUI_PERMISSIONS_PREFIX + "allowHangingBreak") ||
            normalizedPermission.equals("allowHangingBreak")) {
            boolean defaultValue = true;
            toggleStateCache.put(normalizedPermission, defaultValue);
            
            if (hasDetailedLogging) {
                plugin.debug("  Value: true (special default for hanging break)");
            }
            return defaultValue;
        }
        
        // Default value if not found
        boolean defaultValue = false;
        toggleStateCache.put(normalizedPermission, defaultValue);
        
        if (hasDetailedLogging) {
            plugin.debug("  Value: false (default)");
        }
        return defaultValue;
    }

    /**
     * Gets the potion effect strength for the specified effect
     * 
     * @param effectName The name of the potion effect (e.g., "allowPotionSpeed")
     * @return The strength value (0-255), or 0 if not set or disabled
     */
    public int getPotionEffectStrength(String effectName) {
        // Normalize permission name to ensure consistent format
        String normalizedKey = normalizeToggleKey(effectName);
        
        // Check if the effect exists in potionEffects
        if (potionEffects.has(normalizedKey)) {
            return potionEffects.optInt(normalizedKey, 0);
        }
        
        // Fall back to checking toggleStates with "Strength" suffix
        String strengthKey = normalizedKey.endsWith("Strength") ? 
            normalizedKey : normalizedKey + "Strength";
            
        Object value = toggleStates.get(strengthKey);
        if (value instanceof Integer) {
            return (Integer) value;
        }
        
        return 0;
    }
    
    /**
     * Sets the strength of a potion effect for this area
     * 
     * @param effectName The name of the potion effect (e.g., "allowPotionSpeed")
     * @param strength The strength value (0-255)
     */
    public void setPotionEffectStrength(String effectName, int strength) {
        // Normalize permission name
        String normalizedKey = normalizeToggleKey(effectName);
        
        // Validate and constrain the strength value
        int validStrength = Math.max(0, Math.min(255, strength));
        
        // Update both storage locations for backward compatibility
        // Store in potionEffects for the new way
        potionEffects.put(normalizedKey, validStrength);
        
        // Also store in toggleStates with "Strength" suffix for backward compatibility
        String strengthKey = normalizedKey.endsWith("Strength") ? 
            normalizedKey : normalizedKey + "Strength";
        toggleStates.put(strengthKey, validStrength);
        
        // Clear cache to ensure changes take effect
        toggleStateCache.invalidateAll();
        
        if (plugin.isDebugMode()) {
            plugin.debug("Set potion effect " + normalizedKey + " strength to " + validStrength + 
                       " in area " + name);
        }
    }
    
    /**
     * Gets all potion effects and their strengths for this area
     * 
     * @return A map of potion effect names to their strength values
     */
    public Map<String, Integer> getAllPotionEffects() {
        Map<String, Integer> effects = new HashMap<>();
        
        // Add all effects from potionEffects JSONObject
        for (String key : potionEffects.keySet()) {
            effects.put(key, potionEffects.optInt(key, 0));
        }
        
        // Also check toggleStates for any strength values
        for (Map.Entry<String, Object> entry : toggleStates.entrySet()) {
            String key = entry.getKey();
            if (key.endsWith("Strength") && entry.getValue() instanceof Integer) {
                effects.put(key, (Integer) entry.getValue());
            }
        }
        
        return effects;
    }

    public AreaDTO toDTO() {
        // Create a JSONObject from toggleStates map
        JSONObject toggleStatesJson = new JSONObject(toggleStates);
        
        // Create a copy of the original settings
        JSONObject updatedSettings = new JSONObject(dto.settings());
        
        // Ensure toggle states are reflected in settings for backward compatibility
        // This is only for in-memory representation, not database storage
        for (String key : toggleStates.keySet()) {
            Object value = toggleStates.get(key);
            
            // For toggle states with the prefix, also add them to settings without the prefix
            if (key.startsWith("gui.permissions.toggles.")) {
                String shortKey = key.substring("gui.permissions.toggles.".length());
                updatedSettings.put(shortKey, value);
            }
            
            // Also add the full key to settings
            updatedSettings.put(key, value);
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug(String.format("Converting area %s to DTO", name));
            plugin.debug("Toggle states: " + toggleStatesJson.toString());
            plugin.debug("Updated settings: " + updatedSettings.toString());
            plugin.debug("Potion effects: " + potionEffects.toString());
        }

        return new AreaDTO(
            name,
            world,
            dto.bounds(),
            priority,
            dto.showTitle(),
            updatedSettings,  // Use the updated settings for in-memory representation
            new HashMap<>(groupPermissions),
            dto.inheritedPermissions(),
            toggleStatesJson,  // Use the actual toggle states for database storage
            dto.defaultToggleStates(),
            dto.inheritedToggleStates(),
            dto.permissions(),
            dto.enterMessage(),
            dto.leaveMessage(),
            new HashMap<>(trackPermissions),
            new HashMap<>(playerPermissions),
            potionEffects  // Include potion effects
        );
    }

    // Simple getters that delegate to DTO
    public String getName() { return name; }
    public String getWorld() { return world; }
    public AreaDTO.Bounds getBounds() { return dto.bounds(); }
    public int getPriority() { return priority; }

    public boolean contains(Position pos) {
        if (!world.equals(pos.getLevel().getName())) {
            return false;
        }
        
        // For global areas, skip caching and detailed checks
        if (isGlobal()) {
            return true;
        }
        
        // Use exact coordinates for consistency with isInside method
        String cacheKey = String.format("%.3f:%.3f:%.3f", pos.getX(), pos.getY(), pos.getZ());
        Boolean cached = containsCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        
        // Quick check using bounding box
        if (!boundingBox.isVectorInside(pos)) {
            containsCache.put(cacheKey, false);
            return false;
        }
        
        // Use the same small epsilon for consistency with isInside method
        final double EPSILON = 0.0001;
        
        // Detailed check if within bounding box with small buffer for boundary cases
        boolean result = pos.getX() >= (Math.min(pos1.getX(), pos2.getX()) - EPSILON) &&
                        pos.getX() <= (Math.max(pos1.getX(), pos2.getX()) + EPSILON) &&
                        pos.getY() >= (Math.min(pos1.getY(), pos2.getY()) - EPSILON) &&
                        pos.getY() <= (Math.max(pos1.getY(), pos2.getY()) + EPSILON) &&
                        pos.getZ() >= (Math.min(pos1.getZ(), pos2.getZ()) - EPSILON) &&
                        pos.getZ() <= (Math.max(pos1.getZ(), pos2.getZ()) + EPSILON);
        
        containsCache.put(cacheKey, result);
        return result;
    }

    public void cleanup() {
        containsCache.invalidateAll();
        effectivePermissionCache.clear();
        // Clear other caches if memory pressure is high
        if (Runtime.getRuntime().freeMemory() < Runtime.getRuntime().maxMemory() * 0.2) {
            groupPermissions.clear();
            trackPermissions.clear();
            playerPermissions.clear();
        }
    }

    public void clearCaches() {
        if (plugin.isDebugMode()) {
            plugin.debug("Clearing caches for area " + name);
        }
        
        containsCache.invalidateAll();
        effectivePermissionCache.clear();
        cachedPlayerPermissions = null;
        
        // Also clear the toggle states to force reload from DTO
        toggleStates.clear();
        JSONObject toggles = dto.toggleStates();
        for (String key : toggles.keySet()) {
            toggleStates.put(key, toggles.getBoolean(key));
        }
    }

    // Getters
    public Vector3 getPos1() { return pos1; }
    public Vector3 getPos2() { return pos2; }
    public Map<String, Object> getToggleStates() { return new HashMap<>(toggleStates); }
    public SimpleAxisAlignedBB getBoundingBox() { return boundingBox; }
    
    // For efficient area lookup
    public boolean overlaps(SimpleAxisAlignedBB other) {
        return this.boundingBox.intersectsWith(other);
    }
    
    public boolean overlaps(Area other) {
        return this.boundingBox.intersectsWith(other.boundingBox);
    }

    public Map<String, Boolean> getTrackPermissions(String trackName) {
        return trackPermissions.getOrDefault(trackName, new HashMap<>());
    }

    public Map<String, Map<String, Boolean>> getTrackPermissions() {
        return trackPermissions;
    }

    /**
     * Get all player-specific permissions for this area
     * @return Map of player names to their permission maps
     */
    public Map<String, Map<String, Boolean>> getPlayerPermissions() {
        // Return cached permissions if available
        if (cachedPlayerPermissions != null) {
            if (plugin.isDebugMode()) {
                plugin.debug("Getting player permissions for area " + name + " (cached)");
            }
            return new HashMap<>(cachedPlayerPermissions);
        }

        if (plugin.isDebugMode()) {
            plugin.debug("Getting player permissions for area " + name + " (not cached)");
        }
        
        // Cache permissions
        cachedPlayerPermissions = new HashMap<>(playerPermissions);
        return new HashMap<>(cachedPlayerPermissions);
    }
    
    /**
     * Get permissions for a specific player in this area
     */
    public Map<String, Boolean> getPlayerPermissions(String playerName) {
        if (plugin.isDebugMode()) {
            plugin.debug("Getting permissions for player " + playerName + " in area " + name);
            plugin.debug("  All player permissions: " + playerPermissions);
            plugin.debug("  Player's permissions: " + playerPermissions.get(playerName));
            plugin.debug("  DTO player permissions: " + dto.playerPermissions());
            plugin.debug("  DTO player permissions for " + playerName + ": " + 
                (dto.playerPermissions().containsKey(playerName) ? dto.playerPermissions().get(playerName) : "none"));
        }
        return playerPermissions.getOrDefault(playerName, new HashMap<>());
    }

    public void setPlayerPermissions(String playerName, Map<String, Boolean> permissions) throws DatabaseException {
        if (plugin.isDebugMode()) {
            plugin.debug("Setting permissions for player " + playerName + " in area " + name);
            plugin.debug("  New permissions: " + permissions);
            plugin.debug("  Old permissions in map: " + playerPermissions.get(playerName));
        }
        
        // Update local map
        playerPermissions.put(playerName, new HashMap<>(permissions));
        
        // Clear cache to ensure fresh data is used
        cachedPlayerPermissions = null;
        
        // Save to database
        try {
            // Create updated DTO with current permissions
            AreaDTO updatedDTO = toDTO();
            if (plugin.isDebugMode()) {
                plugin.debug("  Updated DTO player permissions: " + updatedDTO.playerPermissions());
                plugin.debug("  Current player permissions map: " + playerPermissions);
            }
            
            plugin.getDatabaseManager().saveArea(this);
            if (plugin.isDebugMode()) {
                plugin.debug("  Saved updated permissions to database");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to save player permissions to database", e);
            throw new DatabaseException("Failed to save player permissions to database", e);
        }
    }

    public void setPlayerPermission(String playerName, String permission, boolean value) throws DatabaseException {
        if (plugin.isDebugMode()) {
            plugin.debug("Setting permission " + permission + " to " + value + " for player " + playerName + " in area " + name);
            plugin.debug("  Old permissions in map: " + playerPermissions.get(playerName));
        }

        // Update local map
        Map<String, Boolean> playerPerms = playerPermissions.computeIfAbsent(playerName, k -> new HashMap<>());
        playerPerms.put(permission, value);
        
        // Clear cache to ensure fresh data is used
        cachedPlayerPermissions = null;

        // Save to database
        try {
            // Create updated DTO with current permissions
            AreaDTO updatedDTO = toDTO();
            if (plugin.isDebugMode()) {
                plugin.debug("  Updated DTO player permissions: " + updatedDTO.playerPermissions());
                plugin.debug("  Current player permissions map: " + playerPermissions);
            }
            
            plugin.getDatabaseManager().saveArea(this);
            if (plugin.isDebugMode()) {
                plugin.debug("  Saved updated permission to database");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to save player permission to database", e);
            throw new DatabaseException("Failed to save player permission to database", e);
        }
    }

    public Map<String, Boolean> getGroupPermissions(String groupName) {
        return groupPermissions.getOrDefault(groupName, new HashMap<>());
    }

    public Map<String, Map<String, Boolean>> getGroupPermissions() {
        return new HashMap<>(groupPermissions);
    }

    /**
     * Get an integer setting from the area's settings
     * @param key The setting key
     * @param defaultValue The default value if setting doesn't exist or isn't an integer
     * @return The integer value of the setting
     */
    public int getSettingInt(String key, int defaultValue) {
        try {
            JSONObject settings = dto.settings();
            if (settings != null && settings.has(key)) {
                return settings.optInt(key, defaultValue);
            }
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error getting integer setting " + key + ": " + e.getMessage());
            }
        }
        return defaultValue;
    }
    
    /**
     * Get a string setting from the area's settings
     * @param key The setting key
     * @param defaultValue The default value if setting doesn't exist
     * @return The string value of the setting
     */
    public String getSettingString(String key, String defaultValue) {
        try {
            JSONObject settings = dto.settings();
            if (settings != null && settings.has(key)) {
                return settings.optString(key, defaultValue);
            }
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error getting string setting " + key + ": " + e.getMessage());
            }
        }
        return defaultValue;
    }
    
    /**
     * Get a boolean setting from the area's settings
     * @param key The setting key
     * @param defaultValue The default value if setting doesn't exist
     * @return The boolean value of the setting
     */
    public boolean getSettingBoolean(String key, boolean defaultValue) {
        try {
            JSONObject settings = dto.settings();
            if (settings != null && settings.has(key)) {
                return settings.optBoolean(key, defaultValue);
            }
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error getting boolean setting " + key + ": " + e.getMessage());
            }
        }
        return defaultValue;
    }

    public void clearAllPermissions() throws DatabaseException {
        // Temporarily disable debug logging
        boolean wasDebug = plugin.isDebugMode();
        plugin.setDebugMode(false);
        
        try {
            // Clear all permission maps
            groupPermissions.clear();
            trackPermissions.clear();
            playerPermissions.clear();
            
            // Clear cache
            cachedPlayerPermissions = null;
            
            // Save to database directly without triggering events
            plugin.getDatabaseManager().saveArea(this);
            
        } finally {
            // Restore debug mode
            plugin.setDebugMode(wasDebug);
            
            if (wasDebug) {
                plugin.debug("Cleared all permissions for area " + name);
                plugin.debug("  Group permissions cleared: " + groupPermissions);
                plugin.debug("  Track permissions cleared: " + trackPermissions);
                plugin.debug("  Player permissions cleared: " + playerPermissions);
            }
        }
    }

    /**
     * Ensures all toggle states are stored with consistent prefix
     * Call this method during plugin startup or area loading to fix inconsistencies
     */
    public void normalizeToggleStates() {
        if (plugin.isDebugMode()) {
            plugin.debug("Normalizing toggle states for area " + name);
            plugin.debug("  Before: " + toggleStates);
        }
        
        Map<String, Object> normalizedToggles = new ConcurrentHashMap<>(8, 0.75f, 1);
        
        // Process each toggle and normalize its key
        for (Map.Entry<String, Object> entry : toggleStates.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            // Normalize the key
            if (!key.startsWith("gui.permissions.toggles.") && key.startsWith("allow")) {
                key = "gui.permissions.toggles." + key;
            }
            
            normalizedToggles.put(key, value);
        }
        
        // Replace the toggle states with normalized ones
        toggleStates.clear();
        toggleStates.putAll(normalizedToggles);
        
        if (plugin.isDebugMode()) {
            plugin.debug("  After: " + toggleStates);
        }
    }

    /**
     * Force clear all caches including the toggle state cache.
     * This should be called when experiencing permission issues or inconsistencies.
     */
    public void emergencyClearCaches() {
        if (plugin.isDebugMode()) {
            plugin.debug("EMERGENCY CACHE CLEAR for area " + name);
        }
        
        // Clear all caches
        toggleStateCache.invalidateAll();
        containsCache.invalidateAll();
        effectivePermissionCache.clear();
        
        // Force reload settings from database
        try {
            // Reload area from database
            Area freshArea = plugin.getDatabaseManager().loadArea(name);
            if (freshArea != null) {
                // Update toggle states
                this.toggleStates.clear();
                this.toggleStates.putAll(freshArea.getToggleStates());
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Reloaded toggle states from database: " + toggleStates);
                }
            }
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error reloading area from database: " + e.getMessage());
            }
        }
        
        // Notify all listeners that settings might have changed
        if (plugin.getListenerManager() != null) {
            if (plugin.getListenerManager().getProtectionListener() != null) {
                plugin.getListenerManager().getProtectionListener().cleanup();
            }
            if (plugin.getListenerManager().getEnvironmentListener() != null) {
                plugin.getListenerManager().getEnvironmentListener().invalidateCache();
            }
        }
    }

    /**
     * Get a setting value with automatic type casting.
     * This is a more optimized version of getSettingInt/getSettingBoolean that avoids multiple lookups.
     *
     * @param key The setting key to get
     * @param defaultValue The default value to return if the setting is not found
     * @return The setting value, or defaultValue if not found
     */
    @SuppressWarnings("unchecked")
    public <T> T getSettingObject(String key, T defaultValue) {
        String normalizedKey = normalizeToggleKey(key);
        
        // Try cache first for better performance
        if (toggleStateCache != null) {
            if (defaultValue instanceof Boolean) {
                Boolean cachedResult = toggleStateCache.getIfPresent(normalizedKey);
                if (cachedResult != null) {
                    return (T) cachedResult;
                }
            }
        }
        
        // Try to get the value from toggleStates
        Object result = toggleStates.get(normalizedKey);
        if (result == null) {
            return defaultValue;
        }
        
        // Handle type conversion
        try {
            if (defaultValue instanceof Boolean && result instanceof Number) {
                // If we're expecting a boolean but got a number, treat 0 as false, anything else as true
                return (T) Boolean.valueOf(((Number) result).intValue() != 0);
            } else if (defaultValue instanceof Integer && result instanceof Boolean) {
                // If we're expecting an integer but got a boolean, treat false as 0, true as 1
                return (T) Integer.valueOf(((Boolean) result) ? 1 : 0);
            } else if (defaultValue instanceof Integer && result instanceof Number) {
                // If we're expecting an integer and got a number, convert to integer
                return (T) Integer.valueOf(((Number) result).intValue());
            } else if (defaultValue instanceof Boolean && result instanceof Boolean) {
                // If we're expecting a boolean and got a boolean, return as is
                return (T) result;
            } else if (defaultValue instanceof String && result instanceof String) {
                // If we're expecting a string and got a string, return as is
                return (T) result;
            }
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error converting setting " + key + " with value " + result + ": " + e.getMessage());
            }
        }
        
        // If we can't convert the type properly, return the default
        return defaultValue;
    }

    /**
     * Synchronizes toggles between the settings object and toggle state maps
     * This ensures all toggle states are properly saved to the database
     * @return The updated Area with synchronized toggle states
     */
    public Area synchronizeToggleStates() {
        AreaDTO currentDTO = toDTO();
        JSONObject settings = currentDTO.settings();
        JSONObject toggleStatesJson = currentDTO.toggleStates();
        
        if (plugin.isDebugMode()) {
            plugin.debug("Synchronizing toggle states for area " + name);
            plugin.debug("  Before - Toggle states: " + toggleStatesJson);
        }
        
        // Ensure all toggle states from settings are in the toggleStates map
        // This is important for backward compatibility
        for (String key : settings.keySet()) {
            if (PermissionToggle.isValidToggle(key) && !toggleStatesJson.has(key)) {
                Object value = settings.opt(key);
                toggleStatesJson.put(key, value);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("  Adding missing toggle from settings: " + key + " = " + value);
                }
            }
            
            // If this is a short key (without prefix), add it with the prefix too
            if (!key.startsWith("gui.permissions.toggles.") && PermissionToggle.isValidToggle("gui.permissions.toggles." + key)) {
                String fullKey = "gui.permissions.toggles." + key;
                if (!toggleStatesJson.has(fullKey)) {
                    Object value = settings.opt(key);
                    toggleStatesJson.put(fullKey, value);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Adding prefixed toggle: " + fullKey + " = " + value);
                    }
                }
            }
        }
        
        // Also ensure all toggle states from the in-memory map are in the JSON
        for (Map.Entry<String, Object> entry : toggleStates.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            if (!toggleStatesJson.has(key)) {
                toggleStatesJson.put(key, value);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("  Adding missing toggle from memory: " + key + " = " + value);
                }
            }
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("  After - Toggle states: " + toggleStatesJson);
        }
        
        // Update area with synchronized toggle states
        return AreaBuilder.fromDTO(currentDTO)
            .toggleStates(toggleStatesJson)
            .build();
    }

    /**
     * Gets all toggle keys that have been set in this area
     * @return Set of all toggle keys
     */
    public Set<String> getAllToggleKeys() {
        Set<String> keys = new HashSet<>();
        
        // Add keys from toggleStates
        for (String key : toggleStates.keySet()) {
            keys.add(key);
        }
        
        // Add keys from settings
        JSONObject settingsJson = toDTO().settings();
        for (String key : settingsJson.keySet()) {
            if (key.startsWith("gui.permissions.toggles.")) {
                keys.add(key);
            }
        }
        
        // Add keys from default toggles
        for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
            keys.add(toggle.getPermissionNode());
        }
        
        return keys;
    }

    /**
     * Checks if this area has any potion effects
     * @return true if the area has potion effects, false otherwise
     */
    public boolean hasPotionEffects() {
        // Check if potionEffects JSON has any entries
        if (potionEffects != null && potionEffects.length() > 0) {
            return true;
        }
        
        // Check for potion effect toggles in the settings
        JSONObject settingsJson = toDTO().settings();
        for (String key : settingsJson.keySet()) {
            if (key.startsWith("gui.permissions.toggles.allowPotion")) {
                boolean value = settingsJson.optBoolean(key, false);
                if (value) {
                    return true;
                }
                
                // Also check for strength values > 0
                String strengthKey = key + "Strength";
                int strength = settingsJson.optInt(strengthKey, 0);
                if (strength > 0) {
                    return true;
                }
            }
        }
        
        // Also check toggleStates map for direct toggle values
        for (String key : toggleStates.keySet()) {
            if (key.contains("allowPotion")) {
                Object value = toggleStates.get(key);
                
                // Check boolean toggle values
                if (value instanceof Boolean && (Boolean)value) {
                    return true;
                }
                
                // Check strength values
                if (key.endsWith("Strength") && value instanceof Integer && (Integer)value > 0) {
                    return true;
                }
            }
        }
        
        return false;
    }

    /**
     * Gets all potion effects as a JSONObject
     * @return JSONObject containing potion effects
     */
    public JSONObject getPotionEffectsAsJSON() {
        // Return existing potionEffects if available
        if (potionEffects != null && potionEffects.length() > 0) {
            return new JSONObject(potionEffects.toString());
        }
        
        // Rebuild from settings
        JSONObject result = new JSONObject();
        JSONObject settingsJson = toDTO().settings();
        
        // Check for potion effect toggles and their strength values
        for (String key : settingsJson.keySet()) {
            if (key.startsWith("gui.permissions.toggles.allowPotion")) {
                boolean value = settingsJson.optBoolean(key, false);
                if (value) {
                    // Extract the potion effect name
                    String potionName = key.replace("gui.permissions.toggles.allowPotion", "");
                    
                    // Check for strength value
                    String strengthKey = key + "Strength";
                    int strength = settingsJson.optInt(strengthKey, 1);
                    
                    // Add to result
                    result.put(potionName, strength);
                }
            }
        }
        
        return result;
    }
}