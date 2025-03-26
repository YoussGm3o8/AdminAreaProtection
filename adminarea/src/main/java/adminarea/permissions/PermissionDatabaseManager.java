package adminarea.permissions;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.exception.DatabaseException;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;

/**
 * Manages database operations for permission overrides
 */
public class PermissionDatabaseManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(PermissionDatabaseManager.class);
    private final AdminAreaProtectionPlugin plugin;
    private final HikariDataSource dataSource;
    private static final String DB_FILE = "permission_overrides.db";

    public PermissionDatabaseManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.dataSource = initializeDataSource();
        initializeDatabase();
    }

    private HikariDataSource initializeDataSource() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            logger.error("Failed to load SQLite JDBC driver", e);
            throw new RuntimeException("SQLite JDBC driver not found", e);
        }
        
        HikariConfig config = new HikariConfig();
        String jdbcUrl = "jdbc:sqlite:" + plugin.getDataFolder() + "/" + DB_FILE;
        config.setJdbcUrl(jdbcUrl);
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(5);
        config.setIdleTimeout(60000);
        config.setMaxLifetime(1800000);
        config.setConnectionTimeout(30000);
        config.setConnectionTestQuery("SELECT 1");
        
        // SQLite specific optimizations
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("cache_size", "2000");
        
        return new HikariDataSource(config);
    }

    private void initializeDatabase() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            
            conn.setAutoCommit(false);
            try {
                // Check if tables exist
                boolean tablesExist = false;
                try (ResultSet rs = conn.getMetaData().getTables(null, null, "player_permissions", null)) {
                    tablesExist = rs.next();
                }
                
                if (!tablesExist) {
                    // Create tables if they don't exist - with correct data types from the start
                    stmt.executeUpdate(
                        "CREATE TABLE player_permissions (" +
                        "area_name TEXT NOT NULL, " +
                        "player_name TEXT NOT NULL, " +
                        "permission TEXT NOT NULL, " +
                        "value TEXT NOT NULL, " + // Store as TEXT to avoid type conflicts
                        "PRIMARY KEY (area_name, player_name, permission)" +
                        ")"
                    );
                    
                    stmt.executeUpdate(
                        "CREATE TABLE group_permissions (" +
                        "area_name TEXT NOT NULL, " +
                        "group_name TEXT NOT NULL, " +
                        "permission TEXT NOT NULL, " +
                        "value TEXT NOT NULL, " + // Store as TEXT to avoid type conflicts
                        "PRIMARY KEY (area_name, group_name, permission)" +
                        ")"
                    );
                    
                    stmt.executeUpdate(
                        "CREATE TABLE track_permissions (" +
                        "area_name TEXT NOT NULL, " +
                        "track_name TEXT NOT NULL, " +
                        "permission TEXT NOT NULL, " +
                        "value TEXT NOT NULL, " + // Store as TEXT to avoid type conflicts
                        "PRIMARY KEY (area_name, track_name, permission)" +
                        ")"
                    );
                } else {
                    // Tables exist - check if we need to migrate column types
                    try {
                        // Try to alter existing tables to fix data types if needed
                        stmt.executeUpdate("ALTER TABLE player_permissions RENAME TO player_permissions_old");
                        stmt.executeUpdate(
                            "CREATE TABLE player_permissions (" +
                            "area_name TEXT NOT NULL, " +
                            "player_name TEXT NOT NULL, " +
                            "permission TEXT NOT NULL, " +
                            "value TEXT NOT NULL, " + // Store as TEXT
                            "PRIMARY KEY (area_name, player_name, permission)" +
                            ")"
                        );
                        stmt.executeUpdate("INSERT INTO player_permissions SELECT area_name, player_name, permission, CAST(value AS TEXT) FROM player_permissions_old");
                        stmt.executeUpdate("DROP TABLE player_permissions_old");

                        stmt.executeUpdate("ALTER TABLE group_permissions RENAME TO group_permissions_old");
                        stmt.executeUpdate(
                            "CREATE TABLE group_permissions (" +
                            "area_name TEXT NOT NULL, " +
                            "group_name TEXT NOT NULL, " +
                            "permission TEXT NOT NULL, " +
                            "value TEXT NOT NULL, " + // Store as TEXT
                            "PRIMARY KEY (area_name, group_name, permission)" +
                            ")"
                        );
                        stmt.executeUpdate("INSERT INTO group_permissions SELECT area_name, group_name, permission, CAST(value AS TEXT) FROM group_permissions_old");
                        stmt.executeUpdate("DROP TABLE group_permissions_old");

                        stmt.executeUpdate("ALTER TABLE track_permissions RENAME TO track_permissions_old");
                        stmt.executeUpdate(
                            "CREATE TABLE track_permissions (" +
                            "area_name TEXT NOT NULL, " +
                            "track_name TEXT NOT NULL, " +
                            "permission TEXT NOT NULL, " +
                            "value TEXT NOT NULL, " + // Store as TEXT
                            "PRIMARY KEY (area_name, track_name, permission)" +
                            ")"
                        );
                        stmt.executeUpdate("INSERT INTO track_permissions SELECT area_name, track_name, permission, CAST(value AS TEXT) FROM track_permissions_old");
                        stmt.executeUpdate("DROP TABLE track_permissions_old");
                        
                        logger.info("Successfully migrated permission database tables to new schema");
                    } catch (SQLException e) {
                        // Migration failed, but we'll continue - the getters and setters have been updated to handle this
                        logger.warn("Schema migration failed, but will continue with updated getters/setters: " + e.getMessage());
                    }
                }
                
                // Create indexes
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_player_permissions_area ON player_permissions(area_name)");
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_player_permissions_player ON player_permissions(player_name)");
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_group_permissions_area ON group_permissions(area_name)");
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_group_permissions_group ON group_permissions(group_name)");
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_track_permissions_area ON track_permissions(area_name)");
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_track_permissions_track ON track_permissions(track_name)");
                
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            logger.error("Failed to initialize permission database", e);
            throw new RuntimeException("Failed to initialize permission database", e);
        }
    }
    
    public void backupDatabase() {
        File dbFile = new File(plugin.getDataFolder(), DB_FILE);
        if (!dbFile.exists()) {
            return;
        }
        
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd-HHmmss");
            String timestamp = dateFormat.format(new Date());
            File backupFile = new File(plugin.getDataFolder(), DB_FILE + "-" + timestamp + ".bak");
            
            // Force checkpoint to ensure WAL is written to main file
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA wal_checkpoint(FULL)");
            }
            
            Files.copy(dbFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            logger.info("Permission database backed up to " + backupFile.getName());
        } catch (Exception e) {
            logger.error("Failed to backup permission database", e);
        }
    }
    
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
    
    public void executeInTransaction(SqlRunnable operation) throws DatabaseException {
        try (Connection conn = getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                operation.run(conn);
                conn.commit();
                
                // Log successful transaction if in debug mode
                if (plugin.isDebugMode()) {
                    plugin.debug("Successfully committed permission database transaction");
                }
            } catch (SQLException e) {
                try {
                    conn.rollback();
                    if (plugin.isDebugMode()) {
                        plugin.debug("Rolled back permission database transaction due to error: " + e.getMessage());
                    }
                } catch (SQLException rollbackEx) {
                    logger.error("Failed to rollback transaction", rollbackEx);
                }
                throw e;
            } catch (Exception e) {
                try {
                    conn.rollback();
                    if (plugin.isDebugMode()) {
                        plugin.debug("Rolled back permission database transaction due to error: " + e.getMessage());
                    }
                } catch (SQLException rollbackEx) {
                    logger.error("Failed to rollback transaction", rollbackEx);
                }
                throw new DatabaseException("Database operation failed", e);
            } finally {
                try {
                    conn.setAutoCommit(previousAutoCommit);
                } catch (SQLException e) {
                    logger.error("Failed to restore autoCommit setting", e);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Database operation failed", e);
        }
    }

    // Save player permissions
    public void savePlayerPermissions(String areaName, String playerName, Map<String, Boolean> permissions) 
            throws DatabaseException {
        if (areaName == null || playerName == null || permissions == null) {
            return;
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("Saving " + permissions.size() + " permissions for player " + playerName + " in area " + areaName);
        }
        
        executeInTransaction(conn -> {
            // Delete existing permissions
            try (PreparedStatement deleteStmt = conn.prepareStatement(
                    "DELETE FROM player_permissions WHERE area_name = ? AND player_name = ?")) {
                deleteStmt.setString(1, areaName);
                deleteStmt.setString(2, playerName);
                int deleted = deleteStmt.executeUpdate();
                
                if (plugin.isDebugMode() && deleted > 0) {
                    plugin.debug("Deleted " + deleted + " existing permissions for player " + playerName);
                }
            }
            
            // Skip insertion if permissions map is empty
            if (permissions.isEmpty()) {
                if (plugin.isDebugMode()) {
                    plugin.debug("No permissions to insert for player " + playerName);
                }
                return;
            }
            
            // Insert new permissions
            try (PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT INTO player_permissions(area_name, player_name, permission, value) VALUES(?, ?, ?, ?)")) {
                for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
                    insertStmt.setString(1, areaName);
                    insertStmt.setString(2, playerName);
                    insertStmt.setString(3, entry.getKey());
                    insertStmt.setString(4, entry.getValue().toString());
                    insertStmt.addBatch();
                }
                
                int[] results = insertStmt.executeBatch();
                
                if (plugin.isDebugMode()) {
                    int insertedCount = 0;
                    for (int i : results) {
                        if (i > 0) insertedCount++;
                    }
                    plugin.debug("Successfully inserted " + insertedCount + " permissions for player " + playerName);
                    
                    // Verify the permissions were actually saved
                    try (PreparedStatement verifyStmt = conn.prepareStatement(
                             "SELECT COUNT(*) FROM player_permissions WHERE area_name = ? AND player_name =?")) {
                        verifyStmt.setString(1, areaName);
                        verifyStmt.setString(2, playerName);
                        try (ResultSet rs = verifyStmt.executeQuery()) {
                            if (rs.next()) {
                                int count = rs.getInt(1);
                                if (count != permissions.size()) {
                                    plugin.debug("WARNING: Permission count mismatch! Expected " + 
                                               permissions.size() + " but found " + count + 
                                               " in database for player " + playerName);
                                } else {
                                    plugin.debug("Verified " + count + " permissions saved for player " + playerName);
                                }
                            }
                        }
                    }
                }
            }
        });
        
        try {
            // Checkpoint the database after saving to ensure persistence
            try (Connection conn = getConnection()) {
                try (var stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA wal_checkpoint(FULL)");
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to checkpoint database after saving player permissions: " + e.getMessage());
        }
    }
    
    // Load player permissions
    public Map<String, Boolean> getPlayerPermissions(String areaName, String playerName) throws DatabaseException {
        Map<String, Boolean> permissions = new HashMap<>();
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT permission, value FROM player_permissions WHERE area_name = ? AND player_name = ?")) {
            stmt.setString(1, areaName);
            stmt.setString(2, playerName);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String permission = rs.getString("permission");
                    String value = rs.getString("value");
                    // Explicitly handle text values "true" and "false"
                    boolean boolValue = "true".equalsIgnoreCase(value);
                    // plugin.getLogger().info("[Debug] Permission DB row: " + permission + " = " + value + " (parsed as " + boolValue + ")");
                    permissions.put(permission, boolValue);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load player permissions", e);
        }
        
        return permissions;
    }
    
    // Save group permissions
    public void saveGroupPermissions(String areaName, String groupName, Map<String, Boolean> permissions) 
            throws DatabaseException {
        if (areaName == null || groupName == null || permissions == null) {
            return;
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("Saving " + permissions.size() + " permissions for group " + groupName + " in area " + areaName);
        }
        
        executeInTransaction(conn -> {
            // Delete existing permissions
            try (PreparedStatement deleteStmt = conn.prepareStatement(
                    "DELETE FROM group_permissions WHERE area_name = ? AND group_name = ?")) {
                deleteStmt.setString(1, areaName);
                deleteStmt.setString(2, groupName);
                int deleted = deleteStmt.executeUpdate();
                
                if (plugin.isDebugMode() && deleted > 0) {
                    plugin.debug("Deleted " + deleted + " existing permissions for group " + groupName);
                }
            }
            
            // Skip insertion if permissions map is empty
            if (permissions.isEmpty()) {
                if (plugin.isDebugMode()) {
                    plugin.debug("No permissions to insert for group " + groupName);
                }
                return;
            }
            
            // Insert new permissions
            try (PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT INTO group_permissions(area_name, group_name, permission, value) VALUES(?, ?, ?, ?)")) {
                for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
                    insertStmt.setString(1, areaName);
                    insertStmt.setString(2, groupName);
                    insertStmt.setString(3, entry.getKey());
                    insertStmt.setString(4, entry.getValue().toString());
                    insertStmt.addBatch();
                }
                
                int[] results = insertStmt.executeBatch();
                
                if (plugin.isDebugMode()) {
                    int insertedCount = 0;
                    for (int result : results) {
                        if (result > 0) insertedCount++;
                    }
                    plugin.debug("Successfully inserted " + insertedCount + " permissions for group " + groupName);
                }
            }
        });
        
        // Force WAL checkpoint to ensure changes are written to disk
        // Done outside the transaction to avoid locking issues
        try {
            forceWalCheckpoint();
            if (plugin.isDebugMode()) {
                plugin.debug("Checkpoint completed after saving group permissions");
            }
        } catch (DatabaseException e) {
            // Log the error but don't fail the operation if checkpoint fails
            logger.error("Failed to checkpoint database after saving group permissions: " + e.getMessage());
        }
    }
    
    // Load group permissions
    public Map<String, Boolean> getGroupPermissions(String areaName, String groupName) throws DatabaseException {
        Map<String, Boolean> permissions = new HashMap<>();
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT permission, value FROM group_permissions WHERE area_name = ? AND group_name = ?")) {
            stmt.setString(1, areaName);
            stmt.setString(2, groupName);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String permission = rs.getString("permission");
                    String value = rs.getString("value");
                    // Explicitly handle text values "true" and "false"
                    boolean boolValue = "true".equalsIgnoreCase(value);
                    permissions.put(permission, boolValue);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load group permissions", e);
        }
        
        return permissions;
    }
    
    // Save track permissions
    public void saveTrackPermissions(String areaName, String trackName, Map<String, Boolean> permissions) 
            throws DatabaseException {
        if (areaName == null || trackName == null || permissions == null) {
            return;
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("Saving " + permissions.size() + " permissions for track " + trackName + " in area " + areaName);
        }
        
        executeInTransaction(conn -> {
            // Delete existing permissions
            try (PreparedStatement deleteStmt = conn.prepareStatement(
                    "DELETE FROM track_permissions WHERE area_name = ? AND track_name = ?")) {
                deleteStmt.setString(1, areaName);
                deleteStmt.setString(2, trackName);
                int deleted = deleteStmt.executeUpdate();
                
                if (plugin.isDebugMode() && deleted > 0) {
                    plugin.debug("Deleted " + deleted + " existing permissions for track " + trackName);
                }
            }
            
            // Skip insertion if permissions map is empty
            if (permissions.isEmpty()) {
                if (plugin.isDebugMode()) {
                    plugin.debug("No permissions to insert for track " + trackName);
                }
                return;
            }
            
            // Insert new permissions
            try (PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT INTO track_permissions(area_name, track_name, permission, value) VALUES(?, ?, ?, ?)")) {
                for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
                    insertStmt.setString(1, areaName);
                    insertStmt.setString(2, trackName);
                    insertStmt.setString(3, entry.getKey());
                    insertStmt.setString(4, entry.getValue().toString());
                    insertStmt.addBatch();
                }
                
                int[] results = insertStmt.executeBatch();
                
                if (plugin.isDebugMode()) {
                    int insertedCount = 0;
                    for (int result : results) {
                        if (result > 0) insertedCount++;
                    }
                    plugin.debug("Successfully inserted " + insertedCount + " permissions for track " + trackName);
                }
            }
        });
        
        // Force WAL checkpoint to ensure changes are written to disk
        // Done outside the transaction to avoid locking issues
        try {
            forceWalCheckpoint();
            if (plugin.isDebugMode()) {
                plugin.debug("Checkpoint completed after saving track permissions");
            }
        } catch (DatabaseException e) {
            // Log the error but don't fail the operation if checkpoint fails
            logger.error("Failed to checkpoint database after saving track permissions: " + e.getMessage());
        }
    }
    
    // Load track permissions
    public Map<String, Boolean> getTrackPermissions(String areaName, String trackName) throws DatabaseException {
        Map<String, Boolean> permissions = new HashMap<>();
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT permission, value FROM track_permissions WHERE area_name = ? AND track_name = ?")) {
            stmt.setString(1, areaName);
            stmt.setString(2, trackName);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String permission = rs.getString("permission");
                    String value = rs.getString("value");
                    // Explicitly handle text values "true" and "false"
                    boolean boolValue = "true".equalsIgnoreCase(value);
                    permissions.put(permission, boolValue);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load track permissions", e);
        }
        
        return permissions;
    }
    
    // Get all player permissions for an area
    public Map<String, Map<String, Boolean>> getAllPlayerPermissions(String areaName) throws DatabaseException {
        // plugin.getLogger().info("[Debug] Loading all player permissions for area " + areaName + " from database");
        Map<String, Map<String, Boolean>> allPermissions = new HashMap<>();
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT player_name, permission, value FROM player_permissions WHERE area_name = ?")) {
            stmt.setString(1, areaName);
            
            int totalEntries = 0;
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String playerName = rs.getString("player_name");
                    String permission = rs.getString("permission");
                    String valueStr = rs.getString("value");
                    // Explicitly handle text values "true" and "false"
                    boolean value = "true".equalsIgnoreCase(valueStr);
                    
                    allPermissions.computeIfAbsent(playerName, k -> new HashMap<>()).put(permission, value);
                    totalEntries++;
                }
            }
            
            // Log debug information
            int playerCount = allPermissions.size();
            //plugin.getLogger().info("[Debug] Found " + totalEntries + " total permission entries for " + playerCount + " players in area " + areaName);
            // for (Map.Entry<String, Map<String, Boolean>> entry : allPermissions.entrySet()) {
            //     //plugin.getLogger().info("[Debug]   Player " + entry.getKey() + ": " + entry.getValue().size() + " permissions");
            // }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load all player permissions", e);
        }
        
        return allPermissions;
    }
    
    // Get all group permissions for an area
    public Map<String, Map<String, Boolean>> getAllGroupPermissions(String areaName) throws DatabaseException {
        Map<String, Map<String, Boolean>> allPermissions = new HashMap<>();
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT group_name, permission, value FROM group_permissions WHERE area_name = ?")) {
            stmt.setString(1, areaName);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String groupName = rs.getString("group_name");
                    String permission = rs.getString("permission");
                    String valueStr = rs.getString("value");
                    // Explicitly handle text values "true" and "false"
                    boolean value = "true".equalsIgnoreCase(valueStr);
                    
                    allPermissions.computeIfAbsent(groupName, k -> new HashMap<>()).put(permission, value);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load all group permissions", e);
        }
        
        return allPermissions;
    }
    
    // Get all track permissions for an area
    public Map<String, Map<String, Boolean>> getAllTrackPermissions(String areaName) throws DatabaseException {
        Map<String, Map<String, Boolean>> allPermissions = new HashMap<>();
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT track_name, permission, value FROM track_permissions WHERE area_name = ?")) {
            stmt.setString(1, areaName);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String trackName = rs.getString("track_name");
                    String permission = rs.getString("permission");
                    String valueStr = rs.getString("value");
                    // Explicitly handle text values "true" and "false"
                    boolean value = "true".equalsIgnoreCase(valueStr);
                    
                    allPermissions.computeIfAbsent(trackName, k -> new HashMap<>()).put(permission, value);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load all track permissions", e);
        }
        
        return allPermissions;
    }
    
    /**
     * Delete all permissions for an area, but return the player permissions first
     * for potential preservation.
     * 
     * @param areaName The name of the area
     * @param preservePlayerPermissions Whether to return player permissions
     * @return Map of player permissions that were deleted, or null if preservePlayerPermissions is false
     * @throws DatabaseException If the database operation fails
     */
    public Map<String, Map<String, Boolean>> deleteAreaPermissions(String areaName, boolean preservePlayerPermissions) 
            throws DatabaseException {
        if (areaName == null) {
            return null;
        }
        
        Map<String, Map<String, Boolean>> playerPermissions = null;
        
        // If we need to preserve permissions, get them first
        if (preservePlayerPermissions) {
            try {
                playerPermissions = getAllPlayerPermissions(areaName);
                if (plugin.isDebugMode()) {
                    plugin.debug("Preserving " + (playerPermissions != null ? playerPermissions.size() : 0) + 
                               " player permissions before deleting area " + areaName);
                }
            } catch (Exception e) {
                logger.error("Failed to preserve player permissions before deletion", e);
                // Continue with deletion even if preservation fails
            }
        }
        
        // Delete the permissions
        executeInTransaction(conn -> {
            // Delete player permissions
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM player_permissions WHERE area_name = ?")) {
                stmt.setString(1, areaName);
                int count = stmt.executeUpdate();
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Deleted " + count + " player permissions for area " + areaName);
                }
            }
            
            // Delete group permissions
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM group_permissions WHERE area_name = ?")) {
                stmt.setString(1, areaName);
                int count = stmt.executeUpdate();
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Deleted " + count + " group permissions for area " + areaName);
                }
            }
            
            // Delete track permissions
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM track_permissions WHERE area_name = ?")) {
                stmt.setString(1, areaName);
                int count = stmt.executeUpdate();
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Deleted " + count + " track permissions for area " + areaName);
                }
            }
        });
        
        return playerPermissions;
    }
    
    /**
     * Delete all permissions for an area.
     * 
     * @param areaName The name of the area
     * @throws DatabaseException If the database operation fails
     */
    public void deleteAreaPermissions(String areaName) throws DatabaseException {
        deleteAreaPermissions(areaName, false);
    }
    
    // Rename area permissions
    public void renameAreaPermissions(String oldName, String newName) throws DatabaseException {
        executeInTransaction(conn -> {
            String[] tables = {"player_permissions", "group_permissions", "track_permissions"};
            
            for (String table : tables) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE " + table + " SET area_name = ? WHERE area_name = ?")) {
                    stmt.setString(1, newName);
                    stmt.setString(2, oldName);
                    stmt.executeUpdate();
                }
            }
        });
    }
    
    // Delete player permissions
    public void deletePlayerPermissions(String areaName, String playerName) throws DatabaseException {
        executeInTransaction(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM player_permissions WHERE area_name = ? AND player_name = ?")) {
                stmt.setString(1, areaName);
                stmt.setString(2, playerName);
                stmt.executeUpdate();
            }
        });
    }
    
    // Get areas with group permissions
    public List<String> getAreasWithGroupPermissions(String groupName) throws DatabaseException {
        List<String> areas = new ArrayList<>();
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT DISTINCT area_name FROM group_permissions WHERE group_name = ?")) {
            stmt.setString(1, groupName);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    areas.add(rs.getString("area_name"));
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to get areas with group permissions", e);
        }
        
        return areas;
    }
    
    // Get areas with track permissions
    public List<String> getAreasWithTrackPermissions(String trackName) throws DatabaseException {
        List<String> areas = new ArrayList<>();
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT DISTINCT area_name FROM track_permissions WHERE track_name = ?")) {
            stmt.setString(1, trackName);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    areas.add(rs.getString("area_name"));
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to get areas with track permissions", e);
        }
        
        return areas;
    }
    
    // Run checkpoint to flush changes to disk
    public void checkpoint() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA wal_checkpoint(FULL)");
        } catch (SQLException e) {
            logger.error("Failed to run WAL checkpoint", e);
        }
    }
    
    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            checkpoint();
            dataSource.close();
        }
    }
    
    // Functional interface for transaction operations
    @FunctionalInterface
    public interface SqlRunnable {
        void run(Connection conn) throws SQLException, Exception;
    }

    /**
     * Completely rebuilds the permission database from scratch.
     * This is a drastic measure to be used only when the database is corrupted.
     * WARNING: This will delete and recreate all database tables!
     */
    public void rebuildDatabase() {
        logger.warn("Rebuilding permission database from scratch!");
        
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            
            conn.setAutoCommit(false);
            try {
                // Drop existing tables
                stmt.executeUpdate("DROP TABLE IF EXISTS player_permissions");
                stmt.executeUpdate("DROP TABLE IF EXISTS group_permissions");
                stmt.executeUpdate("DROP TABLE IF EXISTS track_permissions");
                
                // Recreate tables
                initializeDatabase();
                
                logger.info("Permission database successfully rebuilt");
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                logger.error("Failed to rebuild permission database", e);
                throw e;
            }
        } catch (SQLException e) {
            logger.error("Failed to rebuild permission database", e);
            throw new RuntimeException("Failed to rebuild permission database", e);
        }
    }

    /**
     * Forces a WAL checkpoint to ensure all pending changes are written to the main database file.
     * This is important when using SQLite in WAL mode to ensure data durability.
     */
    public void forceWalCheckpoint() throws DatabaseException {
        if (plugin.isDebugMode()) {
            plugin.debug("Starting WAL checkpoint for permission database");
        }
        
        int retries = 3;
        long retryDelayMs = 100;
        SQLException lastException = null;
        
        for (int attempt = 0; attempt < retries; attempt++) {
            try (Connection conn = getConnection()) {
                forceWalCheckpoint(conn);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("WAL checkpoint completed successfully on attempt " + (attempt + 1));
                }
                
                return; // Success
            } catch (SQLException e) {
                lastException = e;
                boolean isLockingError = e.getMessage().contains("SQLITE_LOCKED") || 
                    e.getMessage().contains("database table is locked") ||
                    e.getMessage().contains("database is locked");
                
                if (isLockingError && attempt < retries - 1) {
                    // If database is locked, wait and retry
                    if (plugin.isDebugMode()) {
                        plugin.debug("Database locked during checkpoint, retrying in " + 
                            retryDelayMs * (attempt + 1) + "ms (attempt " + (attempt + 1) + " of " + retries + ")");
                    }
                    
                    try {
                        Thread.sleep(retryDelayMs * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new DatabaseException("Checkpoint interrupted", ie);
                    }
                } else {
                    // Not a locking issue or out of retries
                    if (plugin.isDebugMode()) {
                        plugin.debug("Checkpoint failed with error: " + e.getMessage());
                    }
                    
                    throw new DatabaseException("Failed to checkpoint database", e);
                }
            }
        }
        
        // If we've exhausted all retries and still have an exception
        if (lastException != null) {
            throw new DatabaseException("Failed to checkpoint database after " + retries + " attempts", lastException);
        }
    }
    
    /**
     * Forces a WAL checkpoint on the provided connection.
     */
    private void forceWalCheckpoint(Connection conn) throws SQLException {
        int retries = 3;
        SQLException lastException = null;
        
        for (int attempt = 0; attempt < retries; attempt++) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA wal_checkpoint(FULL)");
                if (plugin.isDebugMode()) {
                    plugin.debug("Forced WAL checkpoint for permission database");
                }
                return; // Success
            } catch (SQLException e) {
                lastException = e;
                if (e.getMessage().contains("SQLITE_LOCKED") || e.getMessage().contains("database table is locked")) {
                    // If database is locked, wait a bit and retry
                    try {
                        Thread.sleep(100 * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    // If it's not a locking issue, don't retry
                    throw e;
                }
            }
        }
        
        // If we've exhausted all retries, throw the last exception
        if (lastException != null) {
            throw lastException;
        }
    }

    /**
     * Debug method to dump player permissions directly from the database
     * This is only used for debugging purposes
     */
    public void debugDumpPlayerPermissions(String areaName, String playerName) throws DatabaseException {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM player_permissions WHERE area_name = ? AND player_name = ?")) {
            stmt.setString(1, areaName);
            stmt.setString(2, playerName);
            
            try (ResultSet rs = stmt.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    count++;
                    String permission = rs.getString("permission");
                    String value = rs.getString("value");
                    plugin.debug("    Permission DB row: " + permission + " = " + value);
                }
                
                if (count == 0) {
                    plugin.debug("    No permissions found in database for player " + playerName + " in area " + areaName);
                } else {
                    plugin.debug("    Total rows in database: " + count);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to dump player permissions for debugging", e);
        }
    }

    /**
     * Check if player permissions exist in the database for a given area 
     * @param areaName The area to check
     * @return true if permissions exist, false otherwise
     */
    public boolean playerPermissionsExist(String areaName) throws DatabaseException {
        if (areaName == null) {
            return false;
        }
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM player_permissions WHERE area_name = ?")) {
            stmt.setString(1, areaName);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int count = rs.getInt(1);
                    return count > 0;
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to check if player permissions exist", e);
        }
        
        return false;
    }
} 