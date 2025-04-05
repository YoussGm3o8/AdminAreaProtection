package adminarea.form.utils;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import cn.nukkit.Player;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.utils.LogLevel;
import java.util.Map;
import java.util.HashMap;

/**
 * Utility class for form handling using the Nukkit MOT form system.
 * Provides common functionality for form handling.
 */
public class FormUtils {
    private static AdminAreaProtectionPlugin plugin;
    
    /**
     * Initializes the form utilities with the plugin instance.
     * This should be called once when the plugin starts.
     */
    public static void init(AdminAreaProtectionPlugin pluginInstance) {
        plugin = pluginInstance;
    }
    
    /**
     * Get the plugin instance
     */
    public static AdminAreaProtectionPlugin getPlugin() {
        return plugin;
    }
    
    /**
     * Clean up form tracking data for a player
     */
    public static void cleanup(Player player) {
        if (plugin.isDebugMode()) {
            plugin.debug("Cleaning up form data");
            var currentForm = plugin.getFormIdMap().get(player.getName());
            if (currentForm != null) {
                plugin.debug("  Removing form data: " + currentForm.getFormId());
            }
        }
        
        // Remove both regular and editing form data
        plugin.getFormIdMap().remove(player.getName());
        plugin.getFormIdMap().remove(player.getName() + "_editing");
        
        // Clear navigation history
        plugin.getGuiManager().clearNavigationHistory(player);
    }

    /**
     * Normalize permission node to ensure it has the correct prefix
     */
    public static String normalizePermissionNode(String node) {
        if (node == null || node.isEmpty()) {
            return "gui.permissions.toggles.default";
        }
        
        // If it already has the prefix, return as is
        if (node.startsWith("gui.permissions.toggles.")) {
            return node;
        }
        
        // Only add prefix for allowXXX permission names
        // This ensures we don't accidentally add the prefix to non-toggle keys
        if (node.startsWith("allow")) {
            return "gui.permissions.toggles." + node;
        }
        
        // Return the original node if it's not a toggle permission
        return node;
    }

    /**
     * Create a standard error handler for forms
     */
    public static FormResponseHandler createErrorHandler(Player player) {
        return FormResponseHandler.withoutPlayer(ignored -> {
            plugin.getLogger().log(LogLevel.ERROR, "Error handling form response");
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            cleanup(player);
        });
    }

