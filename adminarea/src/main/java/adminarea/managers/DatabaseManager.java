package adminarea.managers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.exception.DatabaseException;
import adminarea.logging.PluginLogger;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

public class DatabaseManager {
    private HikariDataSource dataSource;
    private final Cache<String, Area> areaCache;
    private final AdminAreaProtectionPlugin plugin;
    private final PluginLogger logger;

    public DatabaseManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.logger = new PluginLogger(plugin);
        this.areaCache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();
    }

    public void init() {
        try {
            // Ensure plugin data folder exists
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            // Prepare storage folder and copy bundled storage on first run
            File storageFolder = new File(plugin.getDataFolder(), "storage");
            if (!storageFolder.exists()) {
                storageFolder.mkdirs();
                try (InputStream in = plugin.getResource("storage/areas.db")) {
                    if (in != null) {
                        File outFile = new File(storageFolder, "areas.db");
                        Files.copy(in, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        plugin.getLogger().warning("Bundled storage file not found: storage/areas.db");
                    }
                }
            }
            // Use the storage folder's database file
            String dbPath = new File(storageFolder, "areas.db").getAbsolutePath();
            Class.forName("org.sqlite.JDBC");

            // Initialize connection pool
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite:" + dbPath);
            config.setMaximumPoolSize(10);
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            dataSource = new HikariDataSource(config);

            // Initialize Flyway for migrations
            initFlyway();

            initializeDatabase();
        } catch (Exception e) {
            logger.error("Failed to initialize database", e);
            throw new DatabaseException("Database initialization failed", e);
        }
    }

    private void initFlyway() {
        try {
            Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:/db/migration")
                .validateMigrationNaming(true)
                .load();
            
            // Execute migrations and get result
            var result = flyway.migrate();
            
            // Log migration results
            logger.info(String.format(
                "Database migration complete - %d migrations applied",
                result.migrationsExecuted
            ));

            // Log specific migrations if in debug mode
            if (plugin.isDebugMode() && result.migrationsExecuted > 0) {
                for (var migration : result.migrations) {
                    logger.debug(String.format(
                        "Migration: %s - %s",
                        migration.version,
                        migration.description
                    ));
                }
            }
        } catch (Exception e) {
            throw new DatabaseException("Failed to initialize Flyway migrations", e);
        }
    }

    private void initializeDatabase() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            // Create areas table with new columns
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
                    settings TEXT,
                    group_permissions TEXT,
                    inherited_permissions TEXT,
                    enter_message TEXT,
                    leave_message TEXT,
                    toggle_states TEXT DEFAULT '{}',
                    default_toggle_states TEXT DEFAULT '{}',
                    inherited_toggle_states TEXT DEFAULT '{}'
                )
            """);

            // Create group_permissions table
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS group_permissions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "area_id INTEGER," +
                    "group_name TEXT," +
                    "permissions TEXT," +
                    "FOREIGN KEY(area_id) REFERENCES areas(id) ON DELETE CASCADE," +
                    "UNIQUE(area_id, group_name)" +
                    ")");

            // Remove any duplicates that might exist
            cleanupDuplicates();
        }
    }

    private void cleanupDuplicates() throws SQLException {
        try (Connection conn = getConnection()) {
            // Keep only the latest entry for each area name
            conn.createStatement().executeUpdate(
                "DELETE FROM areas WHERE id NOT IN (" +
                "SELECT MAX(id) FROM areas GROUP BY name)");
        }
    }

    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void saveAreas(List<Area> areas) {
        String sql = "REPLACE INTO areas (name, world, x_min, x_max, y_min, y_max, z_min, z_max, priority, show_title, settings, group_permissions, inherited_permissions, enter_message, leave_message) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            conn.setAutoCommit(false);
            
            for (Area area : areas) {
                ps.setString(1, area.getName());
                ps.setString(2, area.getWorld());
                ps.setInt(3, area.getXMin());
                ps.setInt(4, area.getXMax());
                ps.setInt(5, area.getYMin());
                ps.setInt(6, area.getYMax());
                ps.setInt(7, area.getZMin());
                ps.setInt(8, area.getZMax());
                ps.setInt(9, area.getPriority());
                ps.setBoolean(10, area.isShowTitle());
                ps.setString(11, area.getSettings().toString());
                ps.setString(12, area.getGroupPermissions().toString());
                ps.setString(13, new JSONObject(area.getAllGroupPermissions()).toString());
                ps.setString(14, area.getEnterMessage());
                ps.setString(15, area.getLeaveMessage());
                ps.addBatch();
                
                // Update cache
                areaCache.put(area.getName(), area);
            }
            
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            logger.error("Error in batch area save", e);
            throw new DatabaseException("Batch area save failed", e);
        }
    }

    public Area getArea(String name) {
        // Check cache first
        Area cachedArea = areaCache.getIfPresent(name);
        if (cachedArea != null) {
            return cachedArea;
        }

        // If not in cache, load from database
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM areas WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Area area = buildAreaFromResultSet(rs);
                    areaCache.put(name, area);
                    return area;
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to fetch area: " + name, e);
            throw new DatabaseException("Could not fetch area: " + name, e);
        }
        return null;
    }

    private Area buildAreaFromResultSet(ResultSet rs) throws SQLException {
        Area area = Area.builder()
            .name(rs.getString("name"))
            .world(rs.getString("world"))
            .coordinates(
                rs.getInt("x_min"),
                rs.getInt("x_max"),
                rs.getInt("y_min"),
                rs.getInt("y_max"),
                rs.getInt("z_min"),
                rs.getInt("z_max")
            )
            .priority(rs.getInt("priority"))
            .showTitle(rs.getBoolean("show_title"))
            .settings(new JSONObject(rs.getString("settings")))
            .toggleStates(new JSONObject(rs.getString("toggle_states")))
            .defaultToggleStates(new JSONObject(rs.getString("default_toggle_states")))
            .inheritedToggleStates(new JSONObject(rs.getString("inherited_toggle_states")))
            .build();

        // Load group permissions
        try (Connection conn = getConnection()) {
            loadGroupPermissions(conn, area, rs.getInt("id"));
        }

        return area;
    }

    private void saveGroupPermissions(Connection conn, Area area, int areaId) throws SQLException {
        String sql = "INSERT OR REPLACE INTO group_permissions (area_id, group_name, permissions) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            Map<String, Map<String, Boolean>> groupPerms = area.getAllGroupPermissions();
            for (Map.Entry<String, Map<String, Boolean>> entry : groupPerms.entrySet()) {
                ps.setInt(1, areaId);
                ps.setString(2, entry.getKey());
                ps.setString(3, new JSONObject(entry.getValue()).toString());
                ps.executeUpdate();
            }
        }
    }

    private void loadGroupPermissions(Connection conn, Area area, int areaId) throws SQLException {
        String sql = "SELECT group_name, permissions FROM group_permissions WHERE area_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, areaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String groupName = rs.getString("group_name");
                    JSONObject perms = new JSONObject(rs.getString("permissions"));
                    Map<String, Boolean> permMap = new HashMap<>();
                    for (String key : perms.keySet()) {
                        permMap.put(key, perms.getBoolean(key));
                    }
                    area.setGroupPermissions(groupName, permMap);
                }
            }
        }
    }

    public void saveArea(Area area) {
        String sql = "INSERT OR REPLACE INTO areas (name, world, x_min, x_max, y_min, y_max, z_min, z_max, " +
                    "priority, show_title, settings, group_permissions, inherited_permissions, enter_message, leave_message, " +
                    "toggle_states, default_toggle_states, inherited_toggle_states) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // First, save main area data
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, area.getName());
                    ps.setString(2, area.getWorld());
                    ps.setInt(3, area.getXMin());
                    ps.setInt(4, area.getXMax());
                    ps.setInt(5, area.getYMin());
                    ps.setInt(6, area.getYMax());
                    ps.setInt(7, area.getZMin());
                    ps.setInt(8, area.getZMax());
                    ps.setInt(9, area.getPriority());
                    ps.setBoolean(10, area.isShowTitle());
                    ps.setString(11, area.getSettings().toString());
                    ps.setString(12, area.getGroupPermissions().toString());
                    ps.setString(13, new JSONObject(area.getAllGroupPermissions()).toString());
                    ps.setString(14, area.getEnterMessage());
                    ps.setString(15, area.getLeaveMessage());
                    ps.setString(16, area.getToggleStates().toString());
                    ps.setString(17, area.getDefaultToggleStates().toString());
                    ps.setString(18, area.getInheritedToggleStates().toString());
                    ps.executeUpdate();
                }

                // Get the area ID using a separate query
                try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM areas WHERE name = ?")) {
                    ps.setString(1, area.getName());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            int areaId = rs.getInt("id");
                            // Save group permissions
                            saveGroupPermissions(conn, area, areaId);
                        }
                    }
                }
                
                conn.commit();
                // Update cache
                areaCache.put(area.getName(), area);
                
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            logger.error("Error saving area " + area.getName(), e);
            throw new DatabaseException("Could not save area: " + area.getName(), e);
        }
    }

    public void updateArea(Area area) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE areas SET world=?, x_min=?, x_max=?, y_min=?, y_max=?, z_min=?, z_max=?, " +
                "priority=?, show_title=?, settings=?, group_permissions=?, inherited_permissions=?, " +
                "enter_message=?, leave_message=?, toggle_states=?, default_toggle_states=?, " +
                "inherited_toggle_states=? WHERE name=?")) {
            
            ps.setString(1, area.getWorld());
            ps.setInt(2, area.getXMin());
            ps.setInt(3, area.getXMax());
            ps.setInt(4, area.getYMin());
            ps.setInt(5, area.getYMax());
            ps.setInt(6, area.getZMin());
            ps.setInt(7, area.getZMax());
            ps.setInt(8, area.getPriority());
            ps.setBoolean(9, area.isShowTitle());
            ps.setString(10, area.getSettings().toString());
            ps.setString(11, area.getGroupPermissions().toString());
            ps.setString(12, new JSONObject(area.getAllGroupPermissions()).toString());
            ps.setString(13, area.getEnterMessage());
            ps.setString(14, area.getLeaveMessage());
            ps.setString(15, area.getToggleStates().toString());
            ps.setString(16, area.getDefaultToggleStates().toString());
            ps.setString(17, area.getInheritedToggleStates().toString());
            ps.setString(18, area.getName());
            ps.executeUpdate();
            
            // Update cache
            areaCache.put(area.getName(), area);
        } catch (SQLException e) {
            logger.error("Error updating area " + area.getName(), e);
            throw new DatabaseException("Could not update area: " + area.getName(), e);
        }
    }

    public void deleteArea(String name) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM areas WHERE name=?")) {
            ps.setString(1, name);
            ps.executeUpdate();
            
            // Remove from cache
            areaCache.invalidate(name);
        } catch (SQLException e) {
            logger.error("Error deleting area " + name, e);
            throw new DatabaseException("Could not delete area: " + name, e);
        }
    }

    public List<Area> loadAreas() {
        List<Area> areas = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM areas")) {

            while (rs.next()){
                Area area = buildAreaFromResultSet(rs);
                areas.add(area);
                
                // Update cache
                areaCache.put(area.getName(), area);
            }
        } catch (SQLException e) {
            logger.error("Error loading areas", e);
            throw new DatabaseException("Could not load areas", e);
        }
        return areas;
    }
}
