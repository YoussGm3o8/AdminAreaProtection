package adminarea.managers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.listeners.EnderPearlListener;
import adminarea.listeners.EntityListener;
import adminarea.listeners.EnvironmentListener;
import adminarea.listeners.ExperienceListener;
import adminarea.listeners.ItemListener;
import adminarea.listeners.PlayerEffectListener;
import adminarea.listeners.ProtectionListener;
import adminarea.listeners.VehicleListener;
import adminarea.listeners.WandListener;
import cn.nukkit.Player;
import cn.nukkit.event.Listener;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages and registers all protection-related listeners
 */
public class ListenerManager {
    private final AdminAreaProtectionPlugin plugin;
    private final List<Listener> listeners = new ArrayList<>();
    private ProtectionListener protectionListener;
    
    // Individual listeners
    private VehicleListener vehicleListener;
    private PlayerEffectListener playerEffectListener;
    private EnderPearlListener enderPearlListener;
    private ExperienceListener experienceListener;
    private ItemListener itemListener;
    private EnvironmentListener environmentListener;
    private EntityListener entityListener;
    private WandListener wandListener;

    /**
     * Creates a new ListenerManager and initializes all listeners
     * 
     * @param plugin The plugin instance
     */
    public ListenerManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        initialize();
    }

    /**
     * Initialize and register all listeners
     */
    private void initialize() {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Main protection listener - should be registered first
            protectionListener = new ProtectionListener(plugin);
            listeners.add(protectionListener);
            
            // Create specialized listeners
            vehicleListener = new VehicleListener(plugin, protectionListener);
            listeners.add(vehicleListener);
            
            playerEffectListener = new PlayerEffectListener(plugin);
            listeners.add(playerEffectListener);
            
            enderPearlListener = new EnderPearlListener(plugin, protectionListener);
            listeners.add(enderPearlListener);
            
            experienceListener = new ExperienceListener(plugin, protectionListener);
            listeners.add(experienceListener);
            
            itemListener = new ItemListener(plugin, protectionListener);
            listeners.add(itemListener);
            
            environmentListener = new EnvironmentListener(plugin, protectionListener);
            listeners.add(environmentListener);
            
            entityListener = new EntityListener(plugin, protectionListener);
            listeners.add(entityListener);
            
            wandListener = new WandListener(plugin);
            listeners.add(wandListener);
            
            // Register all listeners with the server
            for (Listener listener : listeners) {
                plugin.getServer().getPluginManager().registerEvents(listener, plugin);
                if (plugin.isDebugMode()) {
                    plugin.debug("Registered listener: " + listener.getClass().getSimpleName());
                }
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("Registered " + listeners.size() + " protection listeners");
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "listener_initialization");
        }
    }
    
    /**
     * Reloads all listeners by clearing caches
     */
    public void reload() {
        // Clear caches in listeners that have them
        if (itemListener != null) {
            itemListener.clearCache();
        }
        
        if (environmentListener != null) {
            environmentListener.invalidateCache();
        }
        
        if (protectionListener != null) {
            protectionListener.cleanup();
        }
        
        // Reload player effects
        if (playerEffectListener != null) {
            playerEffectListener.reloadEffects();
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("Reloaded all protection listeners");
        }
    }
    
    /**
     * Cleans up all listeners when the plugin is disabled
     */
    public void cleanup() {
        // Call cleanup methods on listeners that have them
        if (protectionListener != null) {
            protectionListener.cleanup();
        }
        
        if (wandListener != null) {
            wandListener.cleanup();
        }
        
        // Clear the entity handler's cache
        adminarea.entity.MonsterHandler.cleanup();
        
        listeners.clear();
    }
    
    /**
     * Handles a player leaving the server
     * 
     * @param player The player who left
     */
    public void handlePlayerQuit(Player player) {
        // Clean up any player-specific caches
        if (protectionListener != null) {
            protectionListener.cleanup(player);
        }
    }
    
    /**
     * Gets the main protection listener
     * 
     * @return The main protection listener
     */
    public ProtectionListener getProtectionListener() {
        return protectionListener;
    }
    
    /**
     * Gets the environment listener
     * 
     * @return The environment listener
     */
    public EnvironmentListener getEnvironmentListener() {
        return environmentListener;
    }
    
    /**
     * Gets the entity listener
     * 
     * @return The entity listener
     */
    public EntityListener getEntityListener() {
        return entityListener;
    }
    
    /**
     * Gets the player effect listener
     * 
     * @return The player effect listener
     */
    public PlayerEffectListener getPlayerEffectListener() {
        return playerEffectListener;
    }
    
    /**
     * Gets the wand listener
     * 
     * @return The wand listener
     */
    public WandListener getWandListener() {
        return wandListener;
    }
}
