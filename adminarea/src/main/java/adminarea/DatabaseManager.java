package adminarea;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

public class DatabaseManager {
    private Connection connection;
    private final AdminAreaProtectionPlugin plugin;

    public DatabaseManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        try {
            // Create plugin data directory if it doesn't exist
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            
            Class.forName("org.sqlite.JDBC");
            String dbPath = plugin.getDataFolder().getAbsolutePath() + "/areas.db";
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS areas (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "name TEXT," +
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
            }
        } catch (Exception e) {
            plugin.getLogger().error("Could not initialize database", e);
            connection = null;
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch(SQLException e) {
            e.printStackTrace();
        }
    }

    public void saveArea(Area area) {
        if (connection == null) {
            plugin.getLogger().error("Database connection is null. Cannot save area.");
            return;
        }
        try {
            if (connection.isClosed()) {
                plugin.getLogger().error("Database connection is closed. Re-initializing...");
                init(); // Attempt to re-initialize the connection
                if (connection == null || connection.isClosed()) {
                    plugin.getLogger().error("Failed to re-initialize database connection. Aborting save.");
                    return;
                }
            }
            String sql = "INSERT INTO areas (name, world, x_min, x_max, y_min, y_max, z_min, z_max, priority, show_title, settings) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            plugin.getLogger().info("Executing SQL: " + sql); // Log the SQL statement
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
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

                // Log the values being saved
                plugin.getLogger().info("Saving area: name=" + area.getName() +
                        ", world=" + area.getWorld() +
                        ", x_min=" + area.getXMin() +
                        ", x_max=" + area.getXMax() +
                        ", y_min=" + area.getYMin() +
                        ", y_max=" + area.getYMax() +
                        ", z_min=" + area.getZMin() +
                        ", z_max=" + area.getZMax() +
                        ", priority=" + area.getPriority() +
                        ", show_title=" + area.isShowTitle() +
                        ", settings=" + area.getSettings().toString());

                ps.executeUpdate();
            }
        } catch(SQLException e) {
            plugin.getLogger().error("Error saving area " + area.getName(), e);
            plugin.getLogger().error("SQL Exception details:", e); // Log the full exception
        }
    }

    public void updateArea(Area area) {
        try {
            if (connection == null || connection.isClosed()) {
                plugin.getLogger().error("Database connection is not available");
                return;
            }
            String sql = "UPDATE areas SET world=?, x_min=?, x_max=?, y_min=?, y_max=?, z_min=?, z_max=?, priority=?, show_title=?, settings=? WHERE name=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
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
            }
        } catch(SQLException e) {
            plugin.getLogger().error("Error updating area " + area.getName(), e);
        }
    }

    public void deleteArea(String name) {
        try {
            if (connection == null || connection.isClosed()) {
                plugin.getLogger().error("Database connection is not available");
                return;
            }
            String sql = "DELETE FROM areas WHERE name=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.executeUpdate();
            }
        } catch(SQLException e) {
            plugin.getLogger().error("Error deleting area " + name, e);
        }
    }

    public List<Area> loadAreas() {
        List<Area> areas = new ArrayList<>();
        try {
            if (connection == null || connection.isClosed()) {
                plugin.getLogger().error("Database connection is not available");
                return areas;
            }

            String sql = "SELECT * FROM areas";
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(sql)) {

                while (rs.next()){
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
                    String settingsStr = rs.getString("settings");
                    Area area = new Area(name, world, xMin, xMax, yMin, yMax, zMin, zMax, priority, showTitle);
                    area.setSettings(new JSONObject(settingsStr));
                    areas.add(area);
                }
            }
        } catch(SQLException e) {
            plugin.getLogger().error("Error loading areas", e);
        }
        return areas;
    }
}
