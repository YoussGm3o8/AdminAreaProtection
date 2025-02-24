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
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import adminarea.listeners.EnderPearlListener;
import adminarea.listeners.EntityListener;
import adminarea.listeners.EnvironmentListener;
import adminarea.listeners.FormResponseListener;
import adminarea.listeners.ItemListener;
import adminarea.listeners.ProtectionListener;
import adminarea.listeners.VehicleListener;
import adminarea.listeners.WandListener;
import adminarea.listeners.PlayerEffectListener;
import adminarea.listeners.ExperienceListener;
import adminarea.listeners.FormCleanupListener;
import adminarea.managers.AreaManager;
import adminarea.managers.DatabaseManager;
import adminarea.managers.GuiManager;
import adminarea.managers.LanguageManager;
import adminarea.permissions.OverrideManager;  // Updated import path
import adminarea.area.Area;
import adminarea.area.AreaCommand;
import adminarea.config.ConfigManager;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.util.PerformanceMonitor;
import adminarea.util.ValidationUtils;
import io.micrometer.core.instrument.Timer;
import adminarea.exception.DatabaseException;
import adminarea.form.FormRegistry;
import adminarea.permissions.LuckPermsCache;
import java.util.concurrent.ConcurrentHashMap;

public class AdminAreaProtectionPlugin extends PluginBase implements Listener {

    private static AdminAreaProtectionPlugin instance;
    // Add this field with other managers
    private OverrideManager overrideManager;
    private DatabaseManager dbManager;
    private AreaCommand areaCommand;
    private final HashMap<String, Position[]> playerPositions = new HashMap<>(); // Store player positions
    private final ConcurrentHashMap<String, FormTrackingData> formIdMap = new ConcurrentHashMap<>(); // Store form IDs
    private final HashMap<String, Boolean> bypassingPlayers = new HashMap<>(); // Store players bypassing protection
    private boolean globalAreaProtection = false; // new flag
    private LuckPerms luckPermsApi; // New field for LuckPerms API
    private ConfigManager configManager;
    private AreaManager areaManager;
    private PerformanceMonitor performanceMonitor; // Add performance monitor
    private GuiManager guiManager;

    private WandListener wandListener; // Add WandListener

    private boolean enableMessages;
    private LanguageManager languageManager;

    private boolean debugMode = false;  // Change initial value to false

    private LuckPermsCache luckPermsCache;

    private ValidationUtils validationUtils;

    private FormRegistry formRegistry;
    
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
            performanceMonitor = new PerformanceMonitor(this);
            Timer.Sample startupTimer = performanceMonitor.startTimer();
            
