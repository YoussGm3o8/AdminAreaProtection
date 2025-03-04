package adminarea.managers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaDTO;
import adminarea.area.AreaBuilder;
import adminarea.exception.DatabaseException;
import adminarea.permissions.PermissionToggle;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private final AdminAreaProtectionPlugin plugin;
    private final HikariDataSource dataSource;
    private final Cache<String, Area> areaCache;
    private static final int CACHE_SIZE = 100;
    private static final long CACHE_DURATION = TimeUnit.MINUTES.toMillis(5);

    public DatabaseManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.dataSource = initializeDataSource();
        this.areaCache = Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_DURATION, TimeUnit.MILLISECONDS)
            .build();
    }

    private HikariDataSource initializeDataSource() {
        // Explicitly load the SQLite JDBC driver
        try {
            Class.forName("org.sqlite.JDBC");
            if (plugin.isDebugMode()) {
                plugin.debug("Successfully loaded SQLite JDBC driver");
            }
        } catch (ClassNotFoundException e) {
            logger.error("Failed to load SQLite JDBC driver", e);
            throw new RuntimeException("SQLite JDBC driver not found", e);
        }
        
        HikariConfig config = new HikariConfig();
        String jdbcUrl = "jdbc:sqlite:" + plugin.getDataFolder() + "/areas.db";
        config.setJdbcUrl(jdbcUrl);
        config.setMaximumPoolSize(30);       // Increased from 20 to handle more connections
        config.setMinimumIdle(5);            // Decreased to avoid too many idle connections
        config.setIdleTimeout(600000);       // 10 minutes
        config.setMaxLifetime(1800000);      // 30 minutes
        config.setConnectionTimeout(30000);  // 30 seconds - increased for heavy operations
        config.setConnectionTestQuery("SELECT 1");
        
        // Configure connection retry
        config.setInitializationFailTimeout(10000);  // Wait 10 seconds for pool to initialize
        
        // SQLite specific optimizations
        config.addDataSourceProperty("journal_mode", "WAL");    // Use Write-Ahead Logging
        config.addDataSourceProperty("synchronous", "NORMAL");  // Balance between safety and performance
        config.addDataSourceProperty("cache_size", "2000");     // Increase cache size
        
        // Only enable HikariCP debug logging if plugin debug mode is on
        if (!plugin.isDebugMode()) {
            config.setLeakDetectionThreshold(60000); // 1 minute for leak detection
        }
        
        // Test the connection before returning the data source
        try {
            DriverManager.getConnection(jdbcUrl).close();
            if (plugin.isDebugMode()) {
                plugin.debug("Successfully tested database connection");
            }
        } catch (SQLException e) {
            logger.error("Failed to test database connection", e);
            // Continue anyway, as HikariCP might still be able to establish a connection
        }

        return new HikariDataSource(config);
    }

    public void init() throws DatabaseException {
        try {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                
                conn.setAutoCommit(false);
                try {
                    // Create areas table
                    stmt.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS areas (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            name TEXT UNIQUE,
                            world TEXT,
                            x_min INTEGER,
                            x_max INTEGER,
                            y_min INTEGER,
                            y_max INTEGER,
                            z_min INTEGER,
                            z_max INTEGER,
                            priority INTEGER,
                            show_title BOOLEAN,
                            group_permissions TEXT,
                            inherited_permissions TEXT,
                            enter_message TEXT,
                            leave_message TEXT,
                            toggle_states TEXT DEFAULT '{}',
                            default_toggle_states TEXT DEFAULT '{}',
                            inherited_toggle_states TEXT DEFAULT '{}',
                            track_permissions TEXT DEFAULT '{}',
                            player_permissions TEXT DEFAULT '{}',
                            potion_effects TEXT DEFAULT '{}'
                        )
                    """);

                    // Check if potion_effects column exists, and add it if it doesn't
                    try (ResultSet rs = conn.getMetaData().getColumns(null, null, "areas", "potion_effects")) {
                        if (!rs.next()) {
                            // Column doesn't exist, add it
                            if (plugin.isDebugMode()) {
                                plugin.debug("Adding potion_effects column to areas table");
                            }
                            stmt.executeUpdate("ALTER TABLE areas ADD COLUMN potion_effects TEXT DEFAULT '{}'");
                        }
                    }

                    // Check if settings column exists and migrate data
                    try (ResultSet rs = conn.getMetaData().getColumns(null, null, "areas", "settings")) {
                        if (rs.next()) {
                            // Column exists, migrate data
                            if (plugin.isDebugMode()) {
                                plugin.debug("Migrating settings data to toggle_states");
                            }

                            // Get all areas with settings data
                            try (ResultSet areas = stmt.executeQuery("SELECT name, settings, toggle_states FROM areas WHERE settings IS NOT NULL")) {
                                while (areas.next()) {
                                    String name = areas.getString("name");
                                    String settings = areas.getString("settings");
                                    String toggleStates = areas.getString("toggle_states");

                                    // Merge settings into toggle_states
                                    JSONObject merged = new JSONObject(toggleStates);
                                    JSONObject settingsObj = new JSONObject(settings);
                                    for (String key : settingsObj.keySet()) {
                                        if (!merged.has(key)) {
                                            merged.put(key, settingsObj.getBoolean(key));
                                        }
                                    }

                                    // Update the area with merged data
                                    try (PreparedStatement ps = conn.prepareStatement("UPDATE areas SET toggle_states = ? WHERE name = ?")) {
                                        ps.setString(1, merged.toString());
                                        ps.setString(2, name);
                                        ps.executeUpdate();
                                    }
                                }
                            }

                            // Drop settings column
                            stmt.executeUpdate("ALTER TABLE areas DROP COLUMN settings");
                            
                            if (plugin.isDebugMode()) {
                                plugin.debug("Migration complete - settings column removed");
                            }
                        }
                    }
                    
                    // Migrate permission data to permission_overrides database and remove unused columns
                    migratePermissionsToOverrideDatabase(conn, stmt);

                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            }

        } catch (SQLException e) {
            throw new DatabaseException("Failed to initialize database connection", e);
        }
    }

    /**
     * Migrates permission data from the areas table to the permission_overrides database
     * and removes unused permission columns from the areas table.
     * 
     * @param conn The database connection
     * @param stmt The statement to use for executing SQL
     * @throws SQLException If there's an error during migration
     */
    private void migratePermissionsToOverrideDatabase(Connection conn, Statement stmt) throws SQLException {
        // Check if we need to migrate permissions
        List<String> columnsToCheck = Arrays.asList(
            "group_permissions", "player_permissions", "track_permissions"
        );
        
        boolean needsMigration = false;
        for (String column : columnsToCheck) {
            try (ResultSet rs = conn.getMetaData().getColumns(null, null, "areas", column)) {
                if (rs.next()) {
                    needsMigration = true;
                    break;
                }
            }
        }
        
        if (!needsMigration) {
            if (plugin.isDebugMode()) {
                plugin.debug("No permission columns to migrate");
            }
            return;
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("Starting permission migration to permission_overrides database");
        }
        
        // Get all areas with permission data
        try (ResultSet areas = stmt.executeQuery(
                "SELECT name, group_permissions, player_permissions, track_permissions FROM areas")) {
            
            while (areas.next()) {
                String areaName = areas.getString("name");
                
                // Migrate group permissions
                String groupPermsJson = areas.getString("group_permissions");
                if (groupPermsJson != null && !groupPermsJson.isEmpty() && !groupPermsJson.equals("{}")) {
                    Map<String, Map<String, Boolean>> groupPerms = parseJsonToMap(groupPermsJson);
                    for (Map.Entry<String, Map<String, Boolean>> entry : groupPerms.entrySet()) {
                        try {
                            plugin.getPermissionOverrideManager().setGroupPermissions(areaName, entry.getKey(), entry.getValue());
                            if (plugin.isDebugMode()) {
                                plugin.debug("  Migrated group permissions for " + entry.getKey() + " in area " + areaName);
                            }
                        } catch (DatabaseException e) {
                            plugin.getLogger().error("Failed to migrate group permissions for " + entry.getKey() + " in area " + areaName, e);
                        }
                    }
                }
                
                // Migrate player permissions
                String playerPermsJson = areas.getString("player_permissions");
                if (playerPermsJson != null && !playerPermsJson.isEmpty() && !playerPermsJson.equals("{}")) {
                    Map<String, Map<String, Boolean>> playerPerms = parseJsonToMap(playerPermsJson);
                    for (Map.Entry<String, Map<String, Boolean>> entry : playerPerms.entrySet()) {
                        try {
                            plugin.getPermissionOverrideManager().setPlayerPermissions(areaName, entry.getKey(), entry.getValue());
                            if (plugin.isDebugMode()) {
                                plugin.debug("  Migrated player permissions for " + entry.getKey() + " in area " + areaName);
                            }
                        } catch (DatabaseException e) {
                            plugin.getLogger().error("Failed to migrate player permissions for " + entry.getKey() + " in area " + areaName, e);
                        }
                    }
                }
                
                // Migrate track permissions
                String trackPermsJson = areas.getString("track_permissions");
                if (trackPermsJson != null && !trackPermsJson.isEmpty() && !trackPermsJson.equals("{}")) {
                    Map<String, Map<String, Boolean>> trackPerms = parseJsonToMap(trackPermsJson);
                    for (Map.Entry<String, Map<String, Boolean>> entry : trackPerms.entrySet()) {
                        try {
                            plugin.getPermissionOverrideManager().setTrackPermissions(areaName, entry.getKey(), entry.getValue());
                            if (plugin.isDebugMode()) {
                                plugin.debug("  Migrated track permissions for " + entry.getKey() + " in area " + areaName);
                            }
                        } catch (DatabaseException e) {
                            plugin.getLogger().error("Failed to migrate track permissions for " + entry.getKey() + " in area " + areaName, e);
                        }
                    }
                }
            }
        }
        
        // Create a new table without the permission columns
        if (plugin.isDebugMode()) {
            plugin.debug("Creating new areas table without permission columns");
        }
        
        // Create a temporary table without the permission columns
        stmt.executeUpdate("""
            CREATE TABLE areas_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT UNIQUE,
                world TEXT,
                x_min INTEGER,
                x_max INTEGER,
                y_min INTEGER,
                y_max INTEGER,
                z_min INTEGER,
                z_max INTEGER,
                priority INTEGER,
                show_title BOOLEAN,
                enter_message TEXT,
                leave_message TEXT,
                toggle_states TEXT DEFAULT '{}',
                default_toggle_states TEXT DEFAULT '{}',
                inherited_toggle_states TEXT DEFAULT '{}',
                potion_effects TEXT DEFAULT '{}'
            )
        """);
        
        // Copy data to the new table
        stmt.executeUpdate("""
            INSERT INTO areas_new (
                name, world, x_min, x_max, y_min, y_max, z_min, z_max,
                priority, show_title, enter_message, leave_message,
                toggle_states, default_toggle_states, inherited_toggle_states, potion_effects
            )
            SELECT
                name, world, x_min, x_max, y_min, y_max, z_min, z_max,
                priority, show_title, enter_message, leave_message,
                toggle_states, default_toggle_states, inherited_toggle_states, potion_effects
            FROM areas
        """);
        
        // Drop the old table and rename the new one
        stmt.executeUpdate("DROP TABLE areas");
        stmt.executeUpdate("ALTER TABLE areas_new RENAME TO areas");
        
        // Create index on the name column
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_areas_name ON areas(name)");
        
        if (plugin.isDebugMode()) {
            plugin.debug("Permission migration complete - unused columns removed from areas table");
        }
    }

    public void saveArea(Area area) throws DatabaseException {
        if (plugin.isDebugMode()) {
            plugin.debug("Saving area to database: " + area.getName());
            plugin.debug("  Toggle states to save: " + area.toDTO().toggleStates());
            plugin.debug("  Potion effects to save: " + area.toDTO().potionEffects());
        }
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                "INSERT OR REPLACE INTO areas (name, world, x_min, x_max, y_min, y_max, z_min, z_max, " +
                "priority, show_title, enter_message, leave_message, " +
                "toggle_states, default_toggle_states, inherited_toggle_states, potion_effects) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
             )) {

            AreaDTO dto = area.toDTO();
            stmt.setString(1, dto.name());
            stmt.setString(2, dto.world());
            stmt.setInt(3, dto.bounds().xMin());
            stmt.setInt(4, dto.bounds().xMax());
            stmt.setInt(5, dto.bounds().yMin());
            stmt.setInt(6, dto.bounds().yMax());
            stmt.setInt(7, dto.bounds().zMin());
            stmt.setInt(8, dto.bounds().zMax());
            stmt.setInt(9, dto.priority());
            stmt.setBoolean(10, dto.showTitle());
            
            // Store enter/leave messages
            stmt.setString(11, dto.enterMessage());
            stmt.setString(12, dto.leaveMessage());
            
            // Save toggle states JSON - ensure it's not an empty object if there are actual toggle states
            String toggleStatesJson = dto.toggleStates().toString();
            if (toggleStatesJson.equals("{}") && !area.getAllToggleKeys().isEmpty()) {
                // If the JSON is empty but we have toggle keys, something is wrong
                // Try to rebuild the toggle states
                JSONObject rebuiltToggles = new JSONObject();
                for (String key : area.getAllToggleKeys()) {
                    rebuiltToggles.put(key, area.getToggleState(key));
                }
                
                if (rebuiltToggles.length() == 0) {
                    // Still no toggles - something is seriously wrong, use defaults
                    plugin.debug("  No toggle states found, applying defaults");
                    for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
                        String permNode = toggle.getPermissionNode();
                        if (!permNode.startsWith("gui.permissions.toggles.")) {
                            permNode = "gui.permissions.toggles." + permNode;
                        }
                        rebuiltToggles.put(permNode, toggle.getDefaultValue());
                    }
                }
                
                toggleStatesJson = rebuiltToggles.toString();
                if (plugin.isDebugMode()) {
                    plugin.debug("  Rebuilt toggle states: " + toggleStatesJson);
                }
            }
            stmt.setString(13, toggleStatesJson);
            
            stmt.setString(14, dto.defaultToggleStates().toString());
            stmt.setString(15, dto.inheritedToggleStates().toString());
            
            // Save potion effects JSON - ensure it's not empty if there are actual potion effects
            String potionEffectsJson = dto.potionEffects().toString();
            if (potionEffectsJson.equals("{}") && area.hasPotionEffects()) {
                // Try to rebuild the potion effects if we have them but they're not in the JSON
                // This would need to be customized based on how your potion effects are stored
                try {
                    java.lang.reflect.Method method = area.getClass().getMethod("getPotionEffectsAsJSON");
                    Object result = method.invoke(area);
                    if (result instanceof JSONObject) {
                        potionEffectsJson = result.toString();
                    }
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Rebuilt potion effects: " + potionEffectsJson);
                    }
                } catch (Exception e) {
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Could not rebuild potion effects: " + e.getMessage());
                    }
                }
            }
            stmt.setString(16, potionEffectsJson);

            // Execute the update
            int rowsAffected = stmt.executeUpdate();
            
            if (plugin.isDebugMode()) {
                plugin.debug("  Database update complete - rows affected: " + rowsAffected);
            }
            
            // Ensure permissions are saved to the permission_overrides database
            try {
                // Synchronize permissions to the permission_overrides database
                plugin.getPermissionOverrideManager().synchronizeOnSave(area);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("  Synchronized permissions to permission_overrides database");
                }
            } catch (DatabaseException e) {
                plugin.getLogger().error("Failed to synchronize permissions to permission_overrides database", e);
            }
            
            // Update area cache
            areaCache.put(area.getName(), area);
            
            // Ensure area title is in config
            ensureAreaTitlesConfig(dto);
            
        } catch (SQLException e) {
            throw new DatabaseException("Failed to save area to database", e);
        }
    }

    /**
     * Ensures that the area has a title configuration in config.yml
     * This helps recover config entries if the file is reset
     */
    private void ensureAreaTitlesConfig(AreaDTO dto) {
        if (!dto.showTitle()) {
            return;
        }
        
        // Check if area title config exists
        String basePath = "areaTitles." + dto.name();
        if (!plugin.getConfigManager().exists(basePath)) {
            // No config entry exists, create from saved data
            plugin.getConfigManager().set(basePath + ".enter.main", "§6Welcome to " + dto.name());
            plugin.getConfigManager().set(basePath + ".enter.subtitle", dto.enterMessage());
            plugin.getConfigManager().set(basePath + ".enter.fadeIn", 20);
            plugin.getConfigManager().set(basePath + ".enter.stay", 40);
            plugin.getConfigManager().set(basePath + ".enter.fadeOut", 20);
            
            plugin.getConfigManager().set(basePath + ".leave.main", "§6Leaving " + dto.name());
            plugin.getConfigManager().set(basePath + ".leave.subtitle", dto.leaveMessage());
            plugin.getConfigManager().set(basePath + ".leave.fadeIn", 20);
            plugin.getConfigManager().set(basePath + ".leave.stay", 40);
            plugin.getConfigManager().set(basePath + ".leave.fadeOut", 20);
            
            plugin.getConfigManager().save();
            plugin.debug("Recreated missing title config for area " + dto.name());
        }
    }

    public void updateArea(Area area) throws DatabaseException {
        if (plugin.isDebugMode()) {
            plugin.debug("Updating area in database: " + area.getName());
        }
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                "UPDATE areas SET world = ?, x_min = ?, x_max = ?, y_min = ?, y_max = ?, z_min = ?, z_max = ?, " +
                "priority = ?, show_title = ?, enter_message = ?, leave_message = ?, " +
                "toggle_states = ?, default_toggle_states = ?, inherited_toggle_states = ?, potion_effects = ? " +
                "WHERE name = ?"
             )) {

            AreaDTO dto = area.toDTO();
            stmt.setString(1, dto.world());
            stmt.setInt(2, dto.bounds().xMin());
            stmt.setInt(3, dto.bounds().xMax());
            stmt.setInt(4, dto.bounds().yMin());
            stmt.setInt(5, dto.bounds().yMax());
            stmt.setInt(6, dto.bounds().zMin());
            stmt.setInt(7, dto.bounds().zMax());
            stmt.setInt(8, dto.priority());
            stmt.setBoolean(9, dto.showTitle());
            stmt.setString(10, dto.enterMessage());
            stmt.setString(11, dto.leaveMessage());
            stmt.setString(12, dto.toggleStates().toString());
            stmt.setString(13, dto.defaultToggleStates().toString());
            stmt.setString(14, dto.inheritedToggleStates().toString());
            stmt.setString(15, dto.potionEffects().toString());
            stmt.setString(16, dto.name());

            int rowsAffected = stmt.executeUpdate();
            
            if (plugin.isDebugMode()) {
                plugin.debug("  Database update complete - rows affected: " + rowsAffected);
            }
            
            // Ensure permissions are saved to the permission_overrides database
            try {
                // Synchronize permissions to the permission_overrides database
                plugin.getPermissionOverrideManager().synchronizeOnSave(area);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("  Synchronized permissions to permission_overrides database");
                }
            } catch (DatabaseException e) {
                plugin.getLogger().error("Failed to synchronize permissions to permission_overrides database", e);
            }
            
            // Clear area cache
            invalidateAreaCache(area.getName());
            
            // Clear permission checker cache for this area
            if (plugin.getPermissionOverrideManager() != null && 
                plugin.getPermissionOverrideManager().getPermissionChecker() != null) {
                plugin.getPermissionOverrideManager().getPermissionChecker().invalidateCache(area.getName());
            }
            
        } catch (SQLException e) {
            throw new DatabaseException("Failed to update area in database", e);
        }
    }

    /**
     * Invalidates all caches related to an area
     * This ensures changes are immediately visible to all systems
     * 
     * @param areaName The name of the area whose caches should be invalidated
     */
    private void invalidateAllAreaCaches(String areaName) {
        // Clear DatabaseManager's own cache
        areaCache.invalidate(areaName);
        
        // Clear PermissionChecker cache for this area
        if (plugin.getPermissionOverrideManager() != null && 
            plugin.getPermissionOverrideManager().getPermissionChecker() != null) {
            plugin.getPermissionOverrideManager().getPermissionChecker().invalidateCache(areaName);
        }
        
        // Clear location cache in AreaManager if available
        if (plugin.getAreaManager() != null) {
            plugin.getAreaManager().invalidateAreaCache(areaName);
        }
        
        // Clear listener caches if available
        if (plugin.getListenerManager() != null) {
            plugin.getListenerManager().reload();
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("Invalidated all caches for area: " + areaName);
        }
    }

    /**
     * Invalidates the cache for a specific area
     * This is a public method that delegates to invalidateAllAreaCaches
     * 
     * @param areaName The name of the area whose cache should be invalidated
     */
    public void invalidateAreaCache(String areaName) {
        invalidateAllAreaCaches(areaName);
    }

    public void deleteArea(String name) throws DatabaseException {
        if (plugin.isDebugMode()) {
            plugin.debug("Deleting area from database: " + name);
        }
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM areas WHERE name = ?")) {
            
            stmt.setString(1, name);
            int rowsAffected = stmt.executeUpdate();
            
            if (plugin.isDebugMode()) {
                plugin.debug("  Database delete complete - rows affected: " + rowsAffected);
            }
            
            // Delete permissions from permission_overrides database
            try {
                plugin.getPermissionOverrideManager().deleteAreaPermissions(name);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("  Deleted permissions from permission_overrides database");
                }
            } catch (DatabaseException e) {
                plugin.getLogger().error("Failed to delete permissions from permission_overrides database", e);
            }
            
            // Clear area cache
            invalidateAreaCache(name);
            
            // Clear permission checker cache for this area
            if (plugin.getPermissionOverrideManager() != null && 
                plugin.getPermissionOverrideManager().getPermissionChecker() != null) {
                plugin.getPermissionOverrideManager().getPermissionChecker().invalidateCache(name);
            }
            
        } catch (SQLException e) {
            throw new DatabaseException("Failed to delete area from database", e);
        }
    }

    public List<Area> loadAreas() throws DatabaseException {
        List<Area> areas = new ArrayList<>();
        
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM areas")) {
            
            while (rs.next()) {
                Area area = buildAreaFromResultSet(rs);
                areas.add(area);
                areaCache.put(area.getName(), area);
            }
            
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load areas", e);
        }
        
        return areas;
    }

    private Area buildAreaFromResultSet(ResultSet rs) throws SQLException {
        String areaName = rs.getString("name");
        if (plugin.isDebugMode()) {
            plugin.debug("Loading area " + areaName + " from database");
        }

        // Parse JSON fields
        JSONObject toggleStates = new JSONObject(rs.getString("toggle_states"));
        JSONObject defaultToggleStates = new JSONObject(rs.getString("default_toggle_states"));
        JSONObject inheritedToggleStates = new JSONObject(rs.getString("inherited_toggle_states"));
        
        // Get potion effects JSON
        String potionEffectsJson = rs.getString("potion_effects");
        JSONObject potionEffects = potionEffectsJson != null && !potionEffectsJson.isEmpty() ? 
            new JSONObject(potionEffectsJson) : new JSONObject();
        
        if (plugin.isDebugMode()) {
            plugin.debug("  Loaded potion effects from DB: " + potionEffects.toString());
        }
        
        // Get bounds
        AreaDTO.Bounds bounds = new AreaDTO.Bounds(
            rs.getInt("x_min"),
            rs.getInt("x_max"),
            rs.getInt("y_min"),
            rs.getInt("y_max"),
            rs.getInt("z_min"),
            rs.getInt("z_max")
        );

        // Convert toggle states to permissions map
        Map<String, Boolean> permissionsMap = new HashMap<>();
        for (String key : toggleStates.keySet()) {
            permissionsMap.put(key, toggleStates.getBoolean(key));
        }

        // Create DTO with empty permission maps (will be loaded from permission_overrides database)
        AreaDTO dto = new AreaDTO(
            areaName,
            rs.getString("world"),
            bounds,
            rs.getInt("priority"),
            rs.getBoolean("show_title"),
            toggleStates,
            new HashMap<>(), // Empty group permissions
            new HashMap<>(), // Empty inherited permissions
            toggleStates,
            defaultToggleStates,
            inheritedToggleStates,
            AreaDTO.Permissions.fromMap(permissionsMap),
            rs.getString("enter_message"),
            rs.getString("leave_message"),
            new HashMap<>(), // Empty track permissions
            new HashMap<>(), // Empty player permissions
            potionEffects
        );

        // Build the area
        Area area = AreaBuilder.fromDTO(dto).build();
        
        // Check if PermissionOverrideManager is initialized
        if (plugin.getPermissionOverrideManager() != null) {
            plugin.getPermissionOverrideManager().synchronizeOnLoad(area);
            
            if (plugin.isDebugMode()) {
                plugin.debug("  Loaded permissions from permission_overrides database");
                plugin.debug("  Player permissions: " + area.getPlayerPermissions());
                plugin.debug("  Group permissions: " + area.getGroupPermissions());
                plugin.debug("  Track permissions: " + area.getTrackPermissions());
            }
        } else {
            // PermissionOverrideManager not initialized yet, log a debug message
            if (plugin.isDebugMode()) {
                plugin.debug("  PermissionOverrideManager not initialized yet, permissions will be loaded later");
            }
        }

        if (plugin.isDebugMode()) {
            plugin.debug("Successfully loaded area " + areaName);
        }

        return area;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Boolean>> parseJsonToMap(String json) {
        if (json == null || json.isEmpty() || json.equals("{}")) {
            if (plugin.isDebugMode()) {
                plugin.debug("  parseJsonToMap: Input JSON is null, empty, or {}");
            }
            return new HashMap<>();
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("  parseJsonToMap: Parsing JSON: " + json);
        }
        
        Map<String, Map<String, Boolean>> result = new HashMap<>();
        try {
            JSONObject jsonObj = new JSONObject(json);
            
            for (String key : jsonObj.keySet()) {
                if (plugin.isDebugMode()) {
                    plugin.debug("  parseJsonToMap: Processing key: " + key);
                    plugin.debug("  parseJsonToMap: Value: " + jsonObj.get(key));
                }

                Object value = jsonObj.get(key);
                Map<String, Boolean> innerMap = new HashMap<>();

                if (value instanceof JSONObject) {
                    JSONObject innerJson = (JSONObject) value;
                    for (String permKey : innerJson.keySet()) {
                        if (plugin.isDebugMode()) {
                            plugin.debug("    Processing permission: " + permKey + " = " + innerJson.get(permKey));
                        }
                        if (innerJson.get(permKey) instanceof Boolean) {
                            innerMap.put(permKey, innerJson.getBoolean(permKey));
                        }
                    }
                }

                if (!innerMap.isEmpty()) {
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Adding permissions for " + key + ": " + innerMap);
                    }
                    result.put(key, innerMap);
                }
            }
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("  parseJsonToMap: Error parsing JSON: " + e.getMessage());
                e.printStackTrace();
            }
            return new HashMap<>();
        }

        if (plugin.isDebugMode()) {
            plugin.debug("  parseJsonToMap: Final result: " + result);
        }
        
        return result;
    }

    /**
     * Get a database connection with retry logic
     * @return A valid database connection
     * @throws SQLException if connection cannot be established after retries
     */
    public Connection getConnection() throws SQLException {
        SQLException lastException = null;
        int maxRetries = 3;
        int retryDelayMs = 500;
        
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                return dataSource.getConnection();
            } catch (SQLException e) {
                lastException = e;
                logger.warn("Database connection attempt " + (attempt + 1) + " failed: " + e.getMessage());
                
                // Only retry transient connection exceptions
                if (e instanceof SQLTransientConnectionException) {
                    try {
                        Thread.sleep(retryDelayMs * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e; // Don't retry if interrupted
                    }
                } else {
                    throw e; // Don't retry for non-transient exceptions
                }
            }
        }
        
        // If we got here, all retries failed
        if (lastException != null) {
            throw lastException;
        } else {
            throw new SQLException("Failed to get database connection after " + maxRetries + " attempts");
        }
    }

    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * Loads a single area by name from the database.
     * 
     * @param areaName The name of the area to load
     * @return The loaded area, or null if not found
     * @throws DatabaseException If there's an error accessing the database
     */
    public Area loadArea(String areaName) throws DatabaseException {
        if (areaName == null || areaName.isEmpty()) {
            throw new IllegalArgumentException("Area name cannot be null or empty");
        }
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT * FROM areas WHERE name = ?")) {
            
            stmt.setString(1, areaName);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Area area = buildAreaFromResultSet(rs);
                    areaCache.put(area.getName(), area);
                    return area;
                }
            }
            
            return null; // Area not found
            
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load area: " + areaName, e);
        }
    }

    /**
     * Updates a single toggle state in the database without recreating the entire area
     */
    public void updateAreaToggleState(String areaName, String permission, Object value) throws DatabaseException {
        try (Connection conn = getConnection()) {
            // First load existing area data
            Area area = loadArea(areaName);
            if (area == null) {
                throw new DatabaseException("Area not found: " + areaName);
            }

            // Get current toggle states
            JSONObject toggleStates = area.toDTO().toggleStates();
            
            // Update the specific toggle
            toggleStates.put(permission, value);

            // Update database with new toggle states - only update toggle_states column
            try (PreparedStatement stmt = conn.prepareStatement(
                "UPDATE areas SET toggle_states = ? WHERE name = ?")) {
                stmt.setString(1, toggleStates.toString());
                stmt.setString(2, areaName);
                stmt.executeUpdate();
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Updated toggle state in database for area " + areaName);
                    plugin.debug("  Permission: " + permission + ", Value: " + value);
                    plugin.debug("  New toggle states: " + toggleStates.toString());
                }
            }
            
            // Important: Invalidate the cache and reload the area to apply changes
            areaCache.invalidate(areaName);
            
            // Reload the area from database
            Area reloadedArea = loadArea(areaName);
            if (reloadedArea != null) {
                // Apply the new toggle state to the area object
                reloadedArea.setToggleState(permission, value instanceof Boolean ? (Boolean)value : Boolean.valueOf(value.toString()));
                
                if (plugin.isDebugMode()) {
                    plugin.debug("  Reloaded area with updated toggles");
                    plugin.debug("  Verified toggle value for " + permission + ": " + reloadedArea.getToggleState(permission));
                }
            }

        } catch (SQLException e) {
            throw new DatabaseException("Failed to update toggle state", e);
        }
    }
    
    /**
     * Updates all toggle states for an area in a single database operation
     * @param areaName The name of the area to update
     * @param toggleStates JSONObject containing all toggle states
     * @throws DatabaseException If there's an error accessing the database
     */
    public void updateAllAreaToggleStates(String areaName, JSONObject toggleStates) throws DatabaseException {
        try (Connection conn = getConnection()) {
            // First check if area exists
            boolean areaExists = false;
            try (PreparedStatement checkStmt = conn.prepareStatement("SELECT 1 FROM areas WHERE name = ?")) {
                checkStmt.setString(1, areaName);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    areaExists = rs.next();
                }
            }
            
            if (!areaExists) {
                throw new DatabaseException("Area not found: " + areaName);
            }

            // Update database with new toggle states - only update toggle_states column
            try (PreparedStatement stmt = conn.prepareStatement(
                "UPDATE areas SET toggle_states = ? WHERE name = ?")) {
                stmt.setString(1, toggleStates.toString());
                stmt.setString(2, areaName);
                stmt.executeUpdate();
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Updated all toggle states in database for area " + areaName);
                    plugin.debug("  New toggle states: " + toggleStates.toString());
                }
            }
            
            // Important: Invalidate the cache
            areaCache.invalidate(areaName);

        } catch (SQLException e) {
            throw new DatabaseException("Failed to update toggle states", e);
        }
    }
}