    /**
     * Create a handler for when form is closed
     */
    public static FormResponseHandler createCloseHandler(Player player) {
        return FormResponseHandler.withoutPlayer(ignored -> {
            cleanup(player);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Form closed by player " + player.getName());
            }
        });
    }
    
    /**
     * Mark that a validation error has been shown to the player
     */
    public static void markValidationErrorShown() {
        throw new RuntimeException("_validation_error_already_shown");
    }
    
    /**
     * Get the permission override manager
     */
    public static adminarea.permissions.PermissionOverrideManager getOverrideManager() {
        return plugin.getPermissionOverrideManager();
    }
    
    /**
     * Update area toggles and return count of changed settings
     */
    public static int updateAreaToggles(Area area, Map<String, Boolean> toggles) {
        int changedSettings = 0;
        
        for (Map.Entry<String, Boolean> entry : toggles.entrySet()) {
            String key = entry.getKey();
            Boolean value = entry.getValue();
            
            // Skip null values
            if (value == null) {
                continue;
            }
            
            // Get current setting using the correct method
            boolean currentValue = area.getToggleState(key);
            
            // Only update if changed
            if (currentValue != value) {
                // Use the correct method to set the toggle
                area.setToggleState(key, value);
                changedSettings++;
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Changed toggle " + key + " from " + currentValue + " to " + value);
                }
            }
        }
        
        return changedSettings;
    }
    
    /**
     * Update potion effects and notify player
     */
    public static void updatePotionEffectsAndNotifyPlayer(Player player, Area area, Map<String, Integer> effectStrengths, String nextFormId) {
        if (area == null || effectStrengths == null || effectStrengths.isEmpty()) {
            plugin.getGuiManager().openFormById(player, nextFormId, area);
            return;
        }
        
        try {
            // Only log non-zero effects to reduce spam
            if (plugin.isDebugMode()) {
                // Filter for just non-zero values
                Map<String, Integer> nonZeroEffects = new HashMap<>();
                for (Map.Entry<String, Integer> entry : effectStrengths.entrySet()) {
                    if (entry.getValue() > 0) {
                        nonZeroEffects.put(entry.getKey(), entry.getValue());
                    }
                }
                
                if (!nonZeroEffects.isEmpty()) {
                    plugin.debug("Updating " + nonZeroEffects.size() + " non-zero potion effects for area: " + area.getName() + " " + nonZeroEffects);
                } else {
                    plugin.debug("Clearing all potion effects for area: " + area.getName());
                }
            }
            
            // Update the area using AreaManager's specialized method
            plugin.getAreaManager().updateAreaPotionEffects(area, effectStrengths);
            
            // Force clear any caches
            plugin.getDatabaseManager().invalidateAreaCache(area.getName());
            
            // Force reload the area to ensure we have the latest data
            try {
                Area freshArea = plugin.getDatabaseManager().loadArea(area.getName());
                if (freshArea != null) {
                    // Update the area in the global maps to make it available for next forms
                    plugin.getAreaManager().updateArea(freshArea);
                    
                    // Only log refresh if there are non-zero effects
                    if (plugin.isDebugMode()) {
                        Map<String, Integer> effects = freshArea.getAllPotionEffects();
                        boolean hasNonZero = effects.values().stream().anyMatch(v -> v > 0);
                        
                        if (hasNonZero) {
                            plugin.debug("Area " + area.getName() + " now has potion effects: " + effects);
                        }
                    }
                    
                    // Use the fresh area for the next form
                    area = freshArea;
                }
            } catch (Exception e) {
                plugin.getLogger().error("Failed to reload area after potion effect updates", e);
            }
            
            // Notify player of success
            player.sendMessage(plugin.getLanguageManager().get("messages.area.updated", 
                Map.of(
                    "area", area.getName(), 
                    "count", String.valueOf(effectStrengths.size())
                )));
                
            // Apply the potion effects immediately to reflect changes
            if (plugin.getListenerManager() != null && plugin.getListenerManager().getPlayerEffectListener() != null) {
                plugin.getListenerManager().getPlayerEffectListener().reloadEffects();
                if (plugin.isDebugMode()) {
                    plugin.debug("Reloaded potion effects for immediate application");
                }
            }
                
            // Open next form with the refreshed area
            plugin.getGuiManager().openFormById(player, nextFormId, area);
            
        } catch (Exception e) {
            plugin.getLogger().error("Error updating potion effects", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            cleanup(player);
        }
    }
    
    /**
     * Update area and notify player
     */
    public static void updateAreaAndNotifyPlayer(Player player, Area area, int changedSettings, String nextFormId, String settingType) {
        try {
            // Only notify if changes were made
            if (changedSettings > 0) {
                player.sendMessage(plugin.getLanguageManager().get("messages.area.updated",
                    Map.of(
                        "area", area.getName(),
                        "count", String.valueOf(changedSettings)
                    )));
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Updated " + changedSettings + " " + settingType + " settings for area: " + area.getName());
                    plugin.debug("Toggle states after update: " + area.toDTO().toggleStates());
                }
                
                // Update the database with current area data - this is the only database operation needed
                try {
                    // Update the area in the database
                    plugin.getDatabaseManager().updateArea(area);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Area updated in database: " + area.getName());
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Error updating area in database: " + area.getName(), e);
                    // Continue with the rest of the method even if this fails
                }
                
                // Update the area in memory
                try {
                    // Update the area in the AreaManager
                    plugin.getAreaManager().updateArea(area);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Area updated in memory: " + area.getName());
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Error updating area in memory: " + area.getName(), e);
                }
            } else {
                player.sendMessage(plugin.getLanguageManager().get("messages.area.noChanges",
                    Map.of("area", area.getName())));
            }
            
            // Return to next form
            plugin.getGuiManager().openFormById(player, nextFormId, area);
            
        } catch (Exception e) {
            plugin.getLogger().error("Error notifying player of area updates", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            cleanup(player);
        }
    }
} 