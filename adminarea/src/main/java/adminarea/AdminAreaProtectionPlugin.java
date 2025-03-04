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
import adminarea.listeners.FormResponseListener;
import adminarea.listeners.WandListener;
import adminarea.listeners.FormCleanupListener;
import adminarea.managers.AreaManager;
import adminarea.managers.ConfigManager;
import adminarea.managers.DatabaseManager;
import adminarea.managers.GuiManager;
import adminarea.managers.LanguageManager;
import adminarea.managers.ListenerManager;
import adminarea.permissions.PermissionOverrideManager;
import adminarea.area.Area;
import adminarea.area.AreaCommand;
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
    private PermissionOverrideManager permissionOverrideManager;
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
    private ListenerManager listenerManager; // Add listener manager

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
            
            // Initialize debug mode once at startup
            this.debugMode = getConfig().getBoolean("debug", false);
            if (this.debugMode) {
                getLogger().info("Debug mode enabled from config");
                debug("Plugin version: " + getDescription().getVersion());
                debug("Server version: " + getServer().getVersion());
            }
            
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

                // Initialize MonsterHandler early to detect MobPlugin availability
                adminarea.entity.MonsterHandler.initialize(this);
                getLogger().info("Monster handler initialized");
    
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

                Timer.Sample areaInitTimer = performanceMonitor.startTimer();
                areaManager = new AreaManager(this);
                try {
                    List<Area> loadedAreas = dbManager.loadAreas(); // This method should throw DatabaseException
                    for (Area area : loadedAreas) {
                        areaManager.addArea(area);
                    }
                    
                    // Note: we'll normalize toggle states later, after all components are initialized
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

                // Initialize LuckPerms integration before PermissionOverrideManager
                if (getServer().getPluginManager().getPlugin("LuckPerms") != null) {
                    try {
                        luckPermsApi = LuckPermsProvider.get();
                        luckPermsCache = new LuckPermsCache(this, luckPermsApi);
                        
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

                // Initialize PermissionOverrideManager
                permissionOverrideManager = new PermissionOverrideManager(this);
                
                // Now that PermissionOverrideManager is initialized, load permissions for all areas
                try {
                    getLogger().info("Loading permissions for all areas...");
                    List<Area> allAreas = areaManager.getAllAreas();
                    for (Area area : allAreas) {
                        try {
                            permissionOverrideManager.synchronizeOnLoad(area);
                            if (isDebugMode()) {
                                debug("Loaded permissions for area: " + area.getName());
                            }
                        } catch (Exception e) {
                            getLogger().error("Failed to load permissions for area: " + area.getName(), e);
                        }
                    }
                    getLogger().info("Permissions loaded for " + areaManager.getAllAreas().size() + " areas");
                } catch (Exception e) {
                    getLogger().error("Failed to load permissions for areas", e);
                }
    
                // Register events and commands
                getServer().getPluginManager().registerEvents(this, this);
                getServer().getPluginManager().registerEvents(new FormResponseListener(this), this);
                getServer().getPluginManager().registerEvents(wandListener, this); // Register WandListener
                getLogger().info("FormResponseListener registered.");
                
                // Initialize and register protection-related listeners
                Timer.Sample listenerInitTimer = performanceMonitor.startTimer();
                try {
                    // Initialize listener manager which handles all protection listeners
                    listenerManager = new ListenerManager(this);
                    
                    // Register form cleanup listener separately (not protection-related)
                    getServer().getPluginManager().registerEvents(new FormCleanupListener(this), this);
                    
                    performanceMonitor.stopTimer(listenerInitTimer, "listener_manager_init");
                    getLogger().info("Listener manager initialized and all listeners registered successfully");
                } catch (Exception e) {
                    performanceMonitor.stopTimer(listenerInitTimer, "listener_manager_init_failed");
                    getLogger().error("Failed to initialize listener manager", e);
                }
                
                // Now that all managers are initialized, normalize toggle states
                if (configManager.getBoolean("normalize_toggle_states", true)) {
                    getLogger().info("Normalizing toggle states for all areas...");
                    areaManager.normalizeAllAreaToggleStates();
                    getLogger().info("Toggle states normalized successfully.");
                }

                // Initialize AreaCommand
                areaCommand = new AreaCommand(this);
    
                // Register the command
                getServer().getCommandMap().register("area", areaCommand);
                getLogger().info("Area command registered.");
    
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
            // Update debug mode when config is reloaded 
            boolean wasDebug = this.debugMode;
            this.debugMode = getConfig().getBoolean("debug", false);
            
            if (wasDebug != this.debugMode) {
                getLogger().info("Debug mode " + (this.debugMode ? "enabled" : "disabled"));
            }
            
            enableMessages = configManager.isEnabled("enableMessages");
            
            // Refresh all protection caches
            if (listenerManager != null) {
                listenerManager.reload();
                getLogger().info("Protection listener caches cleared");
                
                // Specifically reload potion effects
                if (listenerManager.getPlayerEffectListener() != null) {
                    listenerManager.getPlayerEffectListener().reloadEffects();
                    getLogger().info("Potion effects reloaded for all players");
                }
            }
            
            // Reload language manager
            if (languageManager != null) {
                try {
                    languageManager.reload();
                } catch (Exception e) {
                    getLogger().error("Error reloading language manager", e);
                }
            }
            
            getLogger().info("All caches have been cleared and listeners reloaded");
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
                    
                    // Reload configs first
                    configManager.reload();
                    
                    // Update config values and clear caches
                    reloadConfigValues();
                    
                    // Force invalidate all protection caches
                    if (listenerManager != null && listenerManager.getProtectionListener() != null) {
                        listenerManager.getProtectionListener().cleanup();
                    }
                    
                    // Clear form tracking data
                    formIdMap.clear();
                    
                    sender.sendMessage(languageManager.get("success.plugin.reload"));
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
            getLogger().info("Disabling AdminAreaProtectionPlugin...");
            Timer.Sample shutdownTimer = performanceMonitor.startTimer();
            
            try {
                // Cancel all scheduled tasks first to prevent conflicts
                getServer().getScheduler().cancelTask(this);
                
                // Clean up cache data
                if (isDebugMode()) {
                    getLogger().info("Cleaning up plugin caches...");
                }
                
                // Close GUI related resources
                if (guiManager != null) {
                    // Call cleanup if you've implemented this method, otherwise simply clear references
                    // guiManager.cleanup();
                }
                
                // Clean up protection listeners through the manager
                if (listenerManager != null) {
                    listenerManager.cleanup();
                    getLogger().info("Listener manager cleanup completed");
                }
                
                // Clear player data caches
                playerPositions.clear();
                bypassingPlayers.clear();
                formIdMap.clear();
                
                // Clean up LuckPerms integration
                if (luckPermsCache != null) {
                    // If cleanup method exists, call it, otherwise simply null out the reference
                    // luckPermsCache.cleanup();
                }
            
                // Save areas before shutting down DB
                if (areaManager != null) {
                    Timer.Sample saveTimer = performanceMonitor.startTimer();
                    try {
                        // Get areas and save them with batching for better performance
                        List<Area> allAreas = areaManager.getAllAreas();
                        getLogger().info("Saving " + allAreas.size() + " areas before shutdown...");
                        
                        // Save in batches to avoid memory pressure
                        int batchSize = 20;
                        for (int i = 0; i < allAreas.size(); i += batchSize) {
                            int end = Math.min(i + batchSize, allAreas.size());
                            for (int j = i; j < end; j++) {
                                try {
                                    dbManager.saveArea(allAreas.get(j));
                                } catch (Exception e) {
                                    getLogger().error("Failed to save area: " + allAreas.get(j).getName(), e);
                                }
                            }
                        }
                        performanceMonitor.stopTimer(saveTimer, "final_area_save");
                        getLogger().info("Area data saved successfully");
                    } catch (Exception e) {
                        performanceMonitor.stopTimer(saveTimer, "final_area_save_failed");
                        getLogger().error("Error saving areas during shutdown", e);
                    }
                }
                
                // Close database connection last
                if (dbManager != null) {
                    Timer.Sample dbCloseTimer = performanceMonitor.startTimer();
                    try {
                        dbManager.close();
                        performanceMonitor.stopTimer(dbCloseTimer, "database_close");
                        getLogger().info("Database connection closed");
                    } catch (Exception e) {
                        performanceMonitor.stopTimer(dbCloseTimer, "database_close_failed");
                        getLogger().error("Error closing database", e);
                    }
                }
                
                performanceMonitor.stopTimer(shutdownTimer, "plugin_shutdown");
            } catch (Exception e) {
                getLogger().error("Error during plugin shutdown", e);
            } finally {
                // Final cleanup of the performance monitor
                if (performanceMonitor != null) {
                    performanceMonitor.close();
                }
                
                getLogger().info("AdminAreaProtectionPlugin disabled.");
            }
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
            if (area == null) {
                getLogger().error("Cannot save null area");
                return;
            }
            
            if (isDebugMode()) {
                debug("Saving area: " + area.getName());
                debug("  World: " + area.getWorld());
                debug("  Priority: " + area.getPriority());
                debug("  Toggle states count: " + area.getToggleStates().size());
            }

            try {
                // First save the area without applying toggle states
                dbManager.saveArea(area);
                
                // Then add to area manager, which will apply default toggle states if needed
                areaManager.addArea(area);
                
                // Ensure toggle states are saved in a single batch operation
                area.saveToggleStates();
                
                if (isDebugMode()) {
                    debug("  Area saved successfully");
                }
            } catch (Exception e) {
                getLogger().error("Failed to save area: " + area.getName(), e);
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

        /**
         * Set debug mode and update config
         */
        public void setDebugMode(boolean enabled) {
            if (this.debugMode == enabled) return;
            
            this.debugMode = enabled;
            getConfig().set("debug", enabled);
            getConfig().save();
            
            if (enabled) {
                getLogger().info("Debug mode enabled");
                debug("Plugin version: " + getDescription().getVersion());
                debug("Server version: " + getServer().getVersion());
            } else {
                getLogger().info("Debug mode disabled");
            }
        }

        /**
         * Check if debug mode is enabled
         */
        public boolean isDebugMode() {
            return debugMode;
        }

        /**
         * Send a debug message if debug mode is enabled
         */
        public void debug(String message) {
            if (debugMode) {
                getLogger().debug("[Debug] " + message);
            }
        }

        public LanguageManager getLanguageManager() {
            return languageManager;
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

        /**
         * Gets the listener manager instance
         * @return The ListenerManager instance
         */
        public ListenerManager getListenerManager() {
            return listenerManager;
        }

        public PermissionOverrideManager getPermissionOverrideManager() {
            // If permissionOverrideManager is null and we're in the middle of initialization,
            // create it on-demand to avoid NPEs
            if (permissionOverrideManager == null) {
                getLogger().warning("PermissionOverrideManager was accessed before initialization. Creating it now.");
                try {
                    permissionOverrideManager = new PermissionOverrideManager(this);
                } catch (Exception e) {
                    getLogger().error("Failed to initialize PermissionOverrideManager on-demand", e);
                }
            }
            return permissionOverrideManager;
        }
        
        /**
         * Alias for getPermissionOverrideManager() for backward compatibility.
         */
        public PermissionOverrideManager getOverrideManager() {
            return getPermissionOverrideManager(); // Use the main method to ensure consistency
        }

}