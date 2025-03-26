package adminarea.managers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaDTO;
import adminarea.area.AreaBuilder;
import adminarea.exception.DatabaseException;
import adminarea.permissions.PermissionOverrideManager;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.HashSet;
import java.util.Set;

/**
 * Database manager for AdminAreaProtection plugin - optimized version
 */
public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private final AdminAreaProtectionPlugin plugin;
    private final HikariDataSource dataSource;
    private final Cache<String, Area> areaCache;
    private static final int CACHE_SIZE = 100;
    private static final long CACHE_DURATION = TimeUnit.MINUTES.toMillis(5);
    
    // Thread-local set to track areas being updated to prevent recursion
    private static final ThreadLocal<Set<String>> updatingAreas = ThreadLocal.withInitial(() -> new HashSet<>());

    public DatabaseManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.dataSource = initializeDataSource();
        this.areaCache = Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_DURATION, TimeUnit.MILLISECONDS)
            .build();
    }

    private HikariDataSource initializeDataSource() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            logger.error("Failed to load SQLite JDBC driver", e);
            throw new RuntimeException("SQLite JDBC driver not found", e);
        }
        
        HikariConfig config = new HikariConfig();
        String jdbcUrl = "jdbc:sqlite:" + plugin.getDataFolder() + "/areas.db";
        config.setJdbcUrl(jdbcUrl);
        config.setMaximumPoolSize(30);
        config.setMinimumIdle(5);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        config.setConnectionTimeout(30000);
        config.setConnectionTestQuery("SELECT 1");
        config.setInitializationFailTimeout(10000);
        
        // SQLite specific optimizations
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("cache_size", "2000");
        
        if (!plugin.isDebugMode()) {
            config.setLeakDetectionThreshold(60000);
        }
        
        try {
            DriverManager.getConnection(jdbcUrl).close();
        } catch (SQLException e) {
            logger.error("Failed to test database connection", e);
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
                            enter_message TEXT,
                            leave_message TEXT,
                            enter_title TEXT,
                            leave_title TEXT,
                            toggle_states TEXT DEFAULT '{}',
                            default_toggle_states TEXT DEFAULT '{}',
                            inherited_toggle_states TEXT DEFAULT '{}',
                            potion_effects TEXT DEFAULT '{}'
                        )
                    """);

                    // Add columns if they don't exist
                    try (ResultSet rs = conn.getMetaData().getColumns(null, null, "areas", "enter_title")) {
                        if (!rs.next()) {
                            stmt.executeUpdate("ALTER TABLE areas ADD COLUMN enter_title TEXT DEFAULT ''");
                        }
                    }
                    
                    try (ResultSet rs = conn.getMetaData().getColumns(null, null, "areas", "leave_title")) {
                        if (!rs.next()) {
                            stmt.executeUpdate("ALTER TABLE areas ADD COLUMN leave_title TEXT DEFAULT ''");
                        }
                    }

                    try (ResultSet rs = conn.getMetaData().getColumns(null, null, "areas", "potion_effects")) {
                        if (!rs.next()) {
                            stmt.executeUpdate("ALTER TABLE areas ADD COLUMN potion_effects TEXT DEFAULT '{}'");
                        }
                    }

                    // Create index on name column
                    stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_areas_name ON areas(name)");
                    
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to initialize database", e);
        }
    }

    public void saveArea(Area area) throws DatabaseException {
        if (area == null) {
            throw new IllegalArgumentException("Area cannot be null");
        }

        // Prevent recursion during database saves
        Set<String> inProgress = updatingAreas.get();
        String areaName = area.getName();
        if (inProgress.contains(areaName)) {
            // We're already saving this area in the current call stack
            if (plugin.isDebugMode()) {
                plugin.debug("Preventing recursive database save for area " + areaName);
            }
            return; // Skip this save to avoid recursion
        }
        inProgress.add(areaName);

        try {
            if (plugin.isDebugMode()) {
                plugin.debug("Starting saveArea transaction for: " + areaName);
            }
            
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                
                try {
                    try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT OR REPLACE INTO areas (name, world, x_min, x_max, y_min, y_max, z_min, z_max, " +
                        "priority, show_title, enter_message, leave_message, enter_title, leave_title, " +
                        "toggle_states, default_toggle_states, inherited_toggle_states, potion_effects) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
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
                        stmt.setString(11, dto.enterMessage());
                        stmt.setString(12, dto.leaveMessage());
                        stmt.setString(13, dto.enterTitle());
                        stmt.setString(14, dto.leaveTitle());
                        stmt.setString(15, dto.toggleStates().toString());
                        stmt.setString(16, dto.defaultToggleStates().toString());
                        stmt.setString(17, dto.inheritedToggleStates().toString());
                        stmt.setString(18, dto.potionEffects().toString());
                        
                        int result = stmt.executeUpdate();
                        
                        if (plugin.isDebugMode()) {
                            plugin.debug("Database insert/update for area " + dto.name() + ": " + result + " rows affected");
                        }
                    }
                    
                    // Ensure area title config exists
                    ensureAreaTitlesConfig(area.toDTO());
                    
                    // Commit the area save
                    conn.commit();
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Successfully committed area " + area.getName() + " to database");
                    }
                    
                    // Update cache
                    areaCache.put(area.getName(), area);
                    
                    // Explicitly check if the area was actually saved
                    try (PreparedStatement checkStmt = conn.prepareStatement(
                            "SELECT COUNT(*) FROM areas WHERE name = ?")) {
                        checkStmt.setString(1, area.getName());
                        try (ResultSet rs = checkStmt.executeQuery()) {
                            if (rs.next() && rs.getInt(1) > 0) {
                                if (plugin.isDebugMode()) {
                                    plugin.debug("Verified area " + area.getName() + " exists in database");
                                }
                            } else {
                                // Area doesn't exist in database despite apparent success
                                if (plugin.isDebugMode()) {
                                    plugin.debug("WARNING: Area " + area.getName() + " not found in database after save!");
                                }
                                throw new DatabaseException("Area not found in database after save: " + area.getName());
                            }
                        }
                    }
                    
                    // Add to memory storage via AreaManager
                    if (plugin.getAreaManager() != null) {
                        if (plugin.isDebugMode()) {
                            plugin.debug("Adding area " + area.getName() + " to AreaManager");
                        }
                        plugin.getAreaManager().addArea(area);
                    }
                    
                    // Synchronize permissions separately to avoid long transactions
                    if (plugin.getPermissionOverrideManager() != null) {
                        if (plugin.isDebugMode()) {
                            plugin.debug("Synchronizing permissions for area: " + area.getName());
                        }
                        plugin.getPermissionOverrideManager().synchronizeOnSave(area);
                    }
                    
                } catch (Exception e) {
                    conn.rollback();
                    if (plugin.isDebugMode()) {
                        plugin.debug("ERROR: Failed to save area " + area.getName() + ": " + e.getMessage());
                    }
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new DatabaseException("Failed to save area: " + e.getMessage(), e);
            }
        } finally {
            // Clean up thread-local to prevent memory leaks
            inProgress.remove(areaName);
        }
    }

    private void ensureAreaTitlesConfig(AreaDTO dto) {
        if (!dto.showTitle()) {
            return;
        }
        
        String areaName = dto.name();
        
        // Only create default titles if they don't already exist
        if (!plugin.getConfigManager().hasAreaTitleConfig(areaName)) {
            // Use titles from DTO if available, otherwise use defaults
            String enterTitle = dto.enterTitle().isEmpty() ? "§6Welcome to " + areaName : dto.enterTitle();
            String leaveTitle = dto.leaveTitle().isEmpty() ? "§6Leaving " + areaName : dto.leaveTitle();
            
            // Set title values in areaTitles.yml
            plugin.getConfigManager().setAreaTitleText(areaName, "enter", "main", enterTitle);
            plugin.getConfigManager().setAreaTitleText(areaName, "enter", "subtitle", dto.enterMessage());
            plugin.getConfigManager().setAreaTitleText(areaName, "enter", "fadeIn", "20");
            plugin.getConfigManager().setAreaTitleText(areaName, "enter", "stay", "40");
            plugin.getConfigManager().setAreaTitleText(areaName, "enter", "fadeOut", "20");
            
            plugin.getConfigManager().setAreaTitleText(areaName, "leave", "main", leaveTitle);
            plugin.getConfigManager().setAreaTitleText(areaName, "leave", "subtitle", dto.leaveMessage());
            plugin.getConfigManager().setAreaTitleText(areaName, "leave", "fadeIn", "20");
            plugin.getConfigManager().setAreaTitleText(areaName, "leave", "stay", "40");
            plugin.getConfigManager().setAreaTitleText(areaName, "leave", "fadeOut", "20");
            
            // Save the changes
            plugin.getConfigManager().saveAreaTitles();
        }
    }

    public void updateArea(Area area) throws DatabaseException {
        if (area == null) {
            throw new IllegalArgumentException("Area cannot be null");
        }

        // Prevent recursion during database updates
        Set<String> inProgress = updatingAreas.get();
        String areaName = area.getName();
        if (inProgress.contains(areaName)) {
            // We're already updating this area in the current call stack
            if (plugin.isDebugMode()) {
                plugin.debug("Preventing recursive database update for area " + areaName);
            }
            return; // Skip this update to avoid recursion
        }
        inProgress.add(areaName);
        
        try {
            if (plugin.isDebugMode()) {
                plugin.debug("Starting updateArea transaction for: " + areaName);
            }
            
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                
                try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE areas SET world = ?, x_min = ?, x_max = ?, y_min = ?, y_max = ?, z_min = ?, z_max = ?, " +
                    "priority = ?, show_title = ?, enter_message = ?, leave_message = ?, enter_title = ?, leave_title = ?, " +
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
                    stmt.setString(12, dto.enterTitle());
                    stmt.setString(13, dto.leaveTitle());
                    stmt.setString(14, dto.toggleStates().toString());
                    stmt.setString(15, dto.defaultToggleStates().toString());
                    stmt.setString(16, dto.inheritedToggleStates().toString());
                    stmt.setString(17, dto.potionEffects().toString());
                    stmt.setString(18, dto.name());

                    int updated = stmt.executeUpdate();
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Database update for area " + dto.name() + ": " + updated + " rows affected");
                        if (updated == 0) {
                            plugin.debug("WARNING: No rows were updated for area " + dto.name() + ". Area might not exist in database.");
                        }
                    }
                    
                    // Commit the area update
                    conn.commit();
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Successfully committed area update for " + area.getName() + " to database");
                    }
                    
                    // Clear area cache
                    invalidateAreaCache(area.getName());
                    
                    // Update memory storage via AreaManager
                    if (plugin.getAreaManager() != null) {
                        if (plugin.isDebugMode()) {
                            plugin.debug("Updating area " + area.getName() + " in AreaManager");
                        }
                        plugin.getAreaManager().updateArea(area);
                    }
                    
                    // Synchronize permissions separately to avoid long transactions
                    if (plugin.getPermissionOverrideManager() != null) {
                        if (plugin.isDebugMode()) {
                            plugin.debug("Synchronizing permissions for updated area: " + area.getName());
                        }
                        plugin.getPermissionOverrideManager().synchronizeOnSave(area);
                    }
                    
                } catch (Exception e) {
                    conn.rollback();
                    if (plugin.isDebugMode()) {
                        plugin.debug("ERROR: Failed to update area " + area.getName() + ": " + e.getMessage());
                    }
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new DatabaseException("Failed to update area: " + e.getMessage(), e);
            }
        } finally {
            // Clean up thread-local to prevent memory leaks
            inProgress.remove(areaName);
        }
    }

    public void flushChanges() throws DatabaseException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA wal_checkpoint(FULL)");
        } catch (SQLException e) {
            throw new DatabaseException("Failed to flush database changes", e);
        }
    }

    public void invalidateAreaCache(String areaName) {
        // Clear DatabaseManager's cache
        areaCache.invalidate(areaName);
        
        // Clear PermissionChecker cache
        if (plugin.getPermissionOverrideManager() != null && 
            plugin.getPermissionOverrideManager().getPermissionChecker() != null) {
            plugin.getPermissionOverrideManager().getPermissionChecker().invalidateCache(areaName);
        }
        
        // Clear AreaManager cache
        if (plugin.getAreaManager() != null) {
            plugin.getAreaManager().invalidateAreaCache(areaName);
        }
    }

    public void deleteArea(String name) throws DatabaseException {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            
            try {
                int rowsAffected;
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM areas WHERE name = ?")) {
                    stmt.setString(1, name);
                    rowsAffected = stmt.executeUpdate();
                }
                
                if (rowsAffected == 0) {
                    return;
                }
                
                // Delete permissions
                if (plugin.getPermissionOverrideManager() != null) {
                    plugin.getPermissionOverrideManager().deleteAreaPermissions(name);
                }
                
                conn.commit();
                invalidateAreaCache(name);
                
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to delete area", e);
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

    public Area loadArea(String areaName) throws DatabaseException {
        if (areaName == null || areaName.isEmpty()) {
            throw new IllegalArgumentException("Area name cannot be null or empty");
        }
        
        // Check cache first
        Area cachedArea = areaCache.getIfPresent(areaName);
        if (cachedArea != null) {
            return cachedArea;
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
                return null; // Area not found
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load area: " + areaName, e);
        }
    }

    private Area buildAreaFromResultSet(ResultSet rs) throws SQLException {
        try {
            String name = rs.getString("name");
            String world = rs.getString("world");
            int xMin = rs.getInt("x_min");
            int xMax = rs.getInt("x_max");
            int yMin = rs.getInt("y_min");
            int yMax = rs.getInt("y_max");
            int zMin = rs.getInt("z_min");
            int zMax = rs.getInt("z_max");
            int priority = rs.getInt("priority");
            boolean showTitle = rs.getBoolean("show_title");
            String enterMessage = rs.getString("enter_message");
            String leaveMessage = rs.getString("leave_message");
            String enterTitle = rs.getString("enter_title");
            String leaveTitle = rs.getString("leave_title");
            
            // Check for null strings (can happen with older database entries)
            enterMessage = enterMessage != null ? enterMessage : "";
            leaveMessage = leaveMessage != null ? leaveMessage : "";
            enterTitle = enterTitle != null ? enterTitle : "";
            leaveTitle = leaveTitle != null ? leaveTitle : "";

            // Parse JSON fields
            JSONObject toggleStates = new JSONObject(rs.getString("toggle_states"));
            JSONObject defaultToggleStates = new JSONObject(rs.getString("default_toggle_states"));
            JSONObject inheritedToggleStates = new JSONObject(rs.getString("inherited_toggle_states"));
            
            // Get potion effects JSON
            String potionEffectsJson = rs.getString("potion_effects");
            JSONObject potionEffects = potionEffectsJson != null && !potionEffectsJson.isEmpty() ? 
                new JSONObject(potionEffectsJson) : new JSONObject();
            
            // Create bounds object
            AreaDTO.Bounds bounds = new AreaDTO.Bounds(
                xMin,
                xMax,
                yMin,
                yMax,
                zMin,
                zMax
            );

            // Convert toggle states to permissions map
            Map<String, Boolean> permissionsMap = new HashMap<>();
            for (String key : toggleStates.keySet()) {
                permissionsMap.put(key, toggleStates.getBoolean(key));
            }

            // Lookup titles from areaTitles.yml if configured
            ConfigManager configManager = plugin.getConfigManager();
            String basePath = "areaTitles." + name;
            if (configManager.hasAreaTitleConfig(name)) {
                // Override with values from areaTitles.yml if they exist
                enterTitle = configManager.getAreaTitleText(name, "enter", "main", enterTitle);
                enterMessage = configManager.getAreaTitleText(name, "enter", "subtitle", enterMessage);
                leaveTitle = configManager.getAreaTitleText(name, "leave", "main", leaveTitle);
                leaveMessage = configManager.getAreaTitleText(name, "leave", "subtitle", leaveMessage);
            }

            // Create DTO
            AreaDTO dto = new AreaDTO(
                name,
                world,
                bounds,
                priority,
                showTitle,
                toggleStates,
                new HashMap<>(), // Empty group permissions
                new HashMap<>(), // Empty inherited permissions
                toggleStates,
                defaultToggleStates,
                inheritedToggleStates,
                AreaDTO.Permissions.fromMap(permissionsMap),
                enterMessage,
                leaveMessage,
                enterTitle,
                leaveTitle,
                new HashMap<>(), // Empty track permissions
                new HashMap<>(), // Empty player permissions
                potionEffects
            );

            // Build the area
            Area area = AreaBuilder.fromDTO(dto).build();
            
            // Load permissions if needed
            if (plugin.getPermissionOverrideManager() != null) {
                plugin.getPermissionOverrideManager().synchronizeOnLoad(area);
            }

            return area;
        } catch (Exception e) {
            logger.error("Error building area from result set", e);
            throw new SQLException("Failed to build area from database result", e);
        }
    }

    public Connection getConnection() throws SQLException {
        SQLException lastException = null;
        int maxRetries = 3;
        int retryDelayMs = 500;
        
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                return dataSource.getConnection();
            } catch (SQLException e) {
                lastException = e;
                
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

    public void updateAreaToggleState(String areaName, String permission, Object value) throws DatabaseException {
        // Prevent recursion during toggle state updates
        Set<String> inProgress = updatingAreas.get();
        String toggleKey = areaName + "_toggle_" + permission;
        if (inProgress.contains(toggleKey)) {
            // We're already updating this toggle state in the current call stack
            if (plugin.isDebugMode()) {
                plugin.debug("Preventing recursive toggle state update for area " + areaName + ", permission: " + permission);
            }
            return; // Skip this update to avoid recursion
        }
        inProgress.add(toggleKey);
        
        try {
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                
                try {
                    JSONObject toggleStates = null;
                    
                    // Get current toggle states
                    try (PreparedStatement queryStmt = conn.prepareStatement(
                        "SELECT toggle_states FROM areas WHERE name = ?")) {
                        queryStmt.setString(1, areaName);
                        try (ResultSet rs = queryStmt.executeQuery()) {
                            if (rs.next()) {
                                String togglesJson = rs.getString("toggle_states");
                                toggleStates = new JSONObject(togglesJson);
                            } else {
                                throw new DatabaseException("Area not found: " + areaName);
                            }
                        }
                    }
                    
                    // Update the specific toggle
                    toggleStates.put(permission, value);

                    // Update database
                    try (PreparedStatement updateStmt = conn.prepareStatement(
                        "UPDATE areas SET toggle_states = ? WHERE name = ?")) {
                        updateStmt.setString(1, toggleStates.toString());
                        updateStmt.setString(2, areaName);
                        updateStmt.executeUpdate();
                    }
                    
                    conn.commit();
                    areaCache.invalidate(areaName);
                    
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new DatabaseException("Failed to update toggle state", e);
            }
        } finally {
            // Clean up thread-local to prevent memory leaks
            inProgress.remove(toggleKey);
        }
    }
    
    public void updateAllAreaToggleStates(String areaName, JSONObject toggleStates) throws DatabaseException {
        // Prevent recursion during toggle states updates
        Set<String> inProgress = updatingAreas.get();
        String toggleKey = areaName + "_toggles_all";
        if (inProgress.contains(toggleKey)) {
            // We're already updating toggle states for this area in the current call stack
            if (plugin.isDebugMode()) {
                plugin.debug("Preventing recursive toggle states update for area " + areaName);
            }
            return; // Skip this update to avoid recursion
        }
        inProgress.add(toggleKey);
        
        try {
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                
                try {
                    try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE areas SET toggle_states = ? WHERE name = ?")) {
                        stmt.setString(1, toggleStates.toString());
                        stmt.setString(2, areaName);
                        int updated = stmt.executeUpdate();
                        
                        if (updated == 0) {
                            throw new DatabaseException("Area not found: " + areaName);
                        }
                    }
                    
                    conn.commit();
                    areaCache.invalidate(areaName);
                    
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new DatabaseException("Failed to update toggle states", e);
            }
        } finally {
            // Clean up thread-local to prevent memory leaks
            inProgress.remove(toggleKey);
        }
    }
}
