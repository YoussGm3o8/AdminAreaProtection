package adminarea;

/**
 * Main plugin class for AdminAreaProtection.
 * Handles initialization, configuration management, and core functionality.
 * 
 * @version 1.2.0
 * @author YoussGm3o8
 */
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.event.Listener;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.Player;
import cn.nukkit.level.Position;
import cn.nukkit.math.Vector3;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.UUID;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.Collection;
import java.io.File;

import adminarea.listeners.ContainerListener;
import adminarea.listeners.FormCleanupListener;
import adminarea.listeners.FormResponseListener;
import adminarea.listeners.WandListener;
import adminarea.managers.AreaManager;
import adminarea.managers.ConfigManager;
import adminarea.managers.DatabaseManager;
import adminarea.managers.GuiManager;
import adminarea.managers.LanguageManager;
import adminarea.managers.ListenerManager;
import adminarea.managers.FormModuleManager;
import adminarea.permissions.PermissionOverrideManager;
import adminarea.area.Area;
import adminarea.area.AreaCommand;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.util.LogFilter;
import adminarea.util.PerformanceMonitor;
import adminarea.util.ValidationUtils;
import io.micrometer.core.instrument.Timer;
import adminarea.permissions.LuckPermsCache;
import cn.nukkit.plugin.PluginManager;
import adminarea.listeners.ProtectionListener;
import adminarea.listeners.PlayerEffectListener;
import adminarea.listeners.EnvironmentListener;
import adminarea.util.YamlConfig;


public class AdminAreaProtectionPlugin extends PluginBase implements Listener {

    private static AdminAreaProtectionPlugin instance;
    private static final String CLEAN_SHUTDOWN_MARKER = "clean_shutdown.marker";
    private PermissionOverrideManager permissionOverrideManager;
    private DatabaseManager dbManager;
    private AreaCommand areaCommand;
    private final HashMap<String, Position[]> playerPositions = new HashMap<>(); // Store player positions
    private final ConcurrentHashMap<String, FormTrackingData> formIdMap = new ConcurrentHashMap<>(); // Store form IDs
    private final HashMap<String, Boolean> bypassingPlayers = new HashMap<>(); // Store players bypassing protection
    private boolean globalAreaProtection = false; // new flag
    private Object luckPermsApi; // Changed type to Object to avoid direct reference to LuckPerms
    private ConfigManager configManager;
    private AreaManager areaManager;
    private PerformanceMonitor performanceMonitor; // Add performance monitor
    private GuiManager guiManager;
    private ListenerManager listenerManager; // Add listener manager
    private FormModuleManager formModuleManager; // Add FormModuleManager

    private WandListener wandListener; // Add WandListener

    private boolean enableMessages;
    private LanguageManager languageManager;

    private boolean debugMode = false;  // Change initial value to false

    private LuckPermsCache luckPermsCache;

    private ValidationUtils validationUtils;

    private RecentSaveTracker recentSaveTracker;
    
    // Store LuckPerms class references to avoid direct dependencies
    private Class<?> luckPermsClass = null;
    private Class<?> luckPermsProviderClass = null;
    private Class<?> groupClass = null;
    
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
            // Register custom YAML handler before any configs are loaded
            registerCustomYamlHandler();

            instance = this;
            performanceMonitor = new PerformanceMonitor(this);
            Timer.Sample startupTimer = performanceMonitor.startTimer();
            LogFilter.registerFilter();
            
            long startTime = System.currentTimeMillis();

            // Initialize RecentSaveTracker first to avoid NPEs
            recentSaveTracker = new RecentSaveTracker();
            
