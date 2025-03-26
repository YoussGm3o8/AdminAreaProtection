package adminarea.permissions;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.exception.DatabaseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages permission overrides for areas, providing a centralized API for 
 * player, group, and track permissions with efficient caching.
 */
public class PermissionOverrideManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(PermissionOverrideManager.class);
    private final AdminAreaProtectionPlugin plugin;
    private final PermissionDatabaseManager databaseManager;
    private final PermissionCache permissionCache;
    private final PermissionChecker permissionChecker;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    
    // Track updated permissions
    private final Map<String, Set<String>> updatedPlayerPermissions = new ConcurrentHashMap<>();
    private final Set<String> updatedGroupPermissions = ConcurrentHashMap.newKeySet();
    private final Set<String> updatedTrackPermissions = ConcurrentHashMap.newKeySet();
    
    // ThreadLocal for tracking permissions being processed to prevent recursion
    private static final ThreadLocal<Set<String>> PROCESSING_PERMISSIONS = ThreadLocal.withInitial(HashSet::new);
    
    /**
     * Direction to synchronize permissions
     */
    public enum SyncDirection {
        /**
         * Sync permissions from area object to the database
         */
        TO_DATABASE,
        
        /**
         * Sync permissions from database to area object
         */
        FROM_DATABASE,
        
        /**
         * Sync permissions in both directions (full synchronization)
         */
        BIDIRECTIONAL
    }
    
    public PermissionOverrideManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.databaseManager = new PermissionDatabaseManager(plugin);
        this.permissionCache = new PermissionCache();
        this.permissionChecker = new PermissionChecker(plugin);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        
        setupScheduledTasks();
    }
    
    private void setupScheduledTasks() {
        // Schedule database backups
        scheduler.scheduleAtFixedRate(() -> {
            if (!isShuttingDown.get()) {
                try {
                    databaseManager.backupDatabase();
                } catch (Exception e) {
                    logger.error("Failed to run scheduled database backup", e);
                }
            }
        }, 1, 12, TimeUnit.HOURS);
        
        // Schedule WAL checkpoint
        scheduler.scheduleAtFixedRate(() -> {
            if (!isShuttingDown.get()) {
                try {
                    databaseManager.checkpoint();
                } catch (Exception e) {
                    logger.error("Failed to run scheduled WAL checkpoint", e);
                }
            }
        }, 30, 30, TimeUnit.MINUTES);
    }
    
    // --- Player Permissions ---
    
    /**
     * Get permissions for a player in an area
     */
    public Map<String, Boolean> getPlayerPermissions(String areaName, String playerName) {
        if (areaName == null || playerName == null) {
            return Collections.emptyMap();
        }
        
        // For debug logging
        if (plugin.isDebugMode()) {
            plugin.debug("Retrieved permissions for player " + playerName + " in area " + areaName);
        }
        
        String cacheKey = PermissionCache.createPlayerKey(areaName, playerName);
        Map<String, Boolean> cached = permissionCache.getPlayerPermissions(cacheKey);
        
        if (cached != null) {
            if (plugin.isDebugMode()) {
                plugin.debug("Player " + playerName + " has " + cached.size() + " cached permissions in area " + areaName);
            }
            return new HashMap<>(cached);
        }
        
        try {
            Map<String, Boolean> permissions = databaseManager.getPlayerPermissions(areaName, playerName);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Retrieved " + permissions.size() + " permissions for player " + playerName + " in area " + areaName);
                if (permissions.isEmpty()) {
                    plugin.debug("Player " + playerName + " has no specific permissions in area " + areaName);
                }
            }
            
            permissionCache.cachePlayerPermissions(cacheKey, permissions);
            return permissions;
        } catch (DatabaseException e) {
            logger.error("Failed to load player permissions for {}:{}", areaName, playerName, e);
            return Collections.emptyMap();
        }
    }
    
    /**
     * Set permissions for a player in an area
     */
    public void setPlayerPermissions(String areaName, String playerName, Map<String, Boolean> permissions) 
            throws DatabaseException {
        if (areaName == null || playerName == null || permissions == null) {
            return;
        }
        
        // Prevent recursion - check if we're already processing this area+player combination
        String key = areaName + ":" + playerName;
        Set<String> processingSet = PROCESSING_PERMISSIONS.get();
        
        if (processingSet.contains(key)) {
            if (plugin.isDebugMode()) {
                plugin.debug("Preventing recursive player permission update for " + playerName + " in area " + areaName);
            }
            return;
        }
        
        processingSet.add(key);
        try {
            databaseManager.savePlayerPermissions(areaName, playerName, permissions);
            
            // Update caches
            String cacheKey = PermissionCache.createPlayerKey(areaName, playerName);
            permissionCache.cachePlayerPermissions(cacheKey, permissions);
            
            // Invalidate any area permission caches for this area
            invalidateCache(areaName);
            
            // Clear listeners and caches for this area
            plugin.getListenerManager().getProtectionListener().cleanup();
        } catch (Exception e) {
            plugin.getLogger().error("Failed to update player permissions for " + playerName, e);
            throw new DatabaseException("Failed to update player permissions", e);
        } finally {
            // Remove from processing set to allow future updates
            processingSet.remove(key);
        }
    }
    
    /**
     * Delete permissions for a player in an area
     */
    public void deletePlayerPermissions(String areaName, String playerName) throws DatabaseException {
        if (areaName == null || playerName == null) {
            return;
        }
        
        databaseManager.deletePlayerPermissions(areaName, playerName);
        
        String cacheKey = PermissionCache.createPlayerKey(areaName, playerName);
        permissionCache.invalidatePlayerPermissions(cacheKey);
        
        // Remove from updated tracking
        Set<String> playersForArea = updatedPlayerPermissions.get(areaName);
        if (playersForArea != null) {
            playersForArea.remove(playerName);
            if (playersForArea.isEmpty()) {
                updatedPlayerPermissions.remove(areaName);
            }
        }
    }
    
    // --- Group Permissions ---
    
    /**
     * Get permissions for a group in an area
     */
    public Map<String, Boolean> getGroupPermissions(String areaName, String groupName) {
        if (areaName == null || groupName == null) {
            return Collections.emptyMap();
        }
        
        String cacheKey = PermissionCache.createGroupKey(areaName, groupName);
        Map<String, Boolean> cached = permissionCache.getGroupPermissions(cacheKey);
        
        if (cached != null) {
            return new HashMap<>(cached);
        }
        
        try {
            Map<String, Boolean> permissions = databaseManager.getGroupPermissions(areaName, groupName);
            permissionCache.cacheGroupPermissions(cacheKey, permissions);
            return permissions;
        } catch (DatabaseException e) {
            logger.error("Failed to load group permissions for {}:{}", areaName, groupName, e);
            return Collections.emptyMap();
        }
    }
    
    /**
     * Set permissions for a group in an area
     */
    public void setGroupPermissions(String areaName, String groupName, Map<String, Boolean> permissions) 
            throws DatabaseException {
        if (areaName == null || groupName == null || permissions == null) {
            return;
        }
        
        try {
            // Use the direct method to avoid area recreation
            boolean success = plugin.getAreaManager().directUpdateGroupPermissions(areaName, groupName, permissions);
            
            if (!success) {
                throw new DatabaseException("Failed to update group permissions using direct method");
            }
            
            // Invalidation is handled by the direct method
        } catch (Exception e) {
            plugin.getLogger().error("Failed to update group permissions for " + groupName, e);
            throw new DatabaseException("Failed to update group permissions", e);
        }
    }
    
    /**
     * Set permissions for a group in an area with force flag
     */
    public void setGroupPermissions(String areaName, String groupName, Map<String, Boolean> permissions, boolean force) 
            throws DatabaseException {
        // The force flag is ignored in this implementation since we always save
        setGroupPermissions(areaName, groupName, permissions);
    }
    
    /**
     * Invalidate group permissions cache
     */
    public void invalidateGroupPermissions(String areaName, String groupName) {
        if (areaName == null || groupName == null) {
            return;
        }
        
        String cacheKey = PermissionCache.createGroupKey(areaName, groupName);
        permissionCache.invalidateGroupPermissions(cacheKey);
    }
    
    // --- Track Permissions ---
    
    /**
     * Get permissions for a track in an area
     */
    public Map<String, Boolean> getTrackPermissions(String areaName, String trackName) {
        if (areaName == null || trackName == null) {
            return Collections.emptyMap();
        }
        
        String cacheKey = PermissionCache.createTrackKey(areaName, trackName);
        Map<String, Boolean> cached = permissionCache.getTrackPermissions(cacheKey);
        
        if (cached != null) {
            return new HashMap<>(cached);
        }
        
        try {
            Map<String, Boolean> permissions = databaseManager.getTrackPermissions(areaName, trackName);
            permissionCache.cacheTrackPermissions(cacheKey, permissions);
            return permissions;
        } catch (DatabaseException e) {
            logger.error("Failed to load track permissions for {}:{}", areaName, trackName, e);
            return Collections.emptyMap();
        }
    }
    
    /**
     * Set permissions for a track in an area
     */
    public void setTrackPermissions(String areaName, String trackName, Map<String, Boolean> permissions) 
            throws DatabaseException {
        if (areaName == null || trackName == null || permissions == null) {
            return;
        }
        
        try {
            // Check if there's already a permission operation in progress
            if (plugin.getAreaManager().isPermissionOperationInProgress(areaName)) {
                // If an operation is already in progress, apply the permissions directly to the database
                // instead of using directUpdateTrackPermissions which would skip the operation
                if (plugin.isDebugMode()) {
                    plugin.debug("Permission operation already in progress for area " + areaName + 
                              ", applying track permissions directly to database");
                }
                
                // CRITICAL FIX: Don't get the area and call setTrackPermissions, which would cause recursion.
                // Instead, save directly to the database and update caches
                
                // 1. Save directly to database
                databaseManager.saveTrackPermissions(areaName, trackName, new HashMap<>(permissions));
                
                // 2. Update cache
                String cacheKey = PermissionCache.createTrackKey(areaName, trackName);
                permissionCache.cacheTrackPermissions(cacheKey, new HashMap<>(permissions));
                
                // 3. Invalidate area caches
                plugin.getAreaManager().invalidateAreaCache(areaName);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Directly saved track permissions to database, bypassing Area object calls");
                }
                
                // Success - no need to throw an exception
                return;
            }
            
            // Normal path - use the direct method to avoid area recreation
            boolean success = plugin.getAreaManager().directUpdateTrackPermissions(areaName, trackName, permissions);
            
            if (!success) {
                // Before throwing an exception, verify if the permissions were actually applied
                // This could happen if another thread already applied the permissions
                Area area = plugin.getArea(areaName);
                if (area != null) {
                    Map<String, Boolean> currentPerms = area.getTrackPermissions(trackName);
                    if (currentPerms != null && !currentPerms.isEmpty()) {
                        // Permissions exist, so they might have been applied by another thread
                        if (plugin.isDebugMode()) {
                            plugin.debug("Track permissions already exist for " + trackName + 
                                      " in area " + areaName + ", verifying consistency");
                        }
                        
                        // Check if all requested permissions are set correctly
                        boolean allMatch = true;
                        for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
                            Boolean currentValue = currentPerms.get(entry.getKey());
                            if (currentValue == null || !currentValue.equals(entry.getValue())) {
                                allMatch = false;
                                break;
                            }
                        }
                        
                        if (allMatch) {
                            // All permissions match, so no need to throw an exception
                            if (plugin.isDebugMode()) {
                                plugin.debug("All track permissions match expected values, operation successful");
                            }
                            return;
                        }
                    }
                }
                
                // If we get here, the permissions weren't applied correctly
                throw new DatabaseException("Failed to update track permissions using direct method");
            }
            
            // Invalidation is handled by the direct method
        } catch (Exception e) {
            plugin.getLogger().error("Failed to update track permissions for " + trackName, e);
            throw new DatabaseException("Failed to update track permissions", e);
        }
    }
    
    /**
     * Sets track permissions for an area
     * @param area The area to set permissions for
     * @param trackName The track name
     * @param permissions The permissions to set
     */
    public void setTrackPermissions(Area area, String trackName, Map<String, Boolean> permissions) {
        if (area == null || trackName == null || permissions == null) {
            return;
        }
        
        try {
            // Save to database using the string version
            setTrackPermissions(area.getName(), trackName, permissions);
        } catch (Exception e) {
            logger.error("Failed to save track permissions for track {} in area {}", 
                trackName, area.getName(), e);
        }
    }
    
    /**
     * Sets track permissions for an area with force option
     * @param area The area to set permissions for
     * @param trackName The track name
     * @param permissions The permissions to set
     * @param force Whether to force the permission change (ignored in this implementation)
     */
    public void setTrackPermissions(Area area, String trackName, Map<String, Boolean> permissions, boolean force) {
        // The force parameter is ignored in this implementation
        setTrackPermissions(area, trackName, permissions);
    }
    
    /**
     * Invalidate track permissions cache
     */
    public void invalidateTrackPermissions(String areaName, String trackName) {
        if (areaName == null || trackName == null) {
            return;
        }
        
        String cacheKey = PermissionCache.createTrackKey(areaName, trackName);
        permissionCache.invalidateTrackPermissions(cacheKey);
    }
    
    // --- Bulk Operations ---
    
    /**
     * Get all player permissions for an area
     */
    public Map<String, Map<String, Boolean>> getAllPlayerPermissions(String areaName) {
        if (areaName == null) {
            return Collections.emptyMap();
        }
        
        try {
            Map<String, Map<String, Boolean>> allPermissions = databaseManager.getAllPlayerPermissions(areaName);
            
            // Cache individual player permissions
            for (Map.Entry<String, Map<String, Boolean>> entry : allPermissions.entrySet()) {
                String cacheKey = PermissionCache.createPlayerKey(areaName, entry.getKey());
                permissionCache.cachePlayerPermissions(cacheKey, entry.getValue());
            }
            
            return allPermissions;
        } catch (DatabaseException e) {
            logger.error("Failed to load all player permissions for area {}", areaName, e);
            return Collections.emptyMap();
        }
    }
    
    /**
     * Get all group permissions for an area
     */
    public Map<String, Map<String, Boolean>> getAllGroupPermissions(String areaName) {
        if (areaName == null) {
            return Collections.emptyMap();
        }
        
        try {
            Map<String, Map<String, Boolean>> allPermissions = databaseManager.getAllGroupPermissions(areaName);
            
            // Cache individual group permissions
            for (Map.Entry<String, Map<String, Boolean>> entry : allPermissions.entrySet()) {
                String cacheKey = PermissionCache.createGroupKey(areaName, entry.getKey());
                permissionCache.cacheGroupPermissions(cacheKey, entry.getValue());
            }
            
            return allPermissions;
        } catch (DatabaseException e) {
            logger.error("Failed to load all group permissions for area {}", areaName, e);
            return Collections.emptyMap();
        }
    }
    
    /**
     * Get all track permissions for an area
     */
    public Map<String, Map<String, Boolean>> getAllTrackPermissions(String areaName) {
        if (areaName == null) {
            return Collections.emptyMap();
        }
        
        try {
            Map<String, Map<String, Boolean>> allPermissions = databaseManager.getAllTrackPermissions(areaName);
            
            // Cache individual track permissions
            for (Map.Entry<String, Map<String, Boolean>> entry : allPermissions.entrySet()) {
                String cacheKey = PermissionCache.createTrackKey(areaName, entry.getKey());
                permissionCache.cacheTrackPermissions(cacheKey, entry.getValue());
            }
            
            return allPermissions;
        } catch (DatabaseException e) {
            logger.error("Failed to load all track permissions for area {}", areaName, e);
            return Collections.emptyMap();
        }
    }
    
    /**
     * Delete all permissions for an area.
     * The permissions will be completely removed from the database.
     * 
     * @param areaName Name of the area to delete permissions for
     * @throws DatabaseException If there is a database error
     */
    public void deleteAreaPermissions(String areaName) throws DatabaseException {
        deleteAreaPermissions(areaName, false);
    }
    
    /**
     * Delete all permissions for an area, optionally preserving player permissions
     * for later restoration.
     * 
     * @param areaName Name of the area to delete permissions for
     * @param preservePlayerPermissions If true, player permissions will be returned
     * @return Map of player permissions if preservePlayerPermissions is true, null otherwise
     * @throws DatabaseException If there is a database error
     */
    public Map<String, Map<String, Boolean>> deleteAreaPermissions(String areaName, boolean preservePlayerPermissions) 
            throws DatabaseException {
        if (areaName == null) {
            return null;
        }
        
        // Extra debug: Log who's deleting permissions to help diagnose issues
        if (plugin.isDebugMode()) {
            plugin.debug("PERMISSION DELETION REQUESTED for area " + areaName + 
                        ", preservePlayerPermissions=" + preservePlayerPermissions);
            
            // Get stack trace to see who's calling
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            StringBuilder traceLog = new StringBuilder("Permission deletion call trace:");
            for (int i = 2; i < Math.min(12, stackTrace.length); i++) {
                traceLog.append("\n  ").append(i-1).append(": ").append(stackTrace[i]);
            }
            plugin.debug(traceLog.toString());
            
            // Check if this is a permission-only operation
            if (isPermissionOnlyOperation()) {
                plugin.debug("WARNING: Permission deletion during a permission-only operation!");
            }
        }
        
        // Get current player permissions before deleting if needed
        Map<String, Map<String, Boolean>> playerPermissions = null;
        if (preservePlayerPermissions) {
            // Try to get permissions from cache first
            // If not found, get from database
            String cacheKey = areaName.toLowerCase();
            Set<String> playerKeys = permissionCache.getPlayerKeysForArea(cacheKey);
            if (playerKeys != null && !playerKeys.isEmpty()) {
                playerPermissions = new HashMap<>();
                for (String playerName : playerKeys) {
                    String playerKey = PermissionCache.createPlayerKey(areaName, playerName);
                    Map<String, Boolean> perms = permissionCache.getPlayerPermissions(playerKey);
                    if (perms != null && !perms.isEmpty()) {
                        playerPermissions.put(playerName, new HashMap<>(perms));
                    }
                }
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Preserved " + playerPermissions.size() + " player permissions from cache for area " + areaName);
                    
                    // Show detailed permission count
                    for (Map.Entry<String, Map<String, Boolean>> entry : playerPermissions.entrySet()) {
                        plugin.debug("  Player " + entry.getKey() + ": " + entry.getValue().size() + " permissions");
                    }
                }
            }
        }
        
        // If no permissions found in cache but we want to preserve them, get from database
        if (preservePlayerPermissions && (playerPermissions == null || playerPermissions.isEmpty())) {
            playerPermissions = databaseManager.deleteAreaPermissions(areaName, true);
            
            if (plugin.isDebugMode() && playerPermissions != null) {
                plugin.debug("Preserved " + playerPermissions.size() + " player permissions from database for area " + areaName);
                
                // Show detailed permission count
                for (Map.Entry<String, Map<String, Boolean>> entry : playerPermissions.entrySet()) {
                    plugin.debug("  Player " + entry.getKey() + ": " + entry.getValue().size() + " permissions");
                }
            }
        } else {
            // Delete without preserving if we already have them from cache
            databaseManager.deleteAreaPermissions(areaName, false);
        }
        
        // Invalidate caches for this area
        permissionCache.invalidateArea(areaName);
        
        // Clean up updated tracking
        updatedPlayerPermissions.remove(areaName);
        
        return playerPermissions;
    }
    
    /**
     * Rename area permissions (when an area is renamed)
     */
    public void renameArea(String oldName, String newName) throws DatabaseException {
        if (oldName == null || newName == null) {
            return;
        }
        
        databaseManager.renameAreaPermissions(oldName, newName);
        
        // Invalidate caches for old area
        permissionCache.invalidateArea(oldName);
    }
    
    // --- Cache Management ---
    
    /**
     * Invalidate all caches for an area
     */
    public void invalidateCache(String areaName) {
        if (areaName == null) {
            return;
        }
        
        permissionCache.invalidateArea(areaName);
        permissionChecker.invalidateCache(areaName);
    }
    
    /**
     * Invalidate all caches
     */
    public void invalidateCache() {
        permissionCache.invalidateAll();
        permissionChecker.invalidateCache();
    }
    
    // --- Area Synchronization ---
    
    /**
     * Synchronize permissions when loading an area
     */
    public void synchronizeOnLoad(Area area) {
        if (area == null) {
            return;
        }
        
        try {
            synchronizePermissions(area, SyncDirection.FROM_DATABASE);
        } catch (DatabaseException e) {
            logger.error("Failed to synchronize permissions on load for area {}", area.getName(), e);
        }
    }
    
    /**
     * Check if the operation is only modifying permissions which shouldn't trigger area recreation
     * Permission operations use a separate database and should not cause area recreation
     */
    public boolean isPermissionOnlyOperation() {
        // FIRST CHECK: Thread-local marker for permission operations
        // This is the most reliable way to identify permission operations
        Set<String> processingSet = PROCESSING_PERMISSIONS.get();
        if (!processingSet.isEmpty()) {
            // If we're already processing permissions, this is a permission-only operation
            return true;
        }
        
        // Also check for stack trace markers from permission operations
        // Get the current stack trace to detect if we're being called from an Area permission method
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        String className = null;
        String methodName = null;
        
        // Get the class and method that's calling us
        for (int i = 2; i < stackTrace.length; i++) {
            className = stackTrace[i].getClassName();
            methodName = stackTrace[i].getMethodName();
            
            // If we're called from an Area class with a permission method
            if (className.contains("Area") && 
                (methodName.contains("Permission") || methodName.contains("permission"))) {
                if (plugin.isDebugMode()) {
                    plugin.debug("Detected permission-only operation from " + className + "." + methodName);
                }
                return true;
            }
        }
        
        // SECOND CHECK: Stack trace analysis
        // Check for clear permission method names in the stack trace
        for (StackTraceElement element : stackTrace) {
            methodName = element.getMethodName();
            className = element.getClassName();
            
            // Look for permission-related methods
            if ((methodName.contains("Permission") || methodName.contains("permission")) && 
                (methodName.startsWith("set") || methodName.startsWith("get") || 
                 methodName.startsWith("save") || methodName.startsWith("update") ||
                 methodName.startsWith("synchronize"))) {
                
                // If we're directly in a permission method, this is a permission-only operation
                if (plugin.isDebugMode()) {
                    plugin.debug("Detected permission operation from method: " + className + "." + methodName);
                }
                return true;
            }
            
            // Also check for form handler classes that work with permissions
            if (className.contains("Permission") || 
                (className.contains("form") && className.contains("handler") && 
                 (methodName.contains("Permission") || methodName.contains("permission")))) {
                return true;
            }
        }
        
        // NOT a permission-only operation, allow area recreation
        return false;
    }
    
    /**
     * Synchronize permissions when saving an area
     * This is called from the DatabaseManager.saveArea and DatabaseManager.updateArea methods
     */
    public void synchronizeOnSave(Area area) throws DatabaseException {
        if (area == null) {
            return;
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("Starting permission synchronization on save for area: " + area.getName());
        }
        
        // We only want to save permissions to the database here
        try {
            // Check if this is a permission-only operation that shouldn't cause area recreation
            boolean permissionOnly = isPermissionOnlyOperation();
            if (permissionOnly && plugin.isDebugMode()) {
                plugin.debug("Detected permission-only operation - will skip area recreation");
            }
            
            synchronizePermissions(area, SyncDirection.TO_DATABASE);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Successfully synchronized permissions to database for area: " + area.getName());
            }
        } catch (DatabaseException e) {
            // Log the error but don't throw it - we don't want to fail the main area save operation
            logger.error("Failed to synchronize permissions on save for area: " + area.getName(), e);
            
            if (plugin.isDebugMode()) {
                plugin.debug("ERROR: Failed to synchronize permissions for area: " + area.getName());
                plugin.debug("  Error: " + e.getMessage());
                plugin.debug("  Will attempt to force flush the permissions to ensure persistence");
            }
            
            // Try to force flush the permissions
            try {
                forceFlushPermissions();
            } catch (Exception flushEx) {
                logger.error("Failed to force flush permissions after sync error", flushEx);
            }
        }
    }
    
    /**
     * Synchronize permissions for an area from database to area object
     * or from area object to database based on the direction
     */
    public void synchronizePermissions(Area area, SyncDirection direction) throws DatabaseException {
        if (area == null) {
            return;
        }
        
        String areaName = area.getName();
        
        // Prevent recursion - check if we're already synchronizing this area
        String syncKey = "sync:" + areaName + ":" + direction;
        Set<String> processingSet = PROCESSING_PERMISSIONS.get();
        
        if (processingSet.contains(syncKey)) {
            if (plugin.isDebugMode()) {
                plugin.debug("Preventing recursive permission synchronization for area " + areaName + " (direction: " + direction + ")");
            }
            return; // Skip the synchronization to prevent recursive loop
        }
        
        processingSet.add(syncKey);
        try {
            if (plugin.isDebugMode()) {
                plugin.debug("Synchronizing permissions for area " + areaName + " (direction: " + direction + ")");
            }
            
            // Track errors for error reporting
            List<String> errors = new ArrayList<>();
            
            if (direction == SyncDirection.FROM_DATABASE || direction == SyncDirection.BIDIRECTIONAL) {
                try {
                    // Before fetching, check if we have permissions in memory that should be preserved
                    Map<String, Map<String, Boolean>> existingPlayerPerms = area.getInternalPlayerPermissions();
                    boolean hasExistingPermissions = existingPlayerPerms != null && !existingPlayerPerms.isEmpty();
                    
                    if (hasExistingPermissions && plugin.isDebugMode()) {
                        plugin.debug("  Area has existing player permissions in memory before database sync: " + 
                                   existingPlayerPerms.size() + " players");
                        for (Map.Entry<String, Map<String, Boolean>> entry : existingPlayerPerms.entrySet()) {
                            plugin.debug("    Player " + entry.getKey() + ": " + entry.getValue().size() + " permissions");
                        }
                    }
                    
                    // Load all permissions from the database
                    Map<String, Map<String, Boolean>> playerPermissions = getAllPlayerPermissions(areaName);
                    Map<String, Map<String, Boolean>> groupPermissions = getAllGroupPermissions(areaName);
                    Map<String, Map<String, Boolean>> trackPermissions = getAllTrackPermissions(areaName);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Loaded from database: " +
                            playerPermissions.size() + " player entries, " +
                            groupPermissions.size() + " group entries, " +
                            trackPermissions.size() + " track entries");
                        
                        if (!playerPermissions.isEmpty()) {
                            plugin.debug("  Player permissions detail:");
                            for (Map.Entry<String, Map<String, Boolean>> entry : playerPermissions.entrySet()) {
                                plugin.debug("    Player " + entry.getKey() + ": " + entry.getValue().size() + " permissions");
                            }
                        }
                    }
                    
                    // If we got empty player permissions from the database but we have in-memory data,
                    // don't overwrite with empty data - this is critical to prevent permission loss
                    if (playerPermissions.isEmpty() && hasExistingPermissions) {
                        if (plugin.isDebugMode()) {
                            plugin.debug("  Database returned empty player permissions but area has " + 
                                       existingPlayerPerms.size() + " players in memory - keeping memory data");
                            
                            // Verify from database directly to see if this is a real issue
                            try {
                                Connection conn = getConnection();
                                try (PreparedStatement stmt = conn.prepareStatement(
                                         "SELECT COUNT(*) FROM player_permissions WHERE area_name = ?")) {
                                    stmt.setString(1, areaName);
                                    ResultSet rs = stmt.executeQuery();
                                    if (rs.next()) {
                                        int count = rs.getInt(1);
                                        plugin.debug("  Direct database query found " + count + 
                                                   " player permission entries for area " + areaName);
                                        
                                        if (count > 0) {
                                            plugin.debug("  WARNING: Database has permissions but loader returned empty map!" +
                                                       " This could indicate a database loading issue");
                                        }
                                    }
                                    rs.close();
                                }
                                conn.close();
                            } catch (Exception e) {
                                plugin.debug("  Failed to perform direct database check: " + e.getMessage());
                            }
                        }
                        
                        // Use the existing permissions instead
                        playerPermissions = new HashMap<>(existingPlayerPerms);
                    }
                    
                    // Update area's permissions in a single call to avoid multiple invalidations
                    area.updateInternalPermissions(playerPermissions, groupPermissions, trackPermissions);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Successfully updated area object with permissions");
                        // Verify permissions were actually updated
                        Map<String, Map<String, Boolean>> verifiedPerms = area.getInternalPlayerPermissions();
                        if (verifiedPerms != null) {
                            plugin.debug("  Verified area now has " + verifiedPerms.size() + " player permissions");
                            for (Map.Entry<String, Map<String, Boolean>> entry : verifiedPerms.entrySet()) {
                                plugin.debug("    Player " + entry.getKey() + ": " + entry.getValue().size() + " permissions");
                            }
                        } else {
                            plugin.debug("  WARNING: Area still has null player permissions after update!");
                        }
                    }
                } catch (Exception e) {
                    String error = "Failed to load permissions from database: " + e.getMessage();
                    logger.error(error, e);
                    errors.add(error);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("  " + error);
                        plugin.debug("  Error details: " + e.toString());
                    }
                }
            }
            
            if (direction == SyncDirection.TO_DATABASE || direction == SyncDirection.BIDIRECTIONAL) {
                try {
                    // This synchronizes the area's permissions to the database
                    // Get player permissions from the area
                    Map<String, Map<String, Boolean>> playerPermissions = area.getPlayerPermissions();
                    
                    // Get group permissions from the area
                    Map<String, Map<String, Boolean>> groupPermissions = area.getGroupPermissions();
                    
                    // Get track permissions from the area
                    Map<String, Map<String, Boolean>> trackPermissions = area.getTrackPermissions();
                    
                    if (plugin.isDebugMode()) {
                        int playerEntries = 0;
                        int groupEntries = 0;
                        int trackEntries = 0;
                        
                        for (Map<String, Boolean> perms : playerPermissions.values()) {
                            playerEntries += perms.size();
                        }
                        
                        for (Map<String, Boolean> perms : groupPermissions.values()) {
                            groupEntries += perms.size();
                        }
                        
                        for (Map<String, Boolean> perms : trackPermissions.values()) {
                            trackEntries += perms.size();
                        }
                        
                        plugin.debug("  Saving to database: " +
                            playerPermissions.size() + " players (" + playerEntries + " total permissions), " +
                            groupPermissions.size() + " groups (" + groupEntries + " total permissions), " +
                            trackPermissions.size() + " tracks (" + trackEntries + " total permissions)");
                        
                        // FIX: Log more details about player permissions
                        if (!playerPermissions.isEmpty()) {
                            plugin.debug("  Player permissions detail for database save:");
                            for (Map.Entry<String, Map<String, Boolean>> entry : playerPermissions.entrySet()) {
                                plugin.debug("    Player " + entry.getKey() + ": " + entry.getValue().size() + " permissions");
                            }
                        }
                    }
                    
                    // Save each permission type with its own error handling
                    int successCount = 0;
                    
                    // Save player permissions
                    for (Map.Entry<String, Map<String, Boolean>> entry : playerPermissions.entrySet()) {
                        try {
                            databaseManager.savePlayerPermissions(areaName, entry.getKey(), entry.getValue());
                            successCount++;
                        } catch (Exception e) {
                            String error = "Failed to save player permissions for " + entry.getKey() + ": " + e.getMessage();
                            logger.error(error, e);
                            errors.add(error);
                            
                            if (plugin.isDebugMode()) {
                                plugin.debug("  " + error);
                            }
                        }
                    }
                    
                    // Save group permissions
                    for (Map.Entry<String, Map<String, Boolean>> entry : groupPermissions.entrySet()) {
                        try {
                            databaseManager.saveGroupPermissions(areaName, entry.getKey(), entry.getValue());
                            successCount++;
                        } catch (Exception e) {
                            String error = "Failed to save group permissions for " + entry.getKey() + ": " + e.getMessage();
                            logger.error(error, e);
                            errors.add(error);
                            
                            if (plugin.isDebugMode()) {
                                plugin.debug("  " + error);
                            }
                        }
                    }
                    
                    // Save track permissions
                    for (Map.Entry<String, Map<String, Boolean>> entry : trackPermissions.entrySet()) {
                        try {
                            databaseManager.saveTrackPermissions(areaName, entry.getKey(), entry.getValue());
                            successCount++;
                        } catch (Exception e) {
                            String error = "Failed to save track permissions for " + entry.getKey() + ": " + e.getMessage();
                            logger.error(error, e);
                            errors.add(error);
                            
                            if (plugin.isDebugMode()) {
                                plugin.debug("  " + error);
                            }
                        }
                    }
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Successfully saved " + successCount + " permission entries to database");
                        plugin.debug("  Forcing database checkpoint to ensure persistence");
                    }
                    
                    // Force WAL checkpoint to ensure changes are persisted
                    databaseManager.forceWalCheckpoint();
                    
                    // Invalidate cache to ensure we get fresh data next time
                    invalidateCache(areaName);
                } catch (Exception e) {
                    String error = "Failed to save permissions to database: " + e.getMessage();
                    logger.error(error, e);
                    errors.add(error);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("  " + error);
                    }
                }
            }
            
            if (!errors.isEmpty()) {
                throw new DatabaseException("Failed to synchronize permissions for area " + areaName + 
                                           ": " + String.join("; ", errors));
            }
        } finally {
            // Clean up - remove from processing set
            processingSet.remove(syncKey);
        }
    }
    
    /**
     * Synchronize permissions from an area to the database
     */
    public void synchronizeFromArea(Area area) {
        if (area == null) {
            return;
        }

        String syncKey = "sync_operation:" + area.getName();
        Set<String> processingSet = area.getPermissionOperations();
        boolean shouldRemove = false;

        if (!processingSet.contains(syncKey)) {
            processingSet.add(syncKey);
            shouldRemove = true;
            
            try {
                // Get permissions from area
                Map<String, Map<String, Boolean>> playerPerms = area.getPlayerPermissions();
                Map<String, Map<String, Boolean>> groupPerms = area.getGroupPermissions();
                Map<String, Map<String, Boolean>> trackPerms = area.getTrackPermissions();
                
                // Synchronize to database
                try {
                    synchronizePermissions(area, SyncDirection.TO_DATABASE);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Synchronized permissions from area " + area.getName() + " to database");
                    }
                } catch (DatabaseException e) {
                    plugin.getLogger().error("Failed to synchronize permissions from area " + area.getName(), e);
                }
            } finally {
                if (shouldRemove) {
                    processingSet.remove(syncKey);
                }
            }
        }
    }
    
    // --- Additional Methods ---
    
    /**
     * Check if a player has updated permissions for an area
     */
    public boolean hasUpdatedPlayerPermissions(String areaName, String playerName) {
        if (areaName == null || playerName == null) {
            return false;
        }
        
        Set<String> playersForArea = updatedPlayerPermissions.get(areaName);
        return playersForArea != null && playersForArea.contains(playerName);
    }
    
    /**
     * Check if a group has updated permissions
     */
    public boolean hasUpdatedGroupPermissions(String groupName) {
        return groupName != null && updatedGroupPermissions.contains(groupName);
    }
    
    /**
     * Check if a track has updated permissions
     */
    public boolean hasUpdatedTrackPermissions(String trackName) {
        return trackName != null && updatedTrackPermissions.contains(trackName);
    }
    
    /**
     * Get areas that have permissions for a group
     */
    public List<String> getAreasWithGroupPermissions(String groupName) {
        if (groupName == null) {
            return Collections.emptyList();
        }
        
        try {
            return databaseManager.getAreasWithGroupPermissions(groupName);
        } catch (DatabaseException e) {
            logger.error("Failed to get areas with group permissions for {}", groupName, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Get areas that have permissions for a track
     */
    public List<String> getAreasWithTrackPermissions(String trackName) {
        if (trackName == null) {
            return Collections.emptyList();
        }
        
        try {
            return databaseManager.getAreasWithTrackPermissions(trackName);
        } catch (DatabaseException e) {
            logger.error("Failed to get areas with track permissions for {}", trackName, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Get the permission checker instance
     */
    public PermissionChecker getPermissionChecker() {
        return permissionChecker;
    }
    
    /**
     * Gets a database connection from the database manager
     * @return A SQL connection
     * @throws SQLException If there is an error getting the connection
     */
    public Connection getConnection() throws SQLException {
        return databaseManager.getConnection();
    }
    
    /**
     * Saves all permissions to the database
     * This iterates through all areas and ensures their permissions are synchronized
     */
    public void saveAllPermissions() {
        if (plugin.isDebugMode()) {
            plugin.debug("Starting to save all permission overrides to the database...");
        }
        
        int success = 0;
        int failure = 0;
        List<String> failedAreas = new ArrayList<>();
        
        try {
            // Get all areas
            List<Area> allAreas = plugin.getAreaManager().getAllAreas();
            
            if (plugin.isDebugMode()) {
                plugin.debug("Found " + allAreas.size() + " areas to process");
            }
            
            // Process each area
            for (Area area : allAreas) {
                String areaName = area.getName();
                
                try {
                    if (plugin.isDebugMode()) {
                        plugin.debug("Synchronizing permissions for area: " + areaName);
                    }
                    
                    synchronizePermissions(area, SyncDirection.TO_DATABASE);
                    success++;
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Successfully synchronized permissions for area: " + areaName);
                    }
                } catch (Exception e) {
                    failure++;
                    failedAreas.add(areaName);
                    logger.error("Failed to save permissions for area: " + areaName, e);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Failed to synchronize permissions for area: " + areaName);
                        plugin.debug("  Error: " + e.getMessage());
                    }
                }
            }
            
            // Force a checkpoint to ensure all changes are written
            try {
                if (plugin.isDebugMode()) {
                    plugin.debug("Forcing final database checkpoint to ensure persistence");
                }
                
                databaseManager.checkpoint();
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Checkpoint completed successfully");
                }
            } catch (Exception e) {
                logger.error("Failed to perform final database checkpoint", e);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Failed to perform final checkpoint: " + e.getMessage());
                }
            }
            
            if (plugin.isDebugMode() || success > 0 || failure > 0) {
                String message = String.format("Permission save complete: %d areas successful, %d areas failed", 
                    success, failure);
                
                if (failure > 0) {
                    message += " (Failed areas: " + String.join(", ", failedAreas) + ")";
                }
                
                logger.info(message);
                
                if (plugin.isDebugMode()) {
                    plugin.debug(message);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to save all permission overrides", e);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Critical error during permission save: " + e.getMessage());
            }
        }
    }
    
    /**
     * Forces a database checkpoint and ensures all permissions are flushed to disk.
     * Call this method after setting important permissions to ensure they're persisted.
     */
    public void forceFlushPermissions() {
        try {
            // Run a WAL checkpoint to ensure changes are written to the main database file
            databaseManager.checkpoint();
            
            if (plugin.isDebugMode()) {
                plugin.debug("Forced permission database checkpoint to ensure changes are persisted");
            }
        } catch (Exception e) {
            logger.error("Failed to force flush permissions to disk", e);
        }
    }
    
    /**
     * Rebuilds the permission database from scratch.
     * This is a drastic measure to be used only when the database is corrupted.
     * After rebuilding the database, this method will try to repopulate it with
     * permissions from all areas.
     */
    public void rebuildPermissionDatabase() {
        logger.warn("Rebuilding permission database and repopulating from areas...");
        
        try {
            // Backup the current database first
            databaseManager.backupDatabase();
            
            // Rebuild the database schema
            databaseManager.rebuildDatabase();
            
            // Clear all caches
            invalidateCache();
            
            // Repopulate the database with permissions from all areas
            for (Area area : plugin.getAreaManager().getAllAreas()) {
                try {
                    logger.info("Repopulating permissions for area: " + area.getName());
                    synchronizePermissions(area, SyncDirection.TO_DATABASE);
                } catch (Exception e) {
                    logger.error("Failed to repopulate permissions for area: " + area.getName(), e);
                }
            }
            
            // Force checkpoint to ensure all changes are written
            databaseManager.checkpoint();
            
            logger.info("Permission database rebuild complete");
        } catch (Exception e) {
            logger.error("Failed to rebuild permission database", e);
        }
    }
    
    /**
     * Debug method to dump player permissions directly from the database
     * This bypasses all caches and directly queries the database
     */
    public void debugDumpPlayerPermissions(String areaName, String playerName) throws DatabaseException {
        databaseManager.debugDumpPlayerPermissions(areaName, playerName);
    }
    
    /**
     * Checks if an area's permissions are consistent between the area object and the database.
     * This is useful for debugging purposes.
     * 
     * @param area The area to check
     * @return A report of any inconsistencies found
     */
    public String checkPermissionConsistency(Area area) {
        if (area == null) {
            return "Cannot check consistency for null area";
        }
        
        StringBuilder report = new StringBuilder();
        String areaName = area.getName();
        
        report.append("Permission consistency check for area: ").append(areaName).append("\n");
        
        try {
            // Get permissions from area object
            Map<String, Map<String, Boolean>> areaPlayerPerms = area.getPlayerPermissions();
            Map<String, Map<String, Boolean>> areaGroupPerms = area.getGroupPermissions();
            Map<String, Map<String, Boolean>> areaTrackPerms = area.getTrackPermissions();
            
            // Get permissions from database
            Map<String, Map<String, Boolean>> dbPlayerPerms = getAllPlayerPermissions(areaName);
            Map<String, Map<String, Boolean>> dbGroupPerms = getAllGroupPermissions(areaName);
            Map<String, Map<String, Boolean>> dbTrackPerms = getAllTrackPermissions(areaName);
            
            // Check player permissions
            report.append("Player permissions:\n");
            report.append("  Area object: ").append(areaPlayerPerms.size()).append(" players\n");
            report.append("  Database: ").append(dbPlayerPerms.size()).append(" players\n");
            
            // Find players in area but not in db
            for (String player : areaPlayerPerms.keySet()) {
                if (!dbPlayerPerms.containsKey(player)) {
                    report.append("  Player '").append(player).append("' exists in area object but not in database\n");
                }
            }
            
            // Find players in db but not in area
            for (String player : dbPlayerPerms.keySet()) {
                if (!areaPlayerPerms.containsKey(player)) {
                    report.append("  Player '").append(player).append("' exists in database but not in area object\n");
                }
            }
            
            // Check for differences in player permissions
            for (String player : areaPlayerPerms.keySet()) {
                if (dbPlayerPerms.containsKey(player)) {
                    Map<String, Boolean> areaPerms = areaPlayerPerms.get(player);
                    Map<String, Boolean> dbPerms = dbPlayerPerms.get(player);
                    
                    if (!areaPerms.equals(dbPerms)) {
                        report.append("  Player '").append(player).append("' has different permissions:\n");
                        report.append("    Area permissions size: ").append(areaPerms.size()).append("\n");
                        report.append("    DB permissions size: ").append(dbPerms.size()).append("\n");
                        
                        // Find permissions in area but not in db
                        for (Map.Entry<String, Boolean> entry : areaPerms.entrySet()) {
                            String perm = entry.getKey();
                            Boolean value = entry.getValue();
                            
                            if (!dbPerms.containsKey(perm)) {
                                report.append("    Permission '").append(perm)
                                      .append("' with value '").append(value)
                                      .append("' exists in area object but not in database\n");
                            } else if (!dbPerms.get(perm).equals(value)) {
                                report.append("    Permission '").append(perm)
                                      .append("' has different values: area=").append(value)
                                      .append(", db=").append(dbPerms.get(perm)).append("\n");
                            }
                        }
                        
                        // Find permissions in db but not in area
                        for (Map.Entry<String, Boolean> entry : dbPerms.entrySet()) {
                            String perm = entry.getKey();
                            if (!areaPerms.containsKey(perm)) {
                                report.append("    Permission '").append(perm)
                                      .append("' with value '").append(entry.getValue())
                                      .append("' exists in database but not in area object\n");
                            }
                        }
                    }
                }
            }
            
            // Similar checks for group and track permissions
            // Group permissions check
            report.append("Group permissions:\n");
            report.append("  Area object: ").append(areaGroupPerms.size()).append(" groups\n");
            report.append("  Database: ").append(dbGroupPerms.size()).append(" groups\n");
            
            // Find groups in area but not in db
            for (String group : areaGroupPerms.keySet()) {
                if (!dbGroupPerms.containsKey(group)) {
                    report.append("  Group '").append(group).append("' exists in area object but not in database\n");
                }
            }
            
            // Find groups in db but not in area
            for (String group : dbGroupPerms.keySet()) {
                if (!areaGroupPerms.containsKey(group)) {
                    report.append("  Group '").append(group).append("' exists in database but not in area object\n");
                }
            }
            
            // Track permissions check
            report.append("Track permissions:\n");
            report.append("  Area object: ").append(areaTrackPerms.size()).append(" tracks\n");
            report.append("  Database: ").append(dbTrackPerms.size()).append(" tracks\n");
            
            // Find tracks in area but not in db
            for (String track : areaTrackPerms.keySet()) {
                if (!dbTrackPerms.containsKey(track)) {
                    report.append("  Track '").append(track).append("' exists in area object but not in database\n");
                }
            }
            
            // Find tracks in db but not in area
            for (String track : dbTrackPerms.keySet()) {
                if (!areaTrackPerms.containsKey(track)) {
                    report.append("  Track '").append(track).append("' exists in database but not in area object\n");
                }
            }
            
            report.append("Consistency check complete.\n");
            
        } catch (Exception e) {
            report.append("Error during consistency check: ").append(e.getMessage()).append("\n");
            logger.error("Error checking permission consistency for area " + areaName, e);
        }
        
        return report.toString();
    }
    
    /**
     * Get all player permissions for an area directly from the database, bypassing cache
     * 
     * @param areaName Name of the area to get permissions for
     * @return Map of player permissions or null if none found
     */
    public Map<String, Map<String, Boolean>> getAllPlayerPermissionsFromDatabase(String areaName) {
        if (areaName == null) {
            return null;
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("Getting player permissions directly from database for area: " + areaName);
        }
        
        try {
            return databaseManager.getAllPlayerPermissions(areaName);
        } catch (DatabaseException e) {
            plugin.getLogger().error("Failed to get player permissions from database for area " + areaName, e);
            return null;
        }
    }
    
    /**
     * Forces WAL checkpoint to ensure changes are written to disk
     * This is useful when making critical permission changes
     */
    public void forceWalCheckpoint() throws DatabaseException {
        if (databaseManager != null) {
            databaseManager.forceWalCheckpoint();
        }
    }
    
    @Override
    public void close() {
        isShuttingDown.set(true);
        
        try {
            scheduler.shutdown();
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        
        databaseManager.close();
    }
}