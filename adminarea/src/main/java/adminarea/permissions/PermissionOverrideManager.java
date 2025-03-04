package adminarea.permissions;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.exception.DatabaseException;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Manages permission overrides for players, groups, and tracks using a dedicated database.
 * This centralizes all permission storage outside of the area objects themselves.
 */
public class PermissionOverrideManager implements AutoCloseable {
    private final AdminAreaProtectionPlugin plugin;
    private final HikariDataSource dataSource;
    private final Cache<String, Map<String, Boolean>> playerPermCache;
    private final Cache<String, Map<String, Boolean>> groupPermCache;
    private final Cache<String, Map<String, Boolean>> trackPermCache;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private final Object dbLock = new Object();
    private final PermissionChecker permissionChecker;
    
    // Define the SyncDirection enum used in Area.java
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
    
    // Track updated permissions for change detection
    private final Map<String, Set<String>> updatedPlayerPermissions = new ConcurrentHashMap<>();
    private final Set<String> updatedGroupPermissions = ConcurrentHashMap.newKeySet();
    private final Set<String> updatedTrackPermissions = ConcurrentHashMap.newKeySet();
    
    private static final int CACHE_SIZE = 200;
    private static final long CACHE_DURATION = TimeUnit.MINUTES.toMillis(5);
    private static final String DB_FILE = "permission_overrides.db";