            // Ensure data folder exists
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }
            
            // Save default config if it doesn't exist
            saveDefaultConfig();
            
            // Initialize debug mode once at startup
            this.debugMode = getConfig().getBoolean("debug", false);
            if (this.debugMode) {
                getLogger().info("Debug mode enabled from config");
                debug("Plugin version: " + getDescription().getVersion());
                debug("Server version: " + getServer().getVersion());
            }
            
            try {
                // Check for clean shutdown marker
                Path cleanShutdownMarker = getDataFolder().toPath().resolve(CLEAN_SHUTDOWN_MARKER);
                boolean cleanShutdownDetected = Files.exists(cleanShutdownMarker);
                
                // Delete the marker file at startup
                if (cleanShutdownDetected) {
                    debug("Detected clean shutdown from previous session, removing marker");
                    Files.delete(cleanShutdownMarker);
                } else {
                    debug("No clean shutdown marker found, potential unclean shutdown detected");
                    // Perform additional database verification if needed
                }
                
                // Initialize config manager first
                configManager = new ConfigManager(this);
                configManager.load();
    
                // Set debug mode right after config load
                debugMode = configManager.getBoolean("debug", false);
                if (debugMode) {
                    getLogger().info("Debug mode enabled from config");
                }

                // Configure logging levels for HikariCP - using SLF4J directly instead of casting
                try {
                    // Get SLF4J LoggerFactory - avoid direct casts to specific implementation
                    org.slf4j.Logger hikariLogger = org.slf4j.LoggerFactory.getLogger("com.zaxxer.hikari");
                    
                    // Set Hikari's logging level to ERROR to minimize verbose output
                    if (hikariLogger instanceof ch.qos.logback.classic.Logger) {
                        ((ch.qos.logback.classic.Logger) hikariLogger).setLevel(ch.qos.logback.classic.Level.ERROR);
                    }
                    
                    // Also try to set the pool logger specifically
                    org.slf4j.Logger poolLogger = org.slf4j.LoggerFactory.getLogger("com.zaxxer.hikari.pool.HikariPool");
                    if (poolLogger instanceof ch.qos.logback.classic.Logger) {
                        ((ch.qos.logback.classic.Logger) poolLogger).setLevel(ch.qos.logback.classic.Level.ERROR);
                    }
                    
                    // Log that we're configuring HikariCP logging
                    if (isDebugMode()) {
                        debug("Configured HikariCP logging to minimize verbose output");
                    }
                } catch (Exception e) {
                    // Just log and continue if this fails - it's not critical
                    if (isDebugMode()) {
                        debug("Failed to configure HikariCP logging: " + e.getMessage());
                    }
                }
                
                // Ensure data folder exists and copy config.yml if missing
                if (!getDataFolder().exists()) {
                    getDataFolder().mkdirs();
                }
                saveResource("config.yml", false); // copies bundled config if it doesn't exist
                reloadConfig();       // reload config after saving
    
                // Log plugin enabling start
                // getLogger().info("Enabling AdminAreaProtectionPlugin...");
    
                // Comment out less important logs
                // getLogger().info("Configuration loaded.");

                // Replace direct config access with config manager
                reloadConfigValues();
                
                // Register container stats listener
                getServer().getPluginManager().registerEvents(new ContainerListener(this), this);
                
                // Initialize MonsterHandler early to detect NukkitMOT mob availability
                adminarea.entity.MonsterHandler.initialize(this);
                // getLogger().info("Monster handler initialized");
    
                // Initialize language manager earlier in startup sequence
                languageManager = new LanguageManager(this);
                try {
                    languageManager.reload();
                    // getLogger().info("LanguageManager initialized successfully.");
                } catch (Exception e) {
                    getLogger().error("Failed to initialize LanguageManager", e);
                    getServer().getPluginManager().disablePlugin(this);
                    return;
                }
    
                // Initialize LuckPerms integration first using reflection
                boolean luckPermsAvailable = getServer().getPluginManager().getPlugin("LuckPerms") != null;
                if (luckPermsAvailable) {
                    try {
                        // Try to load the required LuckPerms classes using reflection
                        luckPermsClass = Class.forName("net.luckperms.api.LuckPerms");
                        luckPermsProviderClass = Class.forName("net.luckperms.api.LuckPermsProvider");
                        groupClass = Class.forName("net.luckperms.api.model.group.Group");
                        
                        // If all classes loaded successfully, initialize the API
                        if (luckPermsClass != null && luckPermsProviderClass != null && groupClass != null) {
                            // Use reflection to get the LuckPerms instance
                            luckPermsApi = luckPermsProviderClass.getMethod("get").invoke(null);
                            
                            if (luckPermsApi != null) {
                                // Create LuckPermsCache with the LuckPerms API
                                // Cast is needed because we're using reflection
                                luckPermsCache = new LuckPermsCache(this, (net.luckperms.api.LuckPerms) luckPermsApi);
                                
                                // Register listeners for LuckPerms changes using reflection
                                // We'll use a simpler approach to avoid deep reflection
                                try {
                                    // Get event bus from the API
                                    Object eventBus = luckPermsClass.getMethod("getEventBus").invoke(luckPermsApi);
                                    
                                    // Register for basic events 
                                    // getLogger().info("LuckPerms integration enabled, but event listeners are simplified");
                                    
                                    // getLogger().info("LuckPerms integration enabled successfully");
                                } catch (Exception e) {
                                    getLogger().warning("Could not register LuckPerms event listeners: " + e.getMessage());
                                }
                            }
                        }
                    } catch (Exception e) {
                        getLogger().error("Failed to initialize LuckPerms integration", e);
                        // getLogger().info("Plugin will continue without LuckPerms features");
                    }
                } else {
                    // getLogger().info("LuckPerms not found. Proceeding without permissions integration.");
                }

                // Initialize PermissionOverrideManager before loading areas
                try {
                    permissionOverrideManager = new PermissionOverrideManager(this);
                    // getLogger().info("PermissionOverrideManager initialized successfully");
                    
                    // Verify and repair track permissions database
                    verifyTrackPermissionsDatabase();
                    
                    // Verify and repair group permissions database 
                    verifyGroupPermissionsDatabase();
                } catch (Exception e) {
                    getLogger().error("Failed to initialize PermissionOverrideManager", e);
                    getServer().getPluginManager().disablePlugin(this);
                    return;
                }
    
                // Initialize database manager and load areas
                try {
                    dbManager = new DatabaseManager(this);
                    
                    // Add recovery check for crashed sessions
                    // getLogger().info("Checking for database recovery needs...");
                    Path areasLockFilePath = getDataFolder().toPath().resolve("areas.db-shm");
                    Path permLockFilePath = getDataFolder().toPath().resolve("permission_overrides.db-shm");

                    // Check if recovery might be needed: files exist AND there was not a clean shutdown
                    boolean needsAreaRecovery = Files.exists(areasLockFilePath) && !cleanShutdownDetected;
                    boolean needsPermRecovery = Files.exists(permLockFilePath) && !cleanShutdownDetected;

                    if (needsAreaRecovery || needsPermRecovery) {
                        // getLogger().info("Detected potential unclean shutdown. Running database recovery...");
                        
                        // Recover areas database if needed
                        if (needsAreaRecovery) {
                            try (Connection conn = dbManager.getConnection();
                                 Statement stmt = conn.createStatement()) {
                                stmt.execute("PRAGMA wal_checkpoint(FULL)");
                                getLogger().info("Area database recovery completed successfully");
                            } catch (SQLException e) {
                                getLogger().error("Error during area database recovery", e);
                            }
                        }
                        
                        // Recover permission database if needed
                        if (needsPermRecovery && permissionOverrideManager != null) {
                            try (Connection conn = permissionOverrideManager.getConnection();
                                 Statement stmt = conn.createStatement()) {
                                stmt.execute("PRAGMA wal_checkpoint(FULL)");
                                getLogger().info("Permission database recovery completed successfully");
                            } catch (SQLException e) {
                                getLogger().error("Error during permission database recovery", e);
                            }
                        }
                    } else {
                        if (isDebugMode()) {
                            debug("No database recovery required - clean shutdown detected");
                        }
                    }
                    
                    // Reset clean shutdown flag for next time
                    debug("Clean shutdown detection completed");
                    
                    // Initialize the database tables
                    dbManager.init();
                    // getLogger().info("Database tables initialized successfully");
                    
                    areaManager = new AreaManager(this);
                    
                    // Load areas from database
                    if (isDebugMode()) {
                        debug("Loading areas from database...");
                    }
                    areaManager.loadAreas();
                    if (isDebugMode()) {
                        debug("Areas loaded successfully: " + areaManager.getAllAreas().size() + " areas");
                    }
                    
                    // getLogger().info("Database and area managers initialized successfully");
                } catch (Exception e) {
                    getLogger().error("Failed to initialize database or area manager", e);
                    getServer().getPluginManager().disablePlugin(this);
                    return;
                }

                // Initialize GUI manager directly without FormRegistry
                guiManager = new GuiManager(this);

                wandListener = new WandListener(this); // Initialize WandListener
    
                validationUtils = new ValidationUtils();
                
                // Now that PermissionOverrideManager is initialized, load permissions for all areas
                try {
                    // getLogger().info("Loading permissions for all areas...");
                    List<Area> allAreas = areaManager.getAllAreas();
                    
                    // Add counter for monitoring
                    int successCount = 0;
                    int failCount = 0;
                    
                    for (Area area : allAreas) {
                        try {
                            permissionOverrideManager.synchronizeOnLoad(area);
                            successCount++;
                            if (isDebugMode()) {
                                debug("Loaded permissions for area: " + area.getName());
                            }
                        } catch (Exception e) {
                            failCount++;
                            getLogger().error("Failed to load permissions for area: " + area.getName(), e);
                        }
                    }
                    getLogger().info("Permissions loaded for " + successCount + " areas, " + 
                                    failCount + " areas failed to load permissions");
                } catch (Exception e) {
                    getLogger().error("Failed to load permissions for areas", e);
                }
    
                // Register events and commands
                getServer().getPluginManager().registerEvents(this, this);
                getServer().getPluginManager().registerEvents(new FormResponseListener(this), this);
                getServer().getPluginManager().registerEvents(wandListener, this); // Register WandListener
                // getLogger().info("FormResponseListener registered.");
                
                // Initialize and register protection-related listeners
                Timer.Sample listenerInitTimer = performanceMonitor.startTimer();
                try {
                    // Initialize listener manager which handles all protection listeners
                    listenerManager = new ListenerManager(this);
                    
                    // Register form cleanup listener separately (not protection-related)
                    getServer().getPluginManager().registerEvents(new FormCleanupListener(this), this);
                    
                    // Register container listener for stats tracking
                    getServer().getPluginManager().registerEvents(new ContainerListener(this), this);
                    
                    performanceMonitor.stopTimer(listenerInitTimer, "listener_manager_init");
                    // getLogger().info("Listener manager initialized and all listeners registered successfully");
                } catch (Exception e) {
                    performanceMonitor.stopTimer(listenerInitTimer, "listener_manager_init_failed");
                    getLogger().error("Failed to initialize listener manager", e);
                }
                
                // Now that all managers are initialized, normalize toggle states
                if (configManager.getBoolean("normalize_toggle_states", true)) {
                    // getLogger().info("Normalizing toggle states for all areas...");
                    
                    // Use high-frequency context for this intensive operation
                    executeInHighFrequencyContext(() -> {
                        areaManager.normalizeAllAreaToggleStates();
                    });
                    
                    // getLogger().info("Toggle states normalized successfully.");
                }

                // Initialize AreaCommand
                areaCommand = new AreaCommand(this);
    
                // Register the command
                getServer().getCommandMap().register("area", areaCommand);
                // getLogger().info("Area command registered.");
    
                performanceMonitor.stopTimer(startupTimer, "plugin_startup");
                getLogger().info("AdminAreaProtectionPlugin enabled successfully.");
    
            } catch (Exception e) {
                if (performanceMonitor != null) {
                    performanceMonitor.stopTimer(startupTimer, "plugin_startup_failed");
                }
                getLogger().error("Critical error during plugin startup", e);
                getServer().getPluginManager().disablePlugin(this);
            }

            // Defer non-critical initialization tasks - only if plugin is still enabled
            if (this.isEnabled()) {
                try {
                    getServer().getScheduler().scheduleDelayedTask(this, () -> {
                        // Perform any initialization that doesn't need to happen immediately
                        // For example: pre-caching data, background cleanups, etc.
                        if (isDebugMode()) {
                            debug("Running deferred initialization tasks");
                        }
                    }, 20); // 1-second delay
                } catch (Exception e) {
                    getLogger().error("Failed to schedule delayed task", e);
                }
            }
            
            // Log startup time
            long elapsedTime = System.currentTimeMillis() - startTime;
            getLogger().info("Plugin startup completed in " + elapsedTime + "ms");

            logStartupPerformance(startTime);
            
            // Final note about missing dependencies
            if (luckPermsApi == null) {
                getLogger().warning("LuckPerms is not available. Players will have full permissions by default.");
                getLogger().warning("Install LuckPerms to enable permission groups and track-based permissions.");
            }
            
            // Using NukkitMOT mobs for monster protection
            getLogger().info("NukkitMOT monster protection enabled.");
            
            getLogger().info("AdminAreaProtectionPlugin has been successfully enabled!");

            // Initialize FormModuleManager in onEnable after form registry initialization
            this.formModuleManager = new FormModuleManager(this);
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
            
            //getLogger().info("All caches have been cleared and listeners reloaded");

            // After reloading configuration
            if (permissionOverrideManager != null && areaManager != null) {
                try {
                    // Refresh permission caches and synchronize all areas
                    getLogger().info("Synchronizing permissions after config reload...");
                    int syncedCount = areaManager.synchronizeAllPermissions();
                    getLogger().info("Successfully synchronized permissions for " + syncedCount + " areas");
                } catch (Exception e) {
                    getLogger().error("Failed to synchronize permissions during reload", e);
                }
            }
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
                    try {
                        // We know the clearCache method exists but might cause errors in some cases
                        // This is a safety measure
                        luckPermsCache.clearCache();
                    } catch (Exception e) {
                        getLogger().warning("Error clearing LuckPerms cache: " + e.getMessage());
                    }
                }
                
                // Mark as clean shutdown 
                debug("Clean shutdown detection completed");
            
                // IMPORTANT: Save operations in priority order
                // Step 1: First save all permissions to database
                try {
                    if (permissionOverrideManager != null && areaManager != null) {
                        getLogger().info("Synchronizing area permissions...");
                        int syncedCount = areaManager.synchronizeAllPermissions();
                        getLogger().info("Successfully synchronized permissions for " + syncedCount + " areas");
                    }
                } catch (Exception e) {
                    getLogger().error("Failed to save area permissions!", e);
                }
                
                // Step 2: Then save all areas
                if (areaManager != null) {
                    Timer.Sample saveTimer = performanceMonitor.startTimer();
                    try {
                        getLogger().info("Saving all areas before shutdown...");
                        int savedCount = areaManager.saveAllAreas();
                        getLogger().info("Successfully saved " + savedCount + " areas");
                        performanceMonitor.stopTimer(saveTimer, "final_area_save");
                    } catch (Exception e) {
                        performanceMonitor.stopTimer(saveTimer, "final_area_save_failed");
                        getLogger().error("Error saving areas during shutdown", e);
                    }
                }
                
                // Step 3: Close databases in proper order, with pre-close checkpoint
                Timer.Sample dbCloseTimer = performanceMonitor.startTimer();
                
                // First do a soft checkpoint of the area database
                if (dbManager != null) {
                    try (Connection conn = dbManager.getConnection();
                        Statement stmt = conn.createStatement()) {
                        // First try a passive checkpoint which is less likely to cause locks
                        stmt.execute("PRAGMA busy_timeout = 5000");
                        stmt.execute("PRAGMA wal_checkpoint(PASSIVE)");
                        if (isDebugMode()) {
                            debug("Executed passive checkpoint on area database before closing");
                        }
                    } catch (Exception e) {
                        // Just log and continue if this fails
                        getLogger().error("Error checkpointing area database", e);
                    }
                    
                    // Then close the area database
                    try {
                        dbManager.close();
                        getLogger().info("Area database connection closed");
                    } catch (Exception e) {
                        getLogger().error("Failed to close area database", e);
                    }
                }
                
                // Now handle permission database
                if (permissionOverrideManager != null) {
                    // Close it after everything else is done
                    try {
                        permissionOverrideManager.close();
                        getLogger().info("Permission override manager closed");
                    } catch (Exception e) {
                        getLogger().error("Failed to close permission override manager", e);
                    }
                }

                performanceMonitor.stopTimer(dbCloseTimer, "database_close");
                
                // Write a clean shutdown marker file
                try {
                    Path cleanShutdownMarker = getDataFolder().toPath().resolve(CLEAN_SHUTDOWN_MARKER);
                    Files.write(cleanShutdownMarker, "Clean shutdown completed".getBytes());
                    if (debugMode) {
                        debug("Created clean shutdown marker file");
                    }
                } catch (Exception e) {
                    getLogger().error("Failed to create clean shutdown marker", e);
                }
                
                performanceMonitor.stopTimer(shutdownTimer, "plugin_shutdown");
                getLogger().info("AdminAreaProtectionPlugin disabled successfully.");
            } catch (Exception e) {
                getLogger().error("Error during plugin shutdown", e);
            } finally {
                // Final cleanup of the performance monitor
                if (performanceMonitor != null) {
                    try {
                        performanceMonitor.close();
                    } catch (Throwable t) {
                        // Catch all errors during PerformanceMonitor close to ensure plugin shutdown completes
                        getLogger().error("Error during performance monitor shutdown - this won't affect plugin operation", t);
                    }
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
        public Object getLuckPermsApi() {
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
            if (luckPermsApi == null) return true;  // Always allow if LuckPerms is not available
            
            try {
                // Use reflection to interact with LuckPerms API
                // Get the player adapter
                Object playerAdapter = luckPermsClass.getMethod("getPlayerAdapter", Class.class)
                    .invoke(luckPermsApi, Player.class);
                
                // Get the user from the adapter
                Object user = playerAdapter.getClass().getMethod("getUser", Player.class)
                    .invoke(playerAdapter, player);
                
                // Get the query options
                Object queryOptions = user.getClass().getMethod("getQueryOptions").invoke(user);
                
                // Get inherited groups
                Object groups = user.getClass().getMethod("getInheritedGroups", queryOptions.getClass())
                    .invoke(user, queryOptions);
                
                // Convert to List for sorting
                List<Object> sortedGroups = new ArrayList<>((Collection<?>) groups);
                
                // Sort groups by weight
                Collections.sort(sortedGroups, (g1, g2) -> {
                    try {
                        String name1 = (String) groupClass.getMethod("getName").invoke(g1);
                        String name2 = (String) groupClass.getMethod("getName").invoke(g2);
                        
                        return Integer.compare(
                            luckPermsCache.getGroupWeight(name2),
                            luckPermsCache.getGroupWeight(name1)
                        );
                    } catch (Exception e) {
                        if (debugMode) {
                            debug("Error sorting groups: " + e.getMessage());
                        }
                        return 0;
                    }
                });
                
                // Check permissions starting from highest weight group
                for (Object group : sortedGroups) {
                    String groupName = (String) groupClass.getMethod("getName").invoke(group);
                    if (!area.getEffectivePermission(groupName, permission)) {
                        return false;
                    }
                }
                
                return true;
            } catch (Exception e) {
                if (debugMode) {
                    debug("Error checking group permissions: " + e.getMessage());
                }
                return true; // Default to allowing if there's an error
            }
        }
    
        public Set<String> getGroupNames() {
            if (luckPermsApi == null) return new HashSet<>();
            
            try {
                // Use reflection to get group names
                Object groupManager = luckPermsClass.getMethod("getGroupManager").invoke(luckPermsApi);
                Object loadedGroups = groupManager.getClass().getMethod("getLoadedGroups").invoke(groupManager);
                
                Set<String> groupNames = new HashSet<>();
                for (Object group : (Collection<?>) loadedGroups) {
                    String name = (String) groupClass.getMethod("getName").invoke(group);
                    groupNames.add(name);
                }
                
                return groupNames;
            } catch (Exception e) {
                if (debugMode) {
                    debug("Error getting group names: " + e.getMessage());
                }
                return new HashSet<>();
            }
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
            }

            try {
                // Save directly to database using DatabaseManager
                dbManager.saveArea(area);
                
                if (isDebugMode()) {
                    debug("Area saved successfully to database");
                }
            } catch (Exception e) {
                getLogger().error("Failed to save area to database: " + area.getName(), e);
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
                // Use string format with direct console output to avoid logger issues
                String debugMessage = "[Debug] " + message;
                System.out.println("[" + getName() + "] " + debugMessage);
                
                // Only try logging if not already in a potential recursive situation
                try {
                    // Use a special system property to prevent recursive calls
                    if (!"true".equals(System.getProperty("adminarea.debug.recursion"))) {
                        System.setProperty("adminarea.debug.recursion", "true");
                        try {
                            getLogger().info(debugMessage);
                        } finally {
                            System.clearProperty("adminarea.debug.recursion");
                        }
                    }
                } catch (Exception e) {
                    // Ignore any errors during logging
                    System.out.println("[AdminAreaProtection] Error logging debug message: " + e.getMessage());
                }
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
         * Gets the form module manager instance
         * @return The FormModuleManager instance
         */
        public FormModuleManager getFormModuleManager() {
            return formModuleManager;
        }

        private void logStartupPerformance(long startTime) {
            long totalTime = System.currentTimeMillis() - startTime;
            
            StringBuilder perfBuilder = new StringBuilder();
            perfBuilder.append("\n------ Startup Performance ------\n");
            perfBuilder.append("Total startup time: ").append(totalTime).append("ms\n");
            
            // Add specific module timings if available
            if (performanceMonitor != null) {
                // Since getTimings() is not available, we'll use other metrics or skip this part
                perfBuilder.append("For detailed timings, enable debug mode");
            }
            
            perfBuilder.append("\n------------------------------");
            
            // Log the performance data
            getLogger().info(perfBuilder.toString());
            
            // Check for slow startup
            if (totalTime > 250) {
                getLogger().warning("Slow plugin startup detected: " + totalTime + "ms");
            }
        }

        /**
         * Verifies the track permissions database and attempts to repair any issues.
         * This is called during startup to ensure the database is in a valid state.
         */
        private void verifyTrackPermissionsDatabase() {
            if (permissionOverrideManager == null) {
                getLogger().error("Cannot verify track permissions database: PermissionOverrideManager is null");
                return;
            }
            
            try {
                // getLogger().info("Verifying track permissions database integrity...");
                
                // Get a connection to the database
                try (Connection conn = permissionOverrideManager.getConnection();
                     Statement stmt = conn.createStatement()) {
                    
                    // Check if the track_permissions table exists
                    boolean tableExists = false;
                    try (ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='track_permissions'")) {
                        tableExists = rs.next();
                    }
                    
                    if (!tableExists) {
                        getLogger().warning("track_permissions table does not exist! This should never happen.");
                        return;
                    }
                    
                    // Check the total number of track permissions
                    int totalCount = 0;
                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM track_permissions")) {
                        totalCount = rs.next() ? rs.getInt(1) : 0;
                    }
                    
                    // getLogger().info("Found " + totalCount + " total track permission entries in database");
                    
                    // Only force save track permissions if none exist in the database
                    // This prevents overwriting existing permissions during startup
                    if (totalCount == 0) {
                        getLogger().warning("No track permissions found in database. Attempting recovery...");
                        
                        // Since no permissions exist in the database, try to restore from memory
                        int forceSavedCount = 0;
                        
                        if (areaManager != null) {
                            List<Area> allAreas = areaManager.getAllAreas();
                            // getLogger().info("Force saving track permissions for " + allAreas.size() + " areas...");
                            
                            for (Area area : allAreas) {
                                Map<String, Map<String, Boolean>> trackPerms = area.getTrackPermissions();
                                if (trackPerms != null && !trackPerms.isEmpty()) {
                                    for (Map.Entry<String, Map<String, Boolean>> entry : trackPerms.entrySet()) {
                                        String trackName = entry.getKey();
                                        Map<String, Boolean> permissions = entry.getValue();
                                        
                                        if (permissions != null && !permissions.isEmpty()) {
                                            try {
                                                // Use the force flag to ensure saving
                                                permissionOverrideManager.setTrackPermissions(area, trackName, new HashMap<>(permissions), true);
                                                forceSavedCount++;
                                                
                                                if (isDebugMode() && forceSavedCount % 5 == 0) {
                                                    debug("Force saved " + forceSavedCount + " track permissions so far");
                                                }
                                            } catch (Exception e) {
                                                getLogger().error("Failed to force save track permissions for track " + trackName + 
                                                    " in area " + area.getName(), e);
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // getLogger().info("Force saved " + forceSavedCount + " track permissions to the database");
                        
                            // Force a checkpoint to ensure the changes are persisted
                            try {
                                stmt.execute("PRAGMA wal_checkpoint(FULL)");
                                // getLogger().info("Executed full checkpoint to persist track permissions");
                            } catch (SQLException e) {
                                getLogger().error("Failed to execute checkpoint", e);
                            }
                            
                            // Check the permissions count again after saving
                            if (isDebugMode()) {
                                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM track_permissions")) {
                                    int newCount = rs.next() ? rs.getInt(1) : 0;
                                    debug("After force saving: " + newCount + " track permission entries in database (added " + (newCount - totalCount) + ")");
                                }
                            }
                        }
                    } else {
                        // getLogger().info("Track permissions exist in database, skipping force save to preserve data");
                        
                        // Still check for coherence between memory and database
                        if (areaManager != null && isDebugMode()) {
                            List<Area> allAreas = areaManager.getAllAreas();
                            debug("Verifying track permissions for " + allAreas.size() + " areas...");
                            
                            for (Area area : allAreas) {
                                // Get permissions from the database for verification
                                Map<String, Map<String, Boolean>> dbTrackPerms = 
                                    permissionOverrideManager.getAllTrackPermissions(area.getName());
                                
                                // Get permissions from memory
                                Map<String, Map<String, Boolean>> memTrackPerms = area.getTrackPermissions();
                                
                                if (dbTrackPerms != null && !dbTrackPerms.isEmpty()) {
                                    debug("  Area " + area.getName() + " has " + dbTrackPerms.size() + 
                                        " tracks in database, " + (memTrackPerms != null ? memTrackPerms.size() : 0) + 
                                        " tracks in memory");
                                }
                            }
                        }
                    }
                    
                    // Check for any NULL values in critical columns
                    int nullCount = 0;
                    try (ResultSet rs = stmt.executeQuery(
                            "SELECT COUNT(*) FROM track_permissions WHERE area_name IS NULL OR track_name IS NULL OR permission IS NULL")) {
                        nullCount = rs.next() ? rs.getInt(1) : 0;
                    }
                    
                    if (nullCount > 0) {
                        getLogger().warning("Found " + nullCount + " track permission entries with NULL values, fixing...");
                        stmt.execute("DELETE FROM track_permissions WHERE area_name IS NULL OR track_name IS NULL OR permission IS NULL");
                        // getLogger().info("Removed " + nullCount + " invalid track permission entries");
                    }
                    
                    // getLogger().info("Track permissions database verification completed");
                }
            } catch (Exception e) {
                getLogger().error("Error verifying track permissions database", e);
            }
        }

        /**
         * Verifies the group permissions database and attempts to repair any issues.
         * This is called during startup to ensure the database is in a valid state.
         */
        private void verifyGroupPermissionsDatabase() {
            if (permissionOverrideManager == null) {
                getLogger().error("Cannot verify group permissions database: PermissionOverrideManager is null");
                return;
            }
            
            try {
                // getLogger().info("Verifying group permissions database integrity...");
                
                // Get a connection to the database
                try (Connection conn = permissionOverrideManager.getConnection();
                     Statement stmt = conn.createStatement()) {
                    
                    // Check if the group_permissions table exists
                    boolean tableExists = false;
                    try (ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='group_permissions'")) {
                        tableExists = rs.next();
                    }
                    
                    if (!tableExists) {
                        getLogger().warning("group_permissions table does not exist! This should never happen.");
                        return;
                    }
                    
                    // Check the total number of group permissions
                    int totalCount = 0;
                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM group_permissions")) {
                        totalCount = rs.next() ? rs.getInt(1) : 0;
                    }
                    
                    // getLogger().info("Found " + totalCount + " total group permission entries in database");
                    
                    // Only force save group permissions if none exist in the database
                    // This prevents overwriting existing permissions during startup
                    if (totalCount == 0) {
                        getLogger().warning("No group permissions found in database. Attempting recovery...");
                        
                        // Since no permissions exist in the database, try to restore from memory
                        int forceSavedCount = 0;
                        
                        if (areaManager != null) {
                            List<Area> allAreas = areaManager.getAllAreas();
                            // getLogger().info("Force saving group permissions for " + allAreas.size() + " areas...");
                            
                            for (Area area : allAreas) {
                                Map<String, Map<String, Boolean>> groupPerms = area.getGroupPermissions();
                                if (groupPerms != null && !groupPerms.isEmpty()) {
                                    for (Map.Entry<String, Map<String, Boolean>> entry : groupPerms.entrySet()) {
                                        String groupName = entry.getKey();
                                        Map<String, Boolean> permissions = entry.getValue();
                                        
                                        if (permissions != null && !permissions.isEmpty()) {
                                            try {
                                                // Use the force flag to ensure saving
                                                permissionOverrideManager.setGroupPermissions(area.getName(), groupName, new HashMap<>(permissions), true);
                                                forceSavedCount++;
                                                
                                                if (isDebugMode() && forceSavedCount % 5 == 0) {
                                                    debug("Force saved " + forceSavedCount + " group permissions so far");
                                                }
                                            } catch (Exception e) {
                                                getLogger().error("Failed to force save group permissions for group " + groupName + 
                                                    " in area " + area.getName(), e);
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // getLogger().info("Force saved " + forceSavedCount + " group permissions to the database");
                        
                            // Force a checkpoint to ensure the changes are persisted
                            try {
                                stmt.execute("PRAGMA wal_checkpoint(FULL)");
                                // getLogger().info("Executed full checkpoint to persist group permissions");
                            } catch (SQLException e) {
                                getLogger().error("Failed to execute checkpoint", e);
                            }
                            
                            // Check the permissions count again after saving
                            if (isDebugMode()) {
                                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM group_permissions")) {
                                    int newCount = rs.next() ? rs.getInt(1) : 0;
                                    debug("After force saving: " + newCount + " group permission entries in database (added " + (newCount - totalCount) + ")");
                                }
                            }
                        }
                    } else {
                        // getLogger().info("Group permissions exist in database, skipping force save to preserve data");
                        
                        // Still check for coherence between memory and database
                        if (areaManager != null && isDebugMode()) {
                            List<Area> allAreas = areaManager.getAllAreas();
                            debug("Verifying group permissions for " + allAreas.size() + " areas...");
                            
                            for (Area area : allAreas) {
                                // Get permissions from the database for verification
                                Map<String, Map<String, Boolean>> dbGroupPerms = 
                                    permissionOverrideManager.getAllGroupPermissions(area.getName());
                                
                                // Get permissions from memory
                                Map<String, Map<String, Boolean>> memGroupPerms = area.getGroupPermissions();
                                
                                if (dbGroupPerms != null && !dbGroupPerms.isEmpty()) {
                                    debug("  Area " + area.getName() + " has " + dbGroupPerms.size() + 
                                        " groups in database, " + (memGroupPerms != null ? memGroupPerms.size() : 0) + 
                                        " groups in memory");
                                }
                            }
                        }
                    }
                    
                    // Check for any NULL values in critical columns
                    int nullCount = 0;
                    try (ResultSet rs = stmt.executeQuery(
                            "SELECT COUNT(*) FROM group_permissions WHERE area_name IS NULL OR group_name IS NULL OR permission IS NULL")) {
                        nullCount = rs.next() ? rs.getInt(1) : 0;
                    }
                    
                    if (nullCount > 0) {
                        getLogger().warning("Found " + nullCount + " group permission entries with NULL values, fixing...");
                        stmt.execute("DELETE FROM group_permissions WHERE area_name IS NULL OR group_name IS NULL OR permission IS NULL");
                        // getLogger().info("Removed " + nullCount + " invalid group permission entries");
                    }
                    
                    // getLogger().info("Group permissions database verification completed");
                }
            } catch (Exception e) {
                getLogger().error("Error verifying group permissions database", e);
            }
        }

        /**
         * Get the tracker for recent save operations
         * @return The RecentSaveTracker instance
         */
        public RecentSaveTracker getRecentSaveTracker() {
            return recentSaveTracker;
        }

        /**
         * Executes an operation in a high-frequency context to prevent excessive database operations
         * @param operation The operation to execute
         */
        public void executeInHighFrequencyContext(Runnable operation) {
            RecentSaveTracker tracker = getRecentSaveTracker();
            if (tracker != null) {
                tracker.beginHighFrequencyContext();
            }
            try {
                operation.run();
            } finally {
                if (tracker != null) {
                    tracker.endHighFrequencyContext();
                }
            }
        }
        
        /**
         * Executes an operation in a high-frequency context and returns a result
         * @param <T> The type of result
         * @param operation The operation to execute
         * @return The result of the operation
         */
        public <T> T executeInHighFrequencyContext(Supplier<T> operation) {
            RecentSaveTracker tracker = getRecentSaveTracker();
            if (tracker != null) {
                tracker.beginHighFrequencyContext();
            }
            try {
                return operation.get();
            } finally {
                if (tracker != null) {
                    tracker.endHighFrequencyContext();
                }
            }
        }

        /**
         * Tracks recent save operations to prevent excessive database writes
         */
        public static class RecentSaveTracker {
            private final Map<String, Long> lastSaveTime = new ConcurrentHashMap<>();
            private final Map<String, Long> lastUpdateTime = new ConcurrentHashMap<>();
            private final Map<String, Long> lastSyncTime = new ConcurrentHashMap<>();
            private final long MIN_SAVE_INTERVAL = TimeUnit.MILLISECONDS.convert(1, TimeUnit.SECONDS);
            private final long MIN_UPDATE_INTERVAL = TimeUnit.MILLISECONDS.convert(2, TimeUnit.SECONDS);
            private final long MIN_SYNC_INTERVAL = TimeUnit.MILLISECONDS.convert(3, TimeUnit.SECONDS);
            private final ThreadLocal<Boolean> highFrequencyContext = ThreadLocal.withInitial(() -> false);
            
            /**
             * Mark the start of a high-frequency operation context
             */
            public void beginHighFrequencyContext() {
                highFrequencyContext.set(true);
            }
            
            /**
             * Mark the end of a high-frequency operation context
             */
            public void endHighFrequencyContext() {
                highFrequencyContext.set(false);
            }
            
            /**
             * Check if current thread is in a high-frequency update context
             */
            public boolean isHighFrequencyContext() {
                return highFrequencyContext.get();
            }
            
            /**
             * Check if an area was recently saved
             */
            public boolean wasRecentlySaved(String areaKey, long currentTime) {
                Long lastTime = lastSaveTime.get(areaKey);
                return lastTime != null && (currentTime - lastTime) < MIN_SAVE_INTERVAL;
            }
            
            /**
             * Check if an area was recently updated in the database
             */
            public boolean wasRecentlyUpdated(String areaKey, long currentTime) {
                Long lastTime = lastUpdateTime.get(areaKey);
                return lastTime != null && (currentTime - lastTime) < MIN_UPDATE_INTERVAL;
            }
            
            /**
             * Check if an area was recently synchronized with permission system
             */
            public boolean wasRecentlySynced(String areaKey, long currentTime) {
                Long lastTime = lastSyncTime.get(areaKey);
                return lastTime != null && (currentTime - lastTime) < MIN_SYNC_INTERVAL;
            }
            
            /**
             * Mark an area as saved
             */
            public void markSaved(String areaKey, long time) {
                lastSaveTime.put(areaKey, time);
            }
            
            /**
             * Mark an area as updated in the database
             */
            public void markUpdated(String areaKey, long time) {
                lastUpdateTime.put(areaKey, time);
            }
            
            /**
             * Mark an area as synchronized with permission system
             */
            public void markSynced(String areaKey, long time) {
                lastSyncTime.put(areaKey, time);
            }
            
            /**
             * Clear all tracking data
             */
            public void clear() {
                lastSaveTime.clear();
                lastUpdateTime.clear();
                lastSyncTime.clear();
            }
        }

        /**
         * Gets an area by name with rate limiting for high-frequency operations
         * @param name The name of the area to get
         * @return The area, or null if not found
         */
        public Area getAreaRateLimited(String name) {
            // Use a ThreadLocal boolean to prevent recursive calls
            RecentSaveTracker tracker = getRecentSaveTracker();
            
            // If we're already in a high-frequency context, just get the area directly
            if (tracker.isHighFrequencyContext()) {
                return getArea(name);
            }
            
            // Otherwise, wrap the operation in a high-frequency context
            return executeInHighFrequencyContext(() -> getArea(name));
        }

        /**
         * Registers our custom YAML handler to ensure compatibility with Nukkit MOT.
         * This needs to be called before any Config loading.
         */
        private void registerCustomYamlHandler() {
            try {
                getLogger().info("Registering custom YAML handler for better compatibility");
                // Pre-extract language files so they can be loaded with our custom YAML handler
                File langDir = new File(getDataFolder(), "lang");
                if (!langDir.exists()) {
                    langDir.mkdirs();
                    saveResource("lang/en_US.yml", false);
                    saveResource("lang/ru_RU.yml", false);
                }
            } catch (Exception e) {
                getLogger().error("Failed to register custom YAML handler", e);
            }
        }

        public boolean isPlayerInTrack(Player player, String trackName) {
            if (!isLuckPermsEnabled() || player == null || trackName == null) {
                return false;
            }
            
            try {
                // Use reflection to interact with LuckPerms API
                // Get the track manager
                Object trackManager = luckPermsClass.getMethod("getTrackManager").invoke(luckPermsApi);
                
                // Get the track
                Object track = trackManager.getClass().getMethod("getTrack", String.class).invoke(trackManager, trackName);
                if (track == null) {
                    if (isDebugMode()) {
                        debug("Track " + trackName + " does not exist");
                    }
                    return false;
                }
                
                // Get all groups in this track
                List<String> trackGroups = (List<String>) track.getClass().getMethod("getGroups").invoke(track);
                
                // Get the player's primary group for debugging
                String primaryGroup = getPrimaryGroup(player);
                
                // Check ALL groups the player is in, not just primary group
                String playerName = player.getName();
                List<String> playerGroups = getLuckPermsCache().getGroups(playerName);
                
                if (isDebugMode()) {
                    debug("Player " + player.getName() + " has primary group: " + primaryGroup);
                    debug("Player " + player.getName() + " belongs to groups: " + String.join(", ", playerGroups));
                    debug("Track " + trackName + " has groups: " + String.join(", ", trackGroups));
                }
                
                // Check if any of the player's groups are in the track
                boolean isInTrack = false;
                for (String group : playerGroups) {
                    if (trackGroups.contains(group)) {
                        if (isDebugMode()) {
                            debug("Player group " + group + " is in track " + trackName);
                        }
                        isInTrack = true;
                        break;
                    }
                }
                
                if (isDebugMode()) {
                    debug("Player has group in track: " + isInTrack);
                }
                
                // If not directly in track by group membership, check inheritance
                if (!isInTrack) {
                    for (String group : playerGroups) {
                        boolean inheritedGroupInTrack = isPlayerInTrackByInheritance(player, trackName, group, trackGroups);
                        if (inheritedGroupInTrack) {
                            if (isDebugMode()) {
                                debug("Player has inherited group from " + group + " in track: " + inheritedGroupInTrack);
                            }
                            isInTrack = true;
                            break;
                        }
                    }
                }
                
                return isInTrack;
            } catch (Exception e) {
                getLogger().error("Error checking track membership", e);
                return false;
            }
        }

        /**
         * Checks if a player is in a track through inheritance
         * @param player The player to check
         * @param trackName The name of the track
         * @param groupName The name of the group to check inheritance from
         * @param trackGroups The groups in the track
         * @return True if the player is in the track through inheritance
         */
        public boolean isPlayerInTrackByInheritance(Player player, String trackName, String groupName, List<String> trackGroups) {
            if (!isLuckPermsEnabled() || player == null || trackName == null || groupName == null || trackGroups == null) {
                return false;
            }
            
            try {
                // Get all groups the specified group inherits from
                List<String> inheritedGroups = getGroupInheritance(groupName);
                
                if (isDebugMode()) {
                    debug("Group " + groupName + " inherits from groups: " + String.join(", ", inheritedGroups));
                }
                
                // Check if any of the group's inherited groups are in the track
                for (String group : inheritedGroups) {
                    if (trackGroups.contains(group)) {
                        if (isDebugMode()) {
                            debug("Group " + groupName + " inherits from " + group + " which is in track " + trackName);
                        }
                        return true;
                    }
                }
                
                return false;
            } catch (Exception e) {
                getLogger().error("Error checking track membership through inheritance", e);
                return false;
            }
        }

        public String getPrimaryGroup(Player player) {
            if (!isLuckPermsEnabled() || player == null) {
                return null;
            }
            
            try {
                // Get the user manager
                Object userManager = luckPermsClass.getMethod("getUserManager").invoke(luckPermsApi);
                
                // Get the user
                Object user = userManager.getClass().getMethod("getUser", UUID.class)
                    .invoke(userManager, player.getUniqueId());
                
                if (user != null) {
                    return (String) user.getClass().getMethod("getPrimaryGroup").invoke(user);
                }
                return null;
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
            // If permissionOverrideManager is null, this is a critical error,
            // as it should never be accessed before initialization
            if (permissionOverrideManager == null) {
                getLogger().error("CRITICAL: PermissionOverrideManager was accessed before initialization");
                
                // Log stack trace to help identify where this is being called from
                try {
                    throw new IllegalStateException("PermissionOverrideManager not initialized");
                } catch (IllegalStateException e) {
                    getLogger().error("Call stack:", e);
                }
                
                // Create emergency instance but log warning
                try {
                    getLogger().warning("Creating emergency PermissionOverrideManager instance");
                    permissionOverrideManager = new PermissionOverrideManager(this);
                } catch (Exception e) {
                    getLogger().error("Failed to create emergency PermissionOverrideManager", e);
                }
            }
            return permissionOverrideManager;
        }
}