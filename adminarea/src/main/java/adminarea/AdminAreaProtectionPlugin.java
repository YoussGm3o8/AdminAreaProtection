package adminarea;

/**
 * Main plugin class for AdminAreaProtection.
 * Handles initialization, configuration management, and core functionality.
 * 
 * @version 1.0
 * @author roki
 */
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.event.Listener;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.Player;
import cn.nukkit.level.Position;

import java.util.List;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import adminarea.listeners.FormResponseListener;
import adminarea.listeners.ProtectionListener;
import adminarea.listeners.WandListener;
import adminarea.managers.AreaManager;
import adminarea.managers.DatabaseManager;
import adminarea.managers.GuiManager;
import adminarea.api.AdminAreaAPI;
import adminarea.area.Area;
import adminarea.area.AreaCommand;
import adminarea.config.ConfigManager;
import adminarea.util.PerformanceMonitor;
import io.micrometer.core.instrument.Timer;
import adminarea.exception.DatabaseException;

public class AdminAreaProtectionPlugin extends PluginBase implements Listener {

    private static AdminAreaProtectionPlugin instance;
    private AdminAreaAPI api;

    private DatabaseManager dbManager;
    private AreaCommand areaCommand;
    private HashMap<String, Position[]> playerPositions = new HashMap<>(); // Store player positions
    private HashMap<String, String> formIdMap = new HashMap<>(); // Store form IDs
    private HashMap<String, Boolean> bypassingPlayers = new HashMap<>(); // Store players bypassing protection
    private boolean globalAreaProtection = false; // new flag
    private LuckPerms luckPermsApi; // New field for LuckPerms API
    private ConfigManager configManager;
    private AreaManager areaManager;
    private PerformanceMonitor performanceMonitor; // Add performance monitor
    private GuiManager guiManager;

    private WandListener wandListener; // Add WandListener

    private boolean enableMessages;
    private String msgBlockBreak;
    private String msgBlockPlace;
    private String msgPVP;

    private boolean debugMode = false;