    public PermissionOverrideManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.dataSource = initializeDataSource();
        this.playerPermCache = CacheBuilder.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_DURATION, TimeUnit.MILLISECONDS)
            .build();
        this.groupPermCache = CacheBuilder.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_DURATION, TimeUnit.MILLISECONDS)
            .build();
        this.trackPermCache = CacheBuilder.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_DURATION, TimeUnit.MILLISECONDS)
            .build();
        this.scheduler = Executors.newScheduledThreadPool(1);
        // Pass only the plugin instance, not this (to avoid circular dependency)
        this.permissionChecker = new PermissionChecker(plugin);
        
        initializeDatabase();
        setupScheduledTasks();
    }
    
    private HikariDataSource initializeDataSource() {
        try {
            HikariConfig config = new HikariConfig();
            Path dbPath = plugin.getDataFolder().toPath().resolve(DB_FILE);
            config.setJdbcUrl("jdbc:sqlite:" + dbPath);
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(3);
            config.setIdleTimeout(300000); // 5 minutes
            config.setMaxLifetime(600000); // 10 minutes
            config.setConnectionTimeout(30000);
            config.setConnectionTestQuery("SELECT 1");
            return new HikariDataSource(config);
        } catch (Exception e) {
            plugin.getLogger().error("Failed to initialize permission override database", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }
    
    private void initializeDatabase() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            
            conn.setAutoCommit(false);
            try {
                // Create player permissions table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_permissions (
                        area_name TEXT NOT NULL,
                        player_name TEXT NOT NULL,
                        permission TEXT NOT NULL,
                        state BOOLEAN NOT NULL,
                        last_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (area_name, player_name, permission)
                    )
                """);
                
                // Create group permissions table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS group_permissions (
                        area_name TEXT NOT NULL,
                        group_name TEXT NOT NULL,
                        permission TEXT NOT NULL,
                        state BOOLEAN NOT NULL,
                        last_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (area_name, group_name, permission)
                    )
                """);
                
                // Create track permissions table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS track_permissions (
                        area_name TEXT NOT NULL,
                        track_name TEXT NOT NULL,
                        permission TEXT NOT NULL,
                        state BOOLEAN NOT NULL,
                        last_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (area_name, track_name, permission)
                    )
                """);
                
                // Create indexes
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_player_perms_area ON player_permissions(area_name)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_player_perms_player ON player_permissions(player_name)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_group_perms_area ON group_permissions(area_name)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_group_perms_group ON group_permissions(group_name)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_track_perms_area ON track_permissions(area_name)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_track_perms_track ON track_permissions(track_name)");
                
                conn.commit();
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Permission override database initialized successfully");
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to initialize permission override database", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }
    
    private void setupScheduledTasks() {
        // Schedule periodic backup
        scheduler.scheduleAtFixedRate(
            this::backupDatabase, 
            6, 6, TimeUnit.HOURS
        );
        
        // Schedule cache cleanup
        scheduler.scheduleAtFixedRate(() -> {
            playerPermCache.cleanUp();
            groupPermCache.cleanUp();
            trackPermCache.cleanUp();
        }, 5, 5, TimeUnit.MINUTES);
    }
    
    // Player permissions
    public Map<String, Boolean> getPlayerPermissions(String areaName, String playerName) {
        String cacheKey = areaName + ":" + playerName;
        Map<String, Boolean> cachedPerms = playerPermCache.getIfPresent(cacheKey);
        if (cachedPerms != null) {
            return new HashMap<>(cachedPerms);
        }
        
        Map<String, Boolean> permissions = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT permission, state FROM player_permissions WHERE area_name = ? AND player_name = ?")) {
            
            stmt.setString(1, areaName);
            stmt.setString(2, playerName);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    permissions.put(rs.getString("permission"), rs.getBoolean("state"));
                }
            }
            
            playerPermCache.put(cacheKey, new HashMap<>(permissions));
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to retrieve player permissions", e);
        }
        
        return permissions;
    }
    
    public void setPlayerPermissions(String areaName, String playerName, Map<String, Boolean> permissions) throws DatabaseException {
        if (plugin.isDebugMode()) {
            plugin.debug("Setting permissions for player " + playerName + " in area " + areaName);
            plugin.debug("  Permissions: " + permissions);
        }
        
        synchronized (dbLock) {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                
                try {
                    // Delete existing permissions
                    try (PreparedStatement deleteStmt = conn.prepareStatement(
                        "DELETE FROM player_permissions WHERE area_name = ? AND player_name = ?")) {
                        deleteStmt.setString(1, areaName);
                        deleteStmt.setString(2, playerName);
                        deleteStmt.executeUpdate();
                    }
                    
                    // Insert new permissions
                    try (PreparedStatement insertStmt = conn.prepareStatement(
                        "INSERT INTO player_permissions (area_name, player_name, permission, state) VALUES (?, ?, ?, ?)")) {
                        for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
                            insertStmt.setString(1, areaName);
                            insertStmt.setString(2, playerName);
                            insertStmt.setString(3, entry.getKey());
                            insertStmt.setBoolean(4, entry.getValue());
                            insertStmt.addBatch();
                        }
                        insertStmt.executeBatch();
                    }
                    
                    conn.commit();
                    
                    // Update cache
                    String cacheKey = areaName + ":" + playerName;
                    playerPermCache.put(cacheKey, new HashMap<>(permissions));
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Successfully saved player permissions to database");
                    }
                } catch (SQLException e) {
                    conn.rollback();
                    throw new DatabaseException("Failed to save player permissions", e);
                }
            } catch (SQLException e) {
                throw new DatabaseException("Failed to save player permissions", e);
            }
        }
    }
    
    // Group permissions
    public Map<String, Boolean> getGroupPermissions(String areaName, String groupName) {
        String cacheKey = areaName + ":" + groupName;
        Map<String, Boolean> cachedPerms = groupPermCache.getIfPresent(cacheKey);
        if (cachedPerms != null) {
            return new HashMap<>(cachedPerms);
        }
        
        Map<String, Boolean> permissions = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT permission, state FROM group_permissions WHERE area_name = ? AND group_name = ?")) {
            
            stmt.setString(1, areaName);
            stmt.setString(2, groupName);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    permissions.put(rs.getString("permission"), rs.getBoolean("state"));
                }
            }
            
            groupPermCache.put(cacheKey, new HashMap<>(permissions));
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to retrieve group permissions", e);
        }
        
        return permissions;
    }
    
    public void setGroupPermissions(String areaName, String groupName, Map<String, Boolean> permissions) throws DatabaseException {
        if (plugin.isDebugMode()) {
            plugin.debug("Setting permissions for group " + groupName + " in area " + areaName);
            plugin.debug("  Permissions: " + permissions);
        }
        
        synchronized (dbLock) {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                
                try {
                    // Delete existing permissions
                    try (PreparedStatement deleteStmt = conn.prepareStatement(
                        "DELETE FROM group_permissions WHERE area_name = ? AND group_name = ?")) {
                        deleteStmt.setString(1, areaName);
                        deleteStmt.setString(2, groupName);
                        deleteStmt.executeUpdate();
                    }
                    
                    // Insert new permissions
                    try (PreparedStatement insertStmt = conn.prepareStatement(
                        "INSERT INTO group_permissions (area_name, group_name, permission, state) VALUES (?, ?, ?, ?)")) {
                        for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
                            insertStmt.setString(1, areaName);
                            insertStmt.setString(2, groupName);
                            insertStmt.setString(3, entry.getKey());
                            insertStmt.setBoolean(4, entry.getValue());
                            insertStmt.addBatch();
                        }
                        insertStmt.executeBatch();
                    }
                    
                    conn.commit();
                    
                    // Update cache
                    String cacheKey = areaName + ":" + groupName;
                    groupPermCache.put(cacheKey, new HashMap<>(permissions));
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Successfully saved group permissions to database");
                    }
                } catch (SQLException e) {
                    conn.rollback();
                    throw new DatabaseException("Failed to save group permissions", e);
                }
            } catch (SQLException e) {
                throw new DatabaseException("Failed to save group permissions", e);
            }
        }
    }
    
    // Track permissions
    public Map<String, Boolean> getTrackPermissions(String areaName, String trackName) {
        String cacheKey = areaName + ":" + trackName;
        Map<String, Boolean> cachedPerms = trackPermCache.getIfPresent(cacheKey);
        if (cachedPerms != null) {
            return new HashMap<>(cachedPerms);
        }
        
        Map<String, Boolean> permissions = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT permission, state FROM track_permissions WHERE area_name = ? AND track_name = ?")) {
            
            stmt.setString(1, areaName);
            stmt.setString(2, trackName);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    permissions.put(rs.getString("permission"), rs.getBoolean("state"));
                }
            }
            
            trackPermCache.put(cacheKey, new HashMap<>(permissions));
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to retrieve track permissions", e);
        }
        
        return permissions;
    }
    
    public void setTrackPermissions(String areaName, String trackName, Map<String, Boolean> permissions) throws DatabaseException {
        if (plugin.isDebugMode()) {
            plugin.debug("Setting permissions for track " + trackName + " in area " + areaName);
            plugin.debug("  Permissions: " + permissions);
        }
        
        synchronized (dbLock) {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                
                try {
                    // Delete existing permissions
                    try (PreparedStatement deleteStmt = conn.prepareStatement(
                        "DELETE FROM track_permissions WHERE area_name = ? AND track_name = ?")) {
                        deleteStmt.setString(1, areaName);
                        deleteStmt.setString(2, trackName);
                        deleteStmt.executeUpdate();
                    }
                    
                    // Insert new permissions
                    try (PreparedStatement insertStmt = conn.prepareStatement(
                        "INSERT INTO track_permissions (area_name, track_name, permission, state) VALUES (?, ?, ?, ?)")) {
                        for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
                            insertStmt.setString(1, areaName);
                            insertStmt.setString(2, trackName);
                            insertStmt.setString(3, entry.getKey());
                            insertStmt.setBoolean(4, entry.getValue());
                            insertStmt.addBatch();
                        }
                        insertStmt.executeBatch();
                    }
                    
                    conn.commit();
                    
                    // Update cache
                    String cacheKey = areaName + ":" + trackName;
                    trackPermCache.put(cacheKey, new HashMap<>(permissions));
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Successfully saved track permissions to database");
                    }
                } catch (SQLException e) {
                    conn.rollback();
                    throw new DatabaseException("Failed to save track permissions", e);
                }
            } catch (SQLException e) {
                throw new DatabaseException("Failed to save track permissions", e);
            }
        }
    }
    
    // Get all permissions for an area
    public Map<String, Map<String, Boolean>> getAllPlayerPermissions(String areaName) {
        Map<String, Map<String, Boolean>> result = new HashMap<>();
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT player_name, permission, state FROM player_permissions WHERE area_name = ?")) {
            
            stmt.setString(1, areaName);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String playerName = rs.getString("player_name");
                    String permission = rs.getString("permission");
                    boolean state = rs.getBoolean("state");
                    
                    result.computeIfAbsent(playerName, k -> new HashMap<>())
                          .put(permission, state);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to retrieve all player permissions", e);
        }
        
        return result;
    }
    
    public Map<String, Map<String, Boolean>> getAllGroupPermissions(String areaName) {
        Map<String, Map<String, Boolean>> result = new HashMap<>();
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT group_name, permission, state FROM group_permissions WHERE area_name = ?")) {
            
            stmt.setString(1, areaName);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String groupName = rs.getString("group_name");
                    String permission = rs.getString("permission");
                    boolean state = rs.getBoolean("state");
                    
                    result.computeIfAbsent(groupName, k -> new HashMap<>())
                          .put(permission, state);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to retrieve all group permissions", e);
        }
        
        return result;
    }
    
    public Map<String, Map<String, Boolean>> getAllTrackPermissions(String areaName) {
        Map<String, Map<String, Boolean>> result = new HashMap<>();
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT track_name, permission, state FROM track_permissions WHERE area_name = ?")) {
            
            stmt.setString(1, areaName);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String trackName = rs.getString("track_name");
                    String permission = rs.getString("permission");
                    boolean state = rs.getBoolean("state");
                    
                    result.computeIfAbsent(trackName, k -> new HashMap<>())
                          .put(permission, state);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to retrieve all track permissions", e);
        }
        
        return result;
    }
    
    // Area deletion and renaming
    public void deleteAreaPermissions(String areaName) throws DatabaseException {
        synchronized (dbLock) {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                
                try {
                    // Delete from all tables
                    try (PreparedStatement stmt1 = conn.prepareStatement("DELETE FROM player_permissions WHERE area_name = ?");
                         PreparedStatement stmt2 = conn.prepareStatement("DELETE FROM group_permissions WHERE area_name = ?");
                         PreparedStatement stmt3 = conn.prepareStatement("DELETE FROM track_permissions WHERE area_name = ?")) {
                        
                        stmt1.setString(1, areaName);
                        stmt1.executeUpdate();
                        
                        stmt2.setString(1, areaName);
                        stmt2.executeUpdate();
                        
                        stmt3.setString(1, areaName);
                        stmt3.executeUpdate();
                    }
                    
                    conn.commit();
                    
                    // Clear caches
                    clearAreaFromCache(areaName);
                    
                } catch (SQLException e) {
                    conn.rollback();
                    throw new DatabaseException("Failed to delete area permissions", e);
                }
            } catch (SQLException e) {
                throw new DatabaseException("Failed to delete area permissions", e);
            }
        }
    }
    
    public void renameArea(String oldName, String newName) throws DatabaseException {
        synchronized (dbLock) {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                
                try {
                    // Update all tables
                    try (PreparedStatement stmt1 = conn.prepareStatement("UPDATE player_permissions SET area_name = ? WHERE area_name = ?");
                         PreparedStatement stmt2 = conn.prepareStatement("UPDATE group_permissions SET area_name = ? WHERE area_name = ?");
                         PreparedStatement stmt3 = conn.prepareStatement("UPDATE track_permissions SET area_name = ? WHERE area_name = ?")) {
                        
                        stmt1.setString(1, newName);
                        stmt1.setString(2, oldName);
                        stmt1.executeUpdate();
                        
                        stmt2.setString(1, newName);
                        stmt2.setString(2, oldName);
                        stmt2.executeUpdate();
                        
                        stmt3.setString(1, newName);
                        stmt3.setString(2, oldName);
                        stmt3.executeUpdate();
                    }
                    
                    conn.commit();
                    
                    // Clear caches
                    clearAreaFromCache(oldName);
                    
                } catch (SQLException e) {
                    conn.rollback();
                    throw new DatabaseException("Failed to rename area permissions", e);
                }
            } catch (SQLException e) {
                throw new DatabaseException("Failed to rename area permissions", e);
            }
        }
    }
    
    // Data migration from old structure
    public void migrateAreaPermissions(Area area) throws DatabaseException {
        if (plugin.isDebugMode()) {
            plugin.debug("Migrating permissions for area " + area.getName());
        }
        
        try {
            // Migrate player permissions
            Map<String, Map<String, Boolean>> playerPerms = area.getPlayerPermissions();
            for (Map.Entry<String, Map<String, Boolean>> entry : playerPerms.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    setPlayerPermissions(area.getName(), entry.getKey(), entry.getValue());
                }
            }
            
            // Migrate group permissions
            Map<String, Map<String, Boolean>> groupPerms = area.getGroupPermissions();
            for (Map.Entry<String, Map<String, Boolean>> entry : groupPerms.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    setGroupPermissions(area.getName(), entry.getKey(), entry.getValue());
                }
            }
            
            // Migrate track permissions
            Map<String, Map<String, Boolean>> trackPerms = area.getTrackPermissions();
            for (Map.Entry<String, Map<String, Boolean>> entry : trackPerms.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    setTrackPermissions(area.getName(), entry.getKey(), entry.getValue());
                }
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("Successfully migrated permissions for area " + area.getName());
            }
        } catch (Exception e) {
            throw new DatabaseException("Failed to migrate permissions for area " + area.getName(), e);
        }
    }
    
    private void clearAreaFromCache(String areaName) {
        // Clear player permissions cache
        playerPermCache.asMap().keySet().removeIf(key -> key.startsWith(areaName + ":"));
        
        // Clear group permissions cache
        groupPermCache.asMap().keySet().removeIf(key -> key.startsWith(areaName + ":"));
        
        // Clear track permissions cache
        trackPermCache.asMap().keySet().removeIf(key -> key.startsWith(areaName + ":"));
    }
    
    private void backupDatabase() {
        if (isShuttingDown.get()) return;
        
        try {
            Path backupDir = plugin.getDataFolder().toPath().resolve("permission_backups");
            Files.createDirectories(backupDir);
            
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            Path backupFile = backupDir.resolve(String.format("permissions_%s.db", timestamp));
            
            Path sourceDbPath = plugin.getDataFolder().toPath().resolve(DB_FILE);
            
            // Create a backup using file copy instead of SQL command
            synchronized (dbLock) {
                // Ensure writes are flushed to disk before copying
                try (Connection conn = dataSource.getConnection();
                     Statement stmt = conn.createStatement()) {
                    // Use PRAGMA to ensure integrity before backup
                    stmt.execute("PRAGMA wal_checkpoint(FULL)");
                }
                
                // Copy the database file
                Files.copy(sourceDbPath, backupFile, StandardCopyOption.REPLACE_EXISTING);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Successfully backed up permissions database to " + backupFile);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to backup permissions database", e);
        }
    }
    
    @Override
    public void close() {
        if (!isShuttingDown.compareAndSet(false, true)) {
            return; // Already shutting down
        }
        
        try {
            // Shutdown scheduler first
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            
            // Perform final backup
            backupDatabase();
            
            // Close data source
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
            }
            
            // Clear caches
            playerPermCache.invalidateAll();
            groupPermCache.invalidateAll();
            trackPermCache.invalidateAll();
            
            if (plugin.isDebugMode()) {
                plugin.debug("Permission override manager closed successfully");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error during permission override manager shutdown", e);
        }
    }
    
    /**
     * Gets the permission checker instance
     * @return The PermissionChecker instance
     */
    public PermissionChecker getPermissionChecker() {
        return permissionChecker;
    }
    
    /**
     * Synchronizes permissions for an area when it's loaded
     * This loads all permissions from the database and updates the area's permission state
     * 
     * @param area The area to synchronize permissions for
     */
    public void synchronizeOnLoad(Area area) {
        if (area == null) return;
        
        if (plugin.isDebugMode()) {
            plugin.debug("Synchronizing permissions on load for area: " + area.getName());
        }
        
        try {
            // Load player permissions
            Map<String, Map<String, Boolean>> playerPerms = getAllPlayerPermissions(area.getName());
            for (Map.Entry<String, Map<String, Boolean>> entry : playerPerms.entrySet()) {
                try {
                    area.setPlayerPermissions(entry.getKey(), entry.getValue());
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Synchronized player permissions for: " + entry.getKey());
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Failed to synchronize player permissions for " + 
                        entry.getKey() + " in area " + area.getName(), e);
                }
            }
            
            // Load group permissions
            Map<String, Map<String, Boolean>> groupPerms = getAllGroupPermissions(area.getName());
            for (Map.Entry<String, Map<String, Boolean>> entry : groupPerms.entrySet()) {
                try {
                    area.setGroupPermissions(entry.getKey(), entry.getValue());
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Synchronized group permissions for: " + entry.getKey());
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Failed to synchronize group permissions for " + 
                        entry.getKey() + " in area " + area.getName(), e);
                }
            }
            
            // Load track permissions
            Map<String, Map<String, Boolean>> trackPerms = getAllTrackPermissions(area.getName());
            for (Map.Entry<String, Map<String, Boolean>> entry : trackPerms.entrySet()) {
                try {
                    area.setTrackPermissions(entry.getKey(), entry.getValue());
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Synchronized track permissions for: " + entry.getKey());
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Failed to synchronize track permissions for " + 
                        entry.getKey() + " in area " + area.getName(), e);
                }
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("  Permission synchronization completed for: " + area.getName());
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error synchronizing permissions for area: " + area.getName(), e);
        }
    }
    
    /**
     * Alias for synchronizeFromArea that follows naming convention in the DatabaseManager
     * @param area The area to synchronize
     * @throws DatabaseException If there is an error synchronizing the area
     */
    public void synchronizeOnSave(Area area) throws DatabaseException {
        synchronizeFromArea(area);
    }
    
    /**
     * Synchronizes permissions from an area to the database
     * This is used when permissions are modified directly on the area object
     * 
     * @param area The area to synchronize permissions from
     */
    public void synchronizeFromArea(Area area) throws DatabaseException {
        if (area == null) return;
        
        if (plugin.isDebugMode()) {
            plugin.debug("Synchronizing permissions from area to database: " + area.getName());
        }
        
        try {
            // Synchronize player permissions
            Map<String, Map<String, Boolean>> playerPerms = area.getPlayerPermissions();
            for (Map.Entry<String, Map<String, Boolean>> entry : playerPerms.entrySet()) {
                setPlayerPermissions(area.getName(), entry.getKey(), entry.getValue());
            }
            
            // Synchronize group permissions
            Map<String, Map<String, Boolean>> groupPerms = area.getGroupPermissions();
            for (Map.Entry<String, Map<String, Boolean>> entry : groupPerms.entrySet()) {
                setGroupPermissions(area.getName(), entry.getKey(), entry.getValue());
            }
            
            // Synchronize track permissions
            Map<String, Map<String, Boolean>> trackPerms = area.getTrackPermissions();
            for (Map.Entry<String, Map<String, Boolean>> entry : trackPerms.entrySet()) {
                setTrackPermissions(area.getName(), entry.getKey(), entry.getValue());
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("  Permission synchronization from area completed: " + area.getName());
            }
        } catch (Exception e) {
            throw new DatabaseException("Error synchronizing permissions from area: " + area.getName(), e);
        }
    }
    
    /**
     * Delete player permissions for a specific player in an area
     * @param areaName The name of the area
     * @param playerName The name of the player
     * @throws DatabaseException If there is an error deleting permissions
     */
    public void deletePlayerPermissions(String areaName, String playerName) throws DatabaseException {
        if (plugin.isDebugMode()) {
            plugin.debug("Deleting permissions for player " + playerName + " in area " + areaName);
        }
        
        synchronized (dbLock) {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                
                try {
                    try (PreparedStatement deleteStmt = conn.prepareStatement(
                        "DELETE FROM player_permissions WHERE area_name = ? AND player_name = ?")) {
                        deleteStmt.setString(1, areaName);
                        deleteStmt.setString(2, playerName);
                        deleteStmt.executeUpdate();
                    }
                    
                    conn.commit();
                    
                    // Update cache
                    String cacheKey = areaName + ":" + playerName;
                    playerPermCache.invalidate(cacheKey);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Successfully deleted player permissions from database");
                    }
                } catch (SQLException e) {
                    conn.rollback();
                    throw new DatabaseException("Failed to delete player permissions", e);
                }
            } catch (SQLException e) {
                throw new DatabaseException("Failed to delete player permissions", e);
            }
        }
    }
    
    /**
     * Get a list of area names that have permissions for a specific group
     * @param groupName The name of the group
     * @return A list of area names with permissions for the group
     */
    public List<String> getAreasWithGroupPermissions(String groupName) {
        List<String> areas = new ArrayList<>();
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT DISTINCT area_name FROM group_permissions WHERE group_name = ?")) {
            
            stmt.setString(1, groupName);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    areas.add(rs.getString("area_name"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to retrieve areas with group permissions", e);
        }
        
        return areas;
    }
    
    /**
     * Get a list of area names that have permissions for a specific track
     * @param trackName The name of the track
     * @return A list of area names with permissions for the track
     */
    public List<String> getAreasWithTrackPermissions(String trackName) {
        List<String> areas = new ArrayList<>();
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT DISTINCT area_name FROM track_permissions WHERE track_name = ?")) {
            
            stmt.setString(1, trackName);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    areas.add(rs.getString("area_name"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to retrieve areas with track permissions", e);
        }
        
        return areas;
    }
    
    /**
     * Invalidate the cache for a specific group's permissions in an area
     * @param areaName The name of the area
     * @param groupName The name of the group
     */
    public void invalidateGroupPermissions(String areaName, String groupName) {
        String cacheKey = areaName + ":" + groupName;
        groupPermCache.invalidate(cacheKey);
        
        // Mark this group as updated for future permission checks
        updatedGroupPermissions.add(groupName);
        
        if (plugin.isDebugMode()) {
            plugin.debug("Invalidated group permissions cache for " + groupName + " in " + areaName);
        }
    }
    
    /**
     * Invalidate the cache for a specific track's permissions in an area
     * @param areaName The name of the area
     * @param trackName The name of the track
     */
    public void invalidateTrackPermissions(String areaName, String trackName) {
        String cacheKey = areaName + ":" + trackName;
        trackPermCache.invalidate(cacheKey);
        
        // Mark this track as updated for future permission checks
        updatedTrackPermissions.add(trackName);
        
        if (plugin.isDebugMode()) {
            plugin.debug("Invalidated track permissions cache for " + trackName + " in " + areaName);
        }
    }
    
    /**
     * Check if a player's permissions have been updated since last check
     * @param areaName The name of the area
     * @param playerName The name of the player
     * @return true if the player's permissions have been updated
     */
    public boolean hasUpdatedPlayerPermissions(String areaName, String playerName) {
        Set<String> updated = updatedPlayerPermissions.get(areaName);
        if (updated != null && updated.contains(playerName)) {
            // Clear the update flag
            updated.remove(playerName);
            return true;
        }
        return false;
    }
    
    /**
     * Check if a group's permissions have been updated since last check
     * @param groupName The name of the group
     * @return true if the group's permissions have been updated
     */
    public boolean hasUpdatedGroupPermissions(String groupName) {
        if (updatedGroupPermissions.contains(groupName)) {
            // Clear the update flag
            updatedGroupPermissions.remove(groupName);
            return true;
        }
        return false;
    }
    
    /**
     * Check if a track's permissions have been updated since last check
     * @param trackName The name of the track
     * @return true if the track's permissions have been updated
     */
    public boolean hasUpdatedTrackPermissions(String trackName) {
        if (updatedTrackPermissions.contains(trackName)) {
            // Clear the update flag
            updatedTrackPermissions.remove(trackName);
            return true;
        }
        return false;
    }
    
    /**
     * Invalidate all caches for the given area name
     * @param areaName The name of the area to invalidate caches for
     */
    public void invalidateCache(String areaName) {
        clearAreaFromCache(areaName);
    }
    
    /**
     * Invalidate all caches
     */
    public void invalidateCache() {
        playerPermCache.invalidateAll();
        groupPermCache.invalidateAll();
        trackPermCache.invalidateAll();
        
        if (plugin.isDebugMode()) {
            plugin.debug("Invalidated all permission caches");
        }
    }
    
    /**
     * Synchronizes permissions between the Area object and the database
     *
     * @param area The area to synchronize
     * @param direction The synchronization direction
     * @throws DatabaseException If there is an error during synchronization
     */
    public void synchronizePermissions(Area area, SyncDirection direction) throws DatabaseException {
        if (area == null) return;
        
        if (plugin.isDebugMode()) {
            plugin.debug("Synchronizing permissions for area " + area.getName() + " (direction: " + direction + ")");
        }
        
        try {
            if (direction == SyncDirection.FROM_DATABASE || direction == SyncDirection.BIDIRECTIONAL) {
                // Load data from database to area
                Map<String, Map<String, Boolean>> playerPerms = getAllPlayerPermissions(area.getName());
                Map<String, Map<String, Boolean>> groupPerms = getAllGroupPermissions(area.getName());
                Map<String, Map<String, Boolean>> trackPerms = getAllTrackPermissions(area.getName());
                
                // Update area's internal maps with data from database
                area.updateInternalPermissions(playerPerms, groupPerms, trackPerms);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("  Updated area from database: " + 
                        playerPerms.size() + " player permissions, " + 
                        groupPerms.size() + " group permissions, " +
                        trackPerms.size() + " track permissions");
                }
            }
            
            if (direction == SyncDirection.TO_DATABASE || direction == SyncDirection.BIDIRECTIONAL) {
                // Save area data to database
                synchronizeFromArea(area);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("  Updated database from area");
                }
            }
        } catch (Exception e) {
            throw new DatabaseException("Error synchronizing permissions for area: " + area.getName(), e);
        }
    }
}