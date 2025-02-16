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
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
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
            flyway.migrate();
        } catch (Exception e) {
            throw new DatabaseException("Failed to initialize Flyway migrations", e);
        }
    }

    private void initializeDatabase() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            // Create areas table with UNIQUE constraint on name
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS areas (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT UNIQUE," + // Add UNIQUE constraint
                    "world TEXT," +
                    "x_min INTEGER," +
                    "x_max INTEGER," +
                    "y_min INTEGER," +
                    "y_max INTEGER," +
                    "z_min INTEGER," +
                    "z_max INTEGER," +
                    "priority INTEGER," +
                    "show_title BOOLEAN," +
                    "settings TEXT" +
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
        String sql = "REPLACE INTO areas (name, world, x_min, x_max, y_min, y_max, z_min, z_max, priority, show_title, settings) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        
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
        return Area.builder()
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
            .build();
    }

    public void saveArea(Area area) {
        saveAreas(List.of(area));
    }

    public void updateArea(Area area) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE areas SET world=?, x_min=?, x_max=?, y_min=?, y_max=?, z_min=?, z_max=?, priority=?, show_title=?, settings=? WHERE name=?")) {
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
            ps.setString(11, area.getName());
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