    /**
     * Initializes the plugin, loads configuration, and sets up required components.
     * This includes:
     * - Configuration management
     * - Database initialization
     * - Event listeners registration
     * - Commands registration
     * - LuckPerms integration (if available)
     */
    @Override
    public void onEnable() {
        instance = this;
        
        // Initialize performance monitor first
        performanceMonitor = new PerformanceMonitor(this);
        Timer.Sample startupTimer = performanceMonitor.startTimer();
        
        try {
            // Initialize config manager first
            configManager = new ConfigManager(this);
            configManager.load();

            // Ensure data folder exists and copy config.yml if missing
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }
            saveResource("config.yml", false); // copies bundled config if it doesn't exist
            reloadConfig();       // reload config after saving

            // Log plugin enabling start
            getLogger().info("Enabling AdminAreaProtectionPlugin...");

            getLogger().info("Configuration loaded.");

            // Replace direct config access with config manager
            reloadConfigValues();

            // Initialize managers with performance monitoring
            Timer.Sample dbInitTimer = performanceMonitor.startTimer();
            dbManager = new DatabaseManager(this);
            try {
                dbManager.init();
                performanceMonitor.stopTimer(dbInitTimer, "database_init");
                getLogger().info("Database initialized successfully");
            } catch (DatabaseException e) {
                performanceMonitor.stopTimer(dbInitTimer, "database_init_failed");
                getLogger().error("Failed to initialize database", e);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            Timer.Sample areaInitTimer = performanceMonitor.startTimer();
            areaManager = new AreaManager(this);
            try {
                List<Area> loadedAreas = dbManager.loadAreas();
                for (Area area : loadedAreas) {
                    areaManager.addArea(area);
                }
                performanceMonitor.stopTimer(areaInitTimer, "area_manager_init");
                getLogger().info("Loaded " + loadedAreas.size() + " areas from database");
            } catch (DatabaseException e) {
                performanceMonitor.stopTimer(areaInitTimer, "area_manager_init_failed");
                getLogger().error("Failed to load areas from database", e);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            // Initialize GUI manager with performance monitoring
            Timer.Sample guiInitTimer = performanceMonitor.startTimer();
            guiManager = new GuiManager(this);
            performanceMonitor.stopTimer(guiInitTimer, "gui_manager_init");

            wandListener = new WandListener(this); // Initialize WandListener

            // Register events and commands
            getServer().getPluginManager().registerEvents(this, this);
            getServer().getPluginManager().registerEvents(new ProtectionListener(this), this);
            getServer().getPluginManager().registerEvents(new FormResponseListener(this), this);
            getServer().getPluginManager().registerEvents(wandListener, this); // Register WandListener
            getLogger().info("FormResponseListener registered."); // Added log

            // Initialize AreaCommand
            areaCommand = new AreaCommand(this);

            // Register the command
            getServer().getCommandMap().register("area", areaCommand);
            getLogger().info("Area command registered.");

            // Check for LuckPerms presence and retrieve its API if available.
            if (getServer().getPluginManager().getPlugin("LuckPerms") != null) {
                luckPermsApi = LuckPermsProvider.get();
                getLogger().info("LuckPerms detected. Integration is enabled.");
                // ...optional LuckPerms-specific setup...
            } else {
                getLogger().info("LuckPerms not found. Proceeding without it.");
            }

            // Initialize API
            api = new AdminAreaAPI(this);

            performanceMonitor.stopTimer(startupTimer, "plugin_startup");
            getLogger().info("AdminAreaProtectionPlugin enabled successfully.");

        } catch (Exception e) {
            if (performanceMonitor != null) {
                performanceMonitor.stopTimer(startupTimer, "plugin_startup_failed");
            }
            getLogger().error("Critical error during plugin startup", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /**
     * Gets the plugin instance.
     * @return The plugin instance
     */
    public static AdminAreaProtectionPlugin getInstance() {
        return instance;
    }

    /**
     * Gets the plugin's API.
     * @return The API instance
     */
    public AdminAreaAPI getAPI() {
        return api;
    }

    /**
     * Reloads configuration values from disk and updates cached settings.
     */
    public void reloadConfigValues() {
        enableMessages = configManager.isEnabled("enableMessages");
        msgBlockBreak = configManager.getMessage("blockBreak");
        msgBlockPlace = configManager.getMessage("blockPlace");
        msgPVP = configManager.getMessage("pvp");
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("area") || command.getName().equalsIgnoreCase("areaprotect") || command.getName().equalsIgnoreCase("areaadmin")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("adminarea.command.reload")) {
                    sender.sendMessage("§cYou don't have permission to reload the configuration.");
                    return true;
                }
                configManager.reload();
                sender.sendMessage("§aConfiguration reloaded successfully.");
                return true;
            }
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cThis command can only be used by players!");
                return true;
            }
            return areaCommand.execute(sender, label, args);
        }
        return false;
    }

    @Override
    public void onDisable() {
        // Cleanup managers in reverse order of initialization
        if (guiManager != null) {
            // Cancel any pending GUI tasks
            getServer().getScheduler().cancelTask(this);
        }

        if (areaManager != null) {
            // Save any pending area changes
            Timer.Sample saveTimer = performanceMonitor.startTimer();
            try {
                for (Area area : areaManager.getAllAreas()) {
                    dbManager.saveArea(area);
                }
                performanceMonitor.stopTimer(saveTimer, "final_area_save");
            } catch (Exception e) {
                performanceMonitor.stopTimer(saveTimer, "final_area_save_failed");
                getLogger().error("Error saving areas during shutdown", e);
            }
        }

        if (dbManager != null) {
            Timer.Sample dbCloseTimer = performanceMonitor.startTimer();
            try {
                dbManager.close();
                performanceMonitor.stopTimer(dbCloseTimer, "database_close");
            } catch (Exception e) {
                performanceMonitor.stopTimer(dbCloseTimer, "database_close_failed");
                getLogger().error("Error closing database", e);
            }
        }

        if (performanceMonitor != null) {
            performanceMonitor.close();
        }

        getLogger().info("AdminAreaProtectionPlugin disabled.");
    }