            try {
                // Initialize config manager first
                configManager = new ConfigManager(this);
                configManager.load();
    
                // Set debug mode right after config load
                debugMode = configManager.getBoolean("debug", false);
                if (debugMode) {
                    getLogger().info("Debug mode enabled from config");
                }

                // Configure HikariCP logging levels
                ch.qos.logback.classic.Logger hikariLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.zaxxer.hikari");
                hikariLogger.setLevel(ch.qos.logback.classic.Level.ERROR);
                
                ch.qos.logback.classic.Logger poolBaseLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.zaxxer.hikari.pool.PoolBase");
                poolBaseLogger.setLevel(ch.qos.logback.classic.Level.ERROR);
                
                ch.qos.logback.classic.Logger hikariPoolLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.zaxxer.hikari.pool.HikariPool");
                hikariPoolLogger.setLevel(ch.qos.logback.classic.Level.ERROR);
                
                ch.qos.logback.classic.Logger hikariDataSourceLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.zaxxer.hikari.HikariDataSource");
                hikariDataSourceLogger.setLevel(ch.qos.logback.classic.Level.ERROR);
                
                ch.qos.logback.classic.Logger hikariConfigLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.zaxxer.hikari.HikariConfig");
                hikariConfigLogger.setLevel(ch.qos.logback.classic.Level.ERROR);

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
    
                // Initialize language manager earlier in startup sequence
                languageManager = new LanguageManager(this);
                try {
                    languageManager.reload();
                    getLogger().info("LanguageManager initialized successfully.");
                } catch (Exception e) {
                    getLogger().error("Failed to initialize LanguageManager", e);
                    getServer().getPluginManager().disablePlugin(this);
                    return;
                }
    
                // Initialize managers with performance monitoring
                Timer.Sample dbInitTimer = performanceMonitor.startTimer();
                dbManager = new DatabaseManager(this);
                try {
                    dbManager.init(); // This method should throw DatabaseException
                    performanceMonitor.stopTimer(dbInitTimer, "database_init");
                    getLogger().info("Database initialized successfully");
                } catch (DatabaseException e) {
                    performanceMonitor.stopTimer(dbInitTimer, "database_init_failed");
                    getLogger().error("Failed to initialize database", e);
                    getServer().getPluginManager().disablePlugin(this);
                    return;
                }
    
                // Initialize OverrideManager
                Timer.Sample overrideInitTimer = performanceMonitor.startTimer();
                try {
                    overrideManager = new OverrideManager(this);
                    performanceMonitor.stopTimer(overrideInitTimer, "override_manager_init");
                    getLogger().info("Override manager initialized successfully");
                } catch (Exception e) {
                    performanceMonitor.stopTimer(overrideInitTimer, "override_manager_init_failed");
                    getLogger().error("Failed to initialize override manager", e);
                    getServer().getPluginManager().disablePlugin(this);
                    return;
                }
    
                Timer.Sample areaInitTimer = performanceMonitor.startTimer();
                areaManager = new AreaManager(this);
                try {
                    List<Area> loadedAreas = dbManager.loadAreas(); // This method should throw DatabaseException
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
    
                // Initialize FormRegistry before GUI manager
                formRegistry = new FormRegistry(this);
                formRegistry.initialize();
                
                // Verify handlers were registered
                if (formRegistry.getHandler(FormIds.MAIN_MENU) == null) {
                    getLogger().error("MainMenuHandler was not registered!");
                }
                if (formRegistry.getHandler(FormIds.CREATE_AREA) == null) {
                    getLogger().error("CreateAreaHandler was not registered!");
                }
    
                // Initialize GUI managers after form registry
                guiManager = new GuiManager(this);
    
                wandListener = new WandListener(this); // Initialize WandListener
    
                validationUtils = new ValidationUtils();
    
                // Register events and commands
    
                getServer().getPluginManager().registerEvents(this, this);
                getServer().getPluginManager().registerEvents(new FormResponseListener(this), this);
                getServer().getPluginManager().registerEvents(wandListener, this); // Register WandListener
                getLogger().info("FormResponseListener registered."); // Added log
    
                ProtectionListener protectionListener = new ProtectionListener(this);
    
                getServer().getPluginManager().registerEvents(protectionListener, this);
                getServer().getPluginManager().registerEvents(new EnderPearlListener(this, protectionListener), this); // Add this line
                getServer().getPluginManager().registerEvents(new EnvironmentListener(this, protectionListener), this);
                getServer().getPluginManager().registerEvents(new EntityListener(this, protectionListener), this);
                getServer().getPluginManager().registerEvents(new ItemListener(this, protectionListener), this);
                getServer().getPluginManager().registerEvents(new VehicleListener(this, protectionListener), this);
                getServer().getPluginManager().registerEvents(new PlayerEffectListener(this, protectionListener), this);
                getServer().getPluginManager().registerEvents(new ExperienceListener(this, protectionListener), this);
    
                // Register form cleanup listener
                this.getServer().getPluginManager().registerEvents(new FormCleanupListener(this), this);

                // Initialize AreaCommand
    
                areaCommand = new AreaCommand(this);
    
                // Register the command
                getServer().getCommandMap().register("area", areaCommand);
                getLogger().info("Area command registered.");
    
                // Initialize LuckPerms integration
                if (getServer().getPluginManager().getPlugin("LuckPerms") != null) {
                    try {
                        luckPermsApi = LuckPermsProvider.get();
                        luckPermsCache = new LuckPermsCache(luckPermsApi);
                        
                        // Register listener for LuckPerms changes
                        luckPermsApi.getEventBus().subscribe(this, 
                            net.luckperms.api.event.group.GroupDataRecalculateEvent.class, 
                            event -> luckPermsCache.refreshCache());
                        luckPermsApi.getEventBus().subscribe(this,
                            net.luckperms.api.event.group.GroupLoadEvent.class,
                            event -> luckPermsCache.refreshCache());
                            
                        getLogger().info("LuckPerms integration enabled successfully");
                    } catch (Exception e) {
                        getLogger().error("Failed to initialize LuckPerms integration", e);
                    }
                } else {
                    getLogger().info("LuckPerms not found. Proceeding without it.");
                }
    
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
         * Reloads configuration values from disk and updates cached settings.
         */
        public void reloadConfigValues() {
            // Make debug mode update first
            debugMode = configManager.getBoolean("debug", false);
            if (debugMode) {
                getLogger().info("Debug mode reloaded: enabled");
            }
            enableMessages = configManager.isEnabled("enableMessages");
            // ...existing code...
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
                Player player = (Player) sender;
                if (args.length == 0) {
                    // Only open main menu if there's no current form tracking data
                    if (!formIdMap.containsKey(player.getName())) {
                        formIdMap.put(player.getName(), 
                            new FormTrackingData(FormIds.MAIN_MENU, System.currentTimeMillis()));
                        guiManager.openMainMenu(player);
                    }
                    return true;
                }
                return areaCommand.execute(sender, label, args);
            }
            return false;
        }
    
        @Override
        public void onDisable() {
            // Add override manager cleanup before database cleanup
            if (overrideManager != null) {
                Timer.Sample overrideCloseTimer = performanceMonitor.startTimer();
                try {
                    overrideManager.close();
                    performanceMonitor.stopTimer(overrideCloseTimer, "override_manager_close");
                } catch (Exception e) {
                    performanceMonitor.stopTimer(overrideCloseTimer, "override_manager_close_failed");
                    getLogger().error("Error closing override manager", e);
                }
            }
    
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
    
        public LuckPermsCache getLuckPermsCache() {
            return luckPermsCache;
        }
    
        public DatabaseManager getDatabaseManager() {
            return dbManager;
        }
    
        // Getter and setter for player positions
        public HashMap<String, Position[]> getPlayerPositions() {
            return playerPositions;
        }
    
        // Getter and setter for form ID map
        public ConcurrentHashMap<String, FormTrackingData> getFormIdMap() {
            return formIdMap;
        }
    
        // Getters for the messages and toggle
        public boolean isEnableMessages() {
            return enableMessages;
        }
    
        public boolean hasGroupPermission(Player player, Area area, String permission) {
            if (luckPermsApi == null) return true;
            
            var user = luckPermsApi.getPlayerAdapter(Player.class).getUser(player);
            var groups = user.getInheritedGroups(user.getQueryOptions());
            
            // Sort groups by weight
            List<Group> sortedGroups = new ArrayList<>(groups);
            sortedGroups.sort((g1, g2) -> Integer.compare(
                luckPermsCache.getGroupWeight(g2.getName()),
                luckPermsCache.getGroupWeight(g1.getName())
            ));
            
            // Check permissions starting from highest weight group
            for (Group group : sortedGroups) {
                if (!area.getEffectivePermission(group.getName(), permission)) {
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
            
            try {
                if (isDebugMode()) {
                    debug("Saving area: " + area.getName());
                    debug("Current areas in memory: " + areaManager.getAllAreas().size());
                }

                // Verify area doesn't already exist before saving
                if (hasArea(area.getName())) {
                    if (isDebugMode()) {
                        debug("Area " + area.getName() + " already exists, updating instead of creating new");
                    }
                    updateArea(area);
                    return;
                }

                // Save to database first
                dbManager.saveArea(area);
                
                // Then add to area manager
                areaManager.addArea(area);

                if (isDebugMode()) {
                    debug("Area saved successfully: " + area.getName());
                    debug("Total areas after save: " + areaManager.getAllAreas().size());
                }

            } catch (DatabaseException e) {
                getLogger().error("Failed to save area: " + area.getName(), e);
                throw new RuntimeException("Could not save area", e);
            }
        }
    
        public void updateArea(Area area) {
            if (area == null) return;
            
            Timer.Sample sample = performanceMonitor.startTimer();
            try {
                // Update in area manager which handles cache invalidation
                areaManager.updateArea(area);
                
                if (isDebugMode()) {
                    debug("Area updated: " + area.getName());
                }
                
            } finally {
                performanceMonitor.stopTimer(sample, "area_update");
            }
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

        public LanguageManager getLanguageManager() {
            return languageManager;
        }

        public void debug(String message) {
            if (debugMode) {
                getLogger().info("[Debug] " + message); // Change to info instead of debug
            }
        }

        public boolean isLuckPermsEnabled() {
            return luckPermsApi != null && luckPermsCache != null;
        }

        public List<String> getGroupsByTrack(String trackName) {
            if (!isLuckPermsEnabled()) return Collections.emptyList();
            return new ArrayList<>(luckPermsCache.getTrackGroups(trackName));
        }

        public List<String> getGroupInheritance(String groupName) {
            if (!isLuckPermsEnabled()) return Collections.emptyList();
            return luckPermsCache.getInheritanceChain(groupName);
        }

        /**
         * Gets the plugin's override manager.
         * @return The OverrideManager instance
         */
        public OverrideManager getOverrideManager() {
            return overrideManager;
        }

        /**
         * Gets the validation utilities instance.
         * @return The ValidationUtils instance
         */
        public ValidationUtils getValidationUtils() {
            return validationUtils;
        }

        public boolean hasValidSelection(Player player) {
            Position[] positions = playerPositions.get(player.getName());
            return positions != null && positions[0] != null && positions[1] != null;
        }

        public Map<String, Object> getPlayerSelection(Player player) {
            Position[] positions = playerPositions.get(player.getName());
            if (!hasValidSelection(player)) {
                return null;
            }

            Map<String, Object> selection = new HashMap<>();
            selection.put("x1", positions[0].getFloorX());
            selection.put("x2", positions[1].getFloorX());
            selection.put("y1", positions[0].getFloorY());
            selection.put("y2", positions[1].getFloorY());
            selection.put("z1", positions[0].getFloorZ());
            selection.put("z2", positions[1].getFloorZ());
            return selection;
        }

        /**
         * Gets the form registry instance
         * @return The FormRegistry instance
         */
        public FormRegistry getFormRegistry() {
            return formRegistry;
        }

        public boolean isPlayerInTrack(Player player, String trackName) {
            if (!isLuckPermsEnabled() || player == null || trackName == null) {
                return false;
            }
            
            try {
                var user = luckPermsApi.getUserManager().getUser(player.getUniqueId());
                if (user == null) return false;
                
                var track = luckPermsApi.getTrackManager().getTrack(trackName);
                if (track == null) return false;
                
                // Check if player has any group in this track
                String currentGroup = user.getPrimaryGroup();
                return track.getGroups().contains(currentGroup);
            } catch (Exception e) {
                getLogger().error("Error checking track membership", e);
                return false;
            }
        }

        public String getPrimaryGroup(Player player) {
            if (!isLuckPermsEnabled() || player == null) {
                return null;
            }
            
            try {
                var user = luckPermsApi.getUserManager().getUser(player.getUniqueId());
                return user != null ? user.getPrimaryGroup() : null;
            } catch (Exception e) {
                getLogger().error("Error getting primary group", e);
                return null;
            }
        }

}