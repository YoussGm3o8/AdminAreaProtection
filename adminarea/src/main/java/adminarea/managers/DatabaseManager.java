package adminarea.managers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaDTO;
import adminarea.area.AreaBuilder;
import adminarea.exception.DatabaseException;
import adminarea.logging.PluginLogger;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
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

    public void init() throws DatabaseException {
        try {
            // Ensure plugin data folder exists
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            // Prepare storage folder and copy bundled storage on first run
            File storageFolder = new File(plugin.getDataFolder(), "storage");
            if (!storageFolder.exists()) {
                storageFolder.mkdirs();
                copyBundledDatabase(storageFolder);
            }
            // Use the storage folder's database file
            String dbPath = new File(storageFolder, "areas.db").getAbsolutePath();
            initializeDataSource(dbPath);
            initFlyway();
            initializeDatabase();
        } catch (Exception e) {
            throw new DatabaseException("Failed to initialize database", e);
        }
    }

    private void copyBundledDatabase(File storageFolder) throws DatabaseException {
        try (InputStream in = plugin.getResource("storage/areas.db")) {
            if (in != null) {
                File outFile = new File(storageFolder, "areas.db");
                Files.copy(in, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new DatabaseException("Failed to copy bundled database", e);
        }
    }

    private void initializeDataSource(String dbPath) throws DatabaseException {
        try {
            Class.forName("org.sqlite.JDBC");
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite:" + dbPath);
            config.setMaximumPoolSize(10);
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            dataSource = new HikariDataSource(config);
        } catch (Exception e) {
            throw new DatabaseException("Failed to initialize connection pool", e);
        }
    }

    private void initFlyway() throws DatabaseException {
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

    public void saveAreas(List<Area> areas) throws DatabaseException {
        String sql = "REPLACE INTO areas (name, world, x_min, x_max, y_min, y_max, z_min, z_max, " +
                    "priority, show_title, settings, group_permissions, inherited_permissions, " +
                    "enter_message, leave_message) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            conn.setAutoCommit(false);
            
            for (Area area : areas) {
                AreaDTO dto = area.toDTO();
                AreaDTO.Bounds bounds = dto.bounds();
                ps.setString(1, dto.name());
                ps.setString(2, dto.world());
                ps.setInt(3, bounds.xMin());
                ps.setInt(4, bounds.xMax());
                ps.setInt(5, bounds.yMin());
                ps.setInt(6, bounds.yMax());
                ps.setInt(7, bounds.zMin());
                ps.setInt(8, bounds.zMax());
                ps.setInt(9, dto.priority());
                ps.setBoolean(10, dto.showTitle());
                ps.setString(11, dto.settings().toString());
                ps.setString(12, new JSONObject(dto.groupPermissions()).toString());
                ps.setString(13, new JSONObject(dto.inheritedPermissions()).toString());
                ps.setString(14, dto.enterMessage());
                ps.setString(15, dto.leaveMessage());
                ps.addBatch();
                
                // Update cache
                areaCache.put(dto.name(), area);
            }
            
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            logger.error("Error in batch area save", e);
            throw new DatabaseException("Batch area save failed", e);
        }
    }

    public Area getArea(String name) throws DatabaseException {
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
        // Get bounds from database
        AreaDTO.Bounds bounds = new AreaDTO.Bounds(
            rs.getInt("x_min"),
            rs.getInt("x_max"),
            rs.getInt("y_min"),
            rs.getInt("y_max"),
            rs.getInt("z_min"),
            rs.getInt("z_max")
        );

        // Parse JSON strings
        JSONObject settings = new JSONObject(rs.getString("settings"));
        JSONObject toggleStates = new JSONObject(rs.getString("toggle_states"));
        JSONObject defaultToggleStates = new JSONObject(rs.getString("default_toggle_states"));
        JSONObject inheritedToggleStates = new JSONObject(rs.getString("inherited_toggle_states"));

        // Parse group permissions
        Map<String, Map<String, Boolean>> groupPerms = parseGroupPermissions(rs.getString("group_permissions"));
        Map<String, Map<String, Boolean>> inheritedPerms = parseGroupPermissions(rs.getString("inherited_permissions"));

        // Create AreaDTO
        AreaDTO dto = new AreaDTO(
            rs.getString("name"),
            rs.getString("world"),
            bounds,
            rs.getInt("priority"),
            rs.getBoolean("show_title"),
            settings,
            groupPerms,
            inheritedPerms,
            toggleStates,
            defaultToggleStates,
            inheritedToggleStates,
            AreaDTO.Permissions.fromMap(new HashMap<>()),  // Default permissions
            rs.getString("enter_message"),
            rs.getString("leave_message")
        );
        // Create and return Area instance
        return AreaBuilder
            .fromDTO(dto)
            .build();
    }

    public void saveArea(Area area) throws DatabaseException {
        if (area == null) {
            throw new DatabaseException("Cannot save null area");
        }

        // Get DTO from area
        AreaDTO dto = area.toDTO();
        
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                saveAreaDTO(conn, dto);
                conn.commit();
                areaCache.put(dto.name(), area);
            } catch (SQLException e) {
                conn.rollback();
                throw new DatabaseException("Failed to save area: " + dto.name(), e);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Database connection failed while saving area", e);
        }
    }

    private void saveAreaDTO(Connection conn, AreaDTO dto) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT OR REPLACE INTO areas (name, world, x_min, x_max, y_min, y_max, z_min, z_max, " +
            "priority, show_title, settings, group_permissions, inherited_permissions, " + 
            "enter_message, leave_message, toggle_states, default_toggle_states, " +
            "inherited_toggle_states) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            
            var bounds = dto.bounds();
            ps.setString(1, dto.name());
            ps.setString(2, dto.world());
            ps.setInt(3, bounds.xMin());
            ps.setInt(4, bounds.xMax());
            ps.setInt(5, bounds.yMin());
            ps.setInt(6, bounds.yMax());
            ps.setInt(7, bounds.zMin());
            ps.setInt(8, bounds.zMax());
            ps.setInt(9, dto.priority());
            ps.setBoolean(10, dto.showTitle());
            ps.setString(11, dto.settings().toString());
            ps.setString(12, new JSONObject(dto.groupPermissions()).toString());
            ps.setString(13, new JSONObject(dto.inheritedPermissions()).toString());
            ps.setString(14, dto.enterMessage());
            ps.setString(15, dto.leaveMessage());
            ps.setString(16, dto.toggleStates().toString());
            ps.setString(17, dto.defaultToggleStates().toString());
            ps.setString(18, dto.inheritedToggleStates().toString());
            ps.executeUpdate();
        }
    }

    private Map<String, Map<String, Boolean>> parseGroupPermissions(String json) {
        if (json == null || json.isEmpty()) {
            return new HashMap<>();
        }
        try {
            JSONObject jsonObj = new JSONObject(json);
            Map<String, Map<String, Boolean>> result = new HashMap<>();
            
            for (String group : jsonObj.keySet()) {
                JSONObject perms = jsonObj.getJSONObject(group);
                Map<String, Boolean> groupPerms = new HashMap<>();
                for (String perm : perms.keySet()) {
                    groupPerms.put(perm, perms.getBoolean(perm));
                }
                result.put(group, groupPerms);
            }
            return result;
        } catch (Exception e) {
            logger.error("Error parsing group permissions", e);
            return new HashMap<>();
        }
    }

    private void saveGroupPermissions(Connection conn, Area area, int areaId) throws SQLException {
        String sql = "INSERT OR REPLACE INTO group_permissions (area_id, group_name, permissions) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            AreaDTO dto = area.toDTO();
            Map<String, Map<String, Boolean>> groupPerms = dto.groupPermissions();
            
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
                Map<String, Map<String, Boolean>> groupPermissions = new HashMap<>();
                while (rs.next()) {
                    String groupName = rs.getString("group_name");
                    JSONObject perms = new JSONObject(rs.getString("permissions"));
                    Map<String, Boolean> permMap = new HashMap<>();
                    for (String key : perms.keySet()) {
                        permMap.put(key, perms.getBoolean(key));
                    }
                    groupPermissions.put(groupName, permMap);
                }
                AreaDTO updatedDto = new AreaDTO(
                    area.toDTO().name(),
                    area.toDTO().world(),
                    area.toDTO().bounds(),
                    area.toDTO().priority(),
                    area.toDTO().showTitle(),
                    area.toDTO().settings(),
                    groupPermissions,
                    area.toDTO().inheritedPermissions(),
                    area.toDTO().toggleStates(),
                    area.toDTO().defaultToggleStates(),
                    area.toDTO().inheritedToggleStates(),
                    area.toDTO().permissions(),
                    area.toDTO().enterMessage(),
                    area.toDTO().leaveMessage()
                );
                AreaBuilder.fromDTO(updatedDto).build();
            }
        }
    }

    public void updateArea(Area area) throws DatabaseException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE areas SET world=?, x_min=?, x_max=?, y_min=?, y_max=?, z_min=?, z_max=?, " +
                "priority=?, show_title=?, settings=?, group_permissions=?, inherited_permissions=?, " +
                "enter_message=?, leave_message=?, toggle_states=?, default_toggle_states=?, " +
                "inherited_toggle_states=? WHERE name=?")) {
            
            AreaDTO dto = area.toDTO();
            AreaDTO.Bounds bounds = dto.bounds();
            
            ps.setString(1, dto.world());
            ps.setInt(2, bounds.xMin());
            ps.setInt(3, bounds.xMax());
            ps.setInt(4, bounds.yMin());
            ps.setInt(5, bounds.yMax());
            ps.setInt(6, bounds.zMin());
            ps.setInt(7, bounds.zMax());
            ps.setInt(8, dto.priority());
            ps.setBoolean(9, dto.showTitle());
            ps.setString(10, dto.settings().toString());
            ps.setString(11, new JSONObject(dto.groupPermissions()).toString());
            ps.setString(12, new JSONObject(dto.inheritedPermissions()).toString());
            ps.setString(13, dto.enterMessage());
            ps.setString(14, dto.leaveMessage());
            ps.setString(15, dto.toggleStates().toString());
            ps.setString(16, dto.defaultToggleStates().toString());
            ps.setString(17, dto.inheritedToggleStates().toString());
            ps.setString(18, dto.name());
            ps.executeUpdate();
            
            // Update cache
            areaCache.put(dto.name(), area);
        } catch (SQLException e) {
            logger.error("Error updating area " + area.getName(), e);
            throw new DatabaseException("Could not update area: " + area.getName(), e);
        }
    }

    public void deleteArea(String name) throws DatabaseException {
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

    public List<Area> loadAreas() throws DatabaseException {
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