    // Remove duplicate area management methods and delegate to areaManager
    public void addArea(Area area) {
        areaManager.addArea(area);
    }

    public void removeArea(Area area) {
        areaManager.removeArea(area);
    }

    public Area getArea(String name) {
        return areaManager.getArea(name);
    }

    public List<Area> getAreas() {
        return areaManager.getAllAreas();
    }

    public boolean hasArea(String name) {
        return areaManager.hasArea(name);
    }

    public Area getHighestPriorityArea(String world, double x, double y, double z) {
        return areaManager.getHighestPriorityArea(world, x, y, z);
    }

    public List<Area> getApplicableAreas(String world, double x, double y, double z) {
        return areaManager.getAreasAtLocation(world, x, y, z);
    }

    public boolean isBypassing(String playerName) {
        return bypassingPlayers.getOrDefault(playerName, false);
    }

    public void toggleBypass(String playerName) {
        bypassingPlayers.put(playerName, !isBypassing(playerName));
    }

    public boolean isGlobalAreaProtection() {
        return globalAreaProtection;
    }

    public void setGlobalAreaProtection(boolean global) {
        this.globalAreaProtection = global;
    }

    public void toggleGlobalAreaProtection() {
        this.globalAreaProtection = !this.globalAreaProtection;
    }

    // New getter for LuckPerms API.
    public LuckPerms getLuckPermsApi() {
        return luckPermsApi;
    }

    public DatabaseManager getDatabaseManager() {
        return dbManager;
    }

    // Getter and setter for player positions
    public HashMap<String, Position[]> getPlayerPositions() {
        return playerPositions;
    }

    // Getter and setter for form ID map
    public HashMap<String, String> getFormIdMap() {
        return formIdMap;
    }

    // Getters for the messages and toggle
    public boolean isEnableMessages() {
        return enableMessages;
    }

    public String getMsgBlockBreak() {
        return msgBlockBreak;
    }

    public String getMsgBlockPlace() {
        return msgBlockPlace;
    }

    public String getMsgPVP() {
        return msgPVP;
    }

    public boolean hasGroupPermission(Player player, Area area, String permission) {
        if (luckPermsApi == null) return true;
        
        // Get user's groups
        var user = luckPermsApi.getPlayerAdapter(Player.class).getUser(player);
        var groups = user.getInheritedGroups(user.getQueryOptions());
        
        // Check each group's permissions for the area
        for (Group group : groups) {
            if (!area.getGroupPermission(group.getName(), permission)) {
                return false;
            }
        }
        
        return true;
    }

    public Set<String> getGroupNames() {
        if (luckPermsApi == null) return new HashSet<>();
        return luckPermsApi.getGroupManager().getLoadedGroups()
            .stream()
            .map(Group::getName)
            .collect(Collectors.toSet());
    }

    public AreaManager getAreaManager() {
        return areaManager;
    }

    // Add performance monitoring method
    public PerformanceMonitor getPerformanceMonitor() {
        return performanceMonitor;
    }

    public void saveArea(Area area) {
        if (area == null) return;
        // Save area to configuration
        this.getConfig().set("areas." + area.getName(), area.serialize());
        this.saveConfig();
    }
    
    public void updateArea(Area area) {
        // Update the area in your storage system
        getAreaManager().updateArea(area);
        // Trigger any necessary updates or events
    }

    // Add getter for GUI manager
    public GuiManager getGuiManager() {
        return guiManager;
    }

    public WandListener getWandListener() {
        return wandListener;
    }

    public void setDebugMode(boolean enabled) {
        this.debugMode = enabled;
        getLogger().info("Debug mode " + (enabled ? "enabled" : "disabled"));
    }

    public boolean isDebugMode() {
        return debugMode;
    }
}
