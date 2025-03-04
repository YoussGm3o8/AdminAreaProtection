package adminarea.area;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.permissions.PermissionToggle;
import adminarea.exception.DatabaseException;
import adminarea.permissions.PermissionOverrideManager;
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
    
    private Map<String, Map<String, Boolean>> groupPermissions;
    private Map<String, Map<String, Boolean>> trackPermissions;
    private Map<String, Map<String, Boolean>> playerPermissions;
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
        
        // Only add to toggle states if it's a toggle permission
        if (normalizedPermission.startsWith(GUI_PERMISSIONS_PREFIX)) {
            // Update cache and storage
            toggleStates.put(normalizedPermission, state);
            toggleStateCache.invalidateAll(); // Clear the entire toggle state cache
            
            // Invalidate all protection caches to ensure changes take effect immediately
            if (plugin.getListenerManager() != null) {
                // Invalidate protection listener cache
                if (plugin.getListenerManager().getProtectionListener() != null) {
                    plugin.getListenerManager().getProtectionListener().cleanup();
                }
                
                // Invalidate environment listener cache if available
                if (plugin.getListenerManager().getEnvironmentListener() != null) {
                    plugin.getListenerManager().getEnvironmentListener().invalidateCache();
                }
                
                // Invalidate item listener cache if available
                if (plugin.getListenerManager().getItemListener() != null) {
                    plugin.getListenerManager().getItemListener().clearCache();
                }
            }
            
            // Invalidate permission checker cache
            if (plugin.getPermissionOverrideManager() != null && 
                plugin.getPermissionOverrideManager().getPermissionChecker() != null) {
                plugin.getPermissionOverrideManager().getPermissionChecker().invalidateCache(name);
            }
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
        
        // Instead of updating the database directly for each toggle, just update memory
        // Individual database updates will be performed in bulk when the area is saved
        
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
     * Save toggle states to database in a single batch operation
     * Call this after multiple toggle states have been set
     */
    public void saveToggleStates() {
        try {
            // Create a copy of the toggle states to send to the database
            JSONObject toggleStatesJson = new JSONObject(toggleStates);
            plugin.getDatabaseManager().updateAllAreaToggleStates(name, toggleStatesJson);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Saved all toggle states to database for area " + name);
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to save toggle states to database", e);
        }
    }

    /**
     * Gets a toggle state with proper key normalization and caching
     */
    public boolean getToggleState(String permission) {
        String normalizedPermission = normalizeToggleKey(permission);
        
        // Check cache first
        Boolean cachedValue = toggleStateCache.getIfPresent(normalizedPermission);
        if (cachedValue != null) {
            return cachedValue;
        }
        
        // Get value from storage
        Object value = toggleStates.get(normalizedPermission);
        boolean state = value instanceof Boolean ? (Boolean) value : false;
        
        // Cache the result
        toggleStateCache.put(normalizedPermission, state);
        
        return state;
    }

    /**
     * Gets the potion effect strength for the specified effect
     * 
     * @param effectName The name of the potion effect (e.g., "allowPotionSpeed")
     * @return The strength value (0-10), or 0 if not set or disabled
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
     * @param strength The strength value (0-10)
     */
    public void setPotionEffectStrength(String effectName, int strength) {
        // Normalize permission name
        String normalizedKey = normalizeToggleKey(effectName);
        
        // Validate and constrain the strength value
        int validStrength = Math.max(0, Math.min(10, strength));
        
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
        JSONObject toggleStatesJson = new JSONObject();
        
        // Only include actual toggle states (with prefix)
        for (Map.Entry<String, Object> entry : toggleStates.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(GUI_PERMISSIONS_PREFIX)) {
                toggleStatesJson.put(key, entry.getValue());
            }
        }
        
        // Create a copy of the original settings
        JSONObject updatedSettings = new JSONObject(dto.settings());
        
        // Ensure we have the latest permissions from PermissionOverrideManager
        Map<String, Map<String, Boolean>> latestGroupPerms = getGroupPermissions();
        Map<String, Map<String, Boolean>> latestTrackPerms = getTrackPermissions();
        Map<String, Map<String, Boolean>> latestPlayerPerms = getPlayerPermissions();
        
        if (plugin.isDebugMode()) {
            plugin.debug(String.format("Converting area %s to DTO", name));
            plugin.debug("Toggle states: " + toggleStatesJson.toString());
            plugin.debug("Updated settings: " + updatedSettings.toString());
            plugin.debug("Latest player permissions: " + latestPlayerPerms);
            plugin.debug("Latest group permissions: " + latestGroupPerms);
            plugin.debug("Latest track permissions: " + latestTrackPerms);
            plugin.debug("Potion effects: " + potionEffects.toString());
        }

        return new AreaDTO(
            name,
            world,
            dto.bounds(),
            priority,
            dto.showTitle(),
            updatedSettings,
            latestGroupPerms,  // Use latest data from PermissionOverrideManager
            new HashMap<>(),   // inheritedPermissions - no longer needed
            toggleStatesJson,
            dto.defaultToggleStates(),
            dto.inheritedToggleStates(),
            dto.permissions(),
            dto.enterMessage(),
            dto.leaveMessage(),
            latestTrackPerms,  // Use latest data from PermissionOverrideManager
            latestPlayerPerms, // Use latest data from PermissionOverrideManager
            potionEffects
        );
    }

    /**
     * Synchronizes all permission data between the Area object and PermissionOverrideManager
     * Call this before saving the area to ensure all permission data is consistent
     */
    public void synchronizePermissions() {
        if (plugin.isDebugMode()) {
            plugin.debug("Synchronizing permissions for area " + name);
        }
        
        try {
            // Use the new bidirectional synchronization method
            plugin.getPermissionOverrideManager().synchronizePermissions(
                this, 
                PermissionOverrideManager.SyncDirection.BIDIRECTIONAL
            );
            
            if (plugin.isDebugMode()) {
                plugin.debug("  Successfully synchronized permissions bidirectionally for area " + name);
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to synchronize permissions for area " + name, e);
        }
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
        
        playerPermissions = null;
        groupPermissions = null;
        trackPermissions = null;
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
        return plugin.getPermissionOverrideManager().getTrackPermissions(name, trackName);
    }

    public Map<String, Map<String, Boolean>> getTrackPermissions() {
        // Always fetch fresh data from PermissionOverrideManager
        trackPermissions = plugin.getPermissionOverrideManager().getAllTrackPermissions(name);
        
        // Return a defensive copy
        return new HashMap<>(trackPermissions);
    }

    /**
     * Get all player-specific permissions for this area
     * @return Map of player names to their permission maps
     */
    public Map<String, Map<String, Boolean>> getPlayerPermissions() {
        // Always fetch fresh data from PermissionOverrideManager
        playerPermissions = plugin.getPermissionOverrideManager().getAllPlayerPermissions(name);
        
        // Return a defensive copy
        return new HashMap<>(playerPermissions);
    }
    
    /**
     * Get permissions for a specific player in this area
     */
    public Map<String, Boolean> getPlayerPermissions(String playerName) {
        // Always get fresh data from PermissionOverrideManager
        Map<String, Boolean> playerPerms = plugin.getPermissionOverrideManager().getPlayerPermissions(name, playerName);
        
        // Update the cached permissions map
        if (playerPermissions != null) {
            playerPermissions.put(playerName, new HashMap<>(playerPerms));
        }
        
        return playerPerms;
    }

    public void setPlayerPermissions(String playerName, Map<String, Boolean> permissions) throws DatabaseException {
        if (plugin.isDebugMode()) {
            plugin.debug("Setting permissions for player " + playerName + " in area " + name);
            plugin.debug("  New permissions: " + permissions);
        }
        
        // Directly save to PermissionOverrideManager
        plugin.getPermissionOverrideManager().setPlayerPermissions(name, playerName, permissions);
        
        // Update local cache
        if (playerPermissions != null) {
            playerPermissions.put(playerName, new HashMap<>(permissions));
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("  Saved player permissions to PermissionOverrideManager and updated local cache");
        }
    }

    public void setPlayerPermission(String playerName, String permission, boolean value) {
        if (playerName == null || permission == null) return;
        
        try {
            // Get current permissions
            Map<String, Boolean> perms = getPlayerPermissions(playerName);
            if (perms == null) {
                perms = new HashMap<>();
            }
            
            // Update permission
            perms.put(permission, value);
            
            // Save updated permissions
            setPlayerPermissions(playerName, perms);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Set player permission for " + playerName + " in area " + name);
                plugin.debug("  Permission: " + permission + " = " + value);
                plugin.debug("  Updated permissions: " + perms);
            }
        } catch (DatabaseException e) {
            plugin.getLogger().error("Failed to update player permission", e);
        }
    }

    public boolean getPlayerPermission(String playerName, String permission) {
        if (playerName == null || permission == null) return false;
        
        // Get fresh permissions from PermissionOverrideManager
        Map<String, Boolean> perms = getPlayerPermissions(playerName);
        if (perms == null) return false;
        
        return perms.getOrDefault(permission, false);
    }

    public Map<String, Boolean> getGroupPermissions(String groupName) {
        return plugin.getPermissionOverrideManager().getGroupPermissions(name, groupName);
    }

    public Map<String, Map<String, Boolean>> getGroupPermissions() {
        // Always fetch fresh data from PermissionOverrideManager
        groupPermissions = plugin.getPermissionOverrideManager().getAllGroupPermissions(name);
        
        // Return a defensive copy
        return new HashMap<>(groupPermissions);
    }

    public void setGroupPermissions(String groupName, Map<String, Boolean> permissions) throws DatabaseException {
        if (plugin.isDebugMode()) {
            plugin.debug("Setting permissions for group " + groupName + " in area " + name);
            plugin.debug("  New permissions: " + permissions);
        }
        
        // Directly save to PermissionOverrideManager
        plugin.getPermissionOverrideManager().setGroupPermissions(name, groupName, permissions);
        
        // Update local cache
        if (groupPermissions != null) {
            groupPermissions.put(groupName, new HashMap<>(permissions));
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("  Saved group permissions to PermissionOverrideManager and updated local cache");
        }
    }

    public void setTrackPermissions(String trackName, Map<String, Boolean> permissions) throws DatabaseException {
        if (plugin.isDebugMode()) {
            plugin.debug("Setting permissions for track " + trackName + " in area " + name);
            plugin.debug("  New permissions: " + permissions);
        }
        
        // Directly save to PermissionOverrideManager
        plugin.getPermissionOverrideManager().setTrackPermissions(name, trackName, permissions);
        
        // Update local cache
        if (trackPermissions != null) {
            trackPermissions.put(trackName, new HashMap<>(permissions));
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("  Saved track permissions to PermissionOverrideManager and updated local cache");
        }
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
        // Clear all permissions in PermissionOverrideManager
        plugin.getPermissionOverrideManager().deleteAreaPermissions(name);
        
        // Clear local caches
        groupPermissions.clear();
        trackPermissions.clear();
        playerPermissions.clear();
        cachedPlayerPermissions = null;
        
        if (plugin.isDebugMode()) {
            plugin.debug("Cleared all permissions for area " + name);
        }
    }

    /**
     * Normalizes all toggle states to ensure consistent prefixing
     * This ensures all toggle states are properly saved with the correct prefix
     */
    public void normalizeToggleStates() {
        if (plugin.isDebugMode()) {
            plugin.debug("Normalizing toggle states for area " + name);
            plugin.debug("  Before - Toggle states: " + toggleStates);
        }
        
        Map<String, Object> normalizedToggles = new HashMap<>();
        
        // Process each toggle state and ensure it has the correct prefix
        for (Map.Entry<String, Object> entry : toggleStates.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            // Skip null keys
            if (key == null) continue;
            
            // Normalize the key
            String normalizedKey = normalizeToggleKey(key);
            normalizedToggles.put(normalizedKey, value);
        }
        
        // Replace toggle states with normalized version
        toggleStates.clear();
        toggleStates.putAll(normalizedToggles);
        
        // Invalidate toggle state cache
        toggleStateCache.invalidateAll();
        
        if (plugin.isDebugMode()) {
            plugin.debug("  After - Toggle states: " + toggleStates);
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
            if (!key.startsWith(GUI_PERMISSIONS_PREFIX) && PermissionToggle.isValidToggle("gui.permissions.toggles." + key)) {
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
        
        // Invalidate toggle state cache after synchronization
        toggleStateCache.invalidateAll();
        
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

    /**
     * Updates the internal permission maps with data from the database.
     * This method is called by PermissionOverrideManager during synchronization.
     * 
     * @param playerPerms Map of player permissions from the database
     * @param groupPerms Map of group permissions from the database
     * @param trackPerms Map of track permissions from the database
     */
    public void updateInternalPermissions(
            Map<String, Map<String, Boolean>> playerPerms,
            Map<String, Map<String, Boolean>> groupPerms,
            Map<String, Map<String, Boolean>> trackPerms) {
        
        if (plugin.isDebugMode()) {
            plugin.debug("Updating internal permission maps for area " + name);
        }
        
        // Update player permissions
        this.playerPermissions = new HashMap<>(playerPerms);
        
        // Update group permissions
        this.groupPermissions = new HashMap<>(groupPerms);
        
        // Update track permissions
        this.trackPermissions = new HashMap<>(trackPerms);
        
        // Clear the effective permission cache to force recalculation
        this.effectivePermissionCache.clear();
        
        if (plugin.isDebugMode()) {
            plugin.debug("  Updated player permissions: " + this.playerPermissions.size() + " players");
            plugin.debug("  Updated group permissions: " + this.groupPermissions.size() + " groups");
            plugin.debug("  Updated track permissions: " + this.trackPermissions.size() + " tracks");
        }
    }
}