package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.form.IFormHandler;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseModal;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.utils.LogLevel;
import java.util.Map;
import java.util.HashMap;

/**
 * Base handler for all forms in the plugin.
 * Provides common functionality for form handling.
 */
public abstract class BaseFormHandler implements IFormHandler {
    protected final AdminAreaProtectionPlugin plugin;
    
    public BaseFormHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Gets the form ID for this handler
     */
    public abstract String getFormId();
    
    /**
     * Creates a form for the specified player
     */
    public abstract FormWindow createForm(Player player);
    
    /**
     * Creates a form for the specified player and area
     */
    public FormWindow createForm(Player player, Area area) {
        // Default implementation just calls the player-only version
        return createForm(player);
    }
    
    /**
     * Handle form response
     */
    @Override
    public void handleResponse(Player player, Object response) {
        try {
            if (response == null) {
                handleClosed(player);
                return;
            }

            FormWindow window = null;
            
            // Handle window response conversion
            if (response instanceof FormWindow) {
                window = (FormWindow) response;
                if (window.wasClosed()) {
                    handleClosed(player);
                    return;
                }
                
                if (window.getResponse() instanceof FormResponseCustom) {
                    handleCustomResponse(player, (FormResponseCustom) window.getResponse());
                } else if (window.getResponse() instanceof FormResponseSimple) {
                    handleSimpleResponse(player, (FormResponseSimple) window.getResponse());
                } else if (window.getResponse() instanceof FormResponseModal) {
                    handleModalResponse(player, (FormResponseModal) window.getResponse());
                }
            } else {
                // Direct response handling (already processed by FormWindow)
                if (response instanceof FormResponseCustom) {
                    handleCustomResponse(player, (FormResponseCustom) response);
                } else if (response instanceof FormResponseSimple) {
                    handleSimpleResponse(player, (FormResponseSimple) response);
                } else if (response instanceof FormResponseModal) {
                    handleModalResponse(player, (FormResponseModal) response);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(LogLevel.CRITICAL, "Error handling form response", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
        }
    }
    
    /**
     * Legacy method to maintain compatibility with existing code
     */
    public void handleResponse(Player player, FormWindow window) {
        handleResponse(player, (Object) window);
    }
    
    /**
     * Handle form closed event
     */
    protected void handleClosed(Player player) {
        // Just clean up form data without opening the main menu
        cleanup(player);
        
        if (plugin.isDebugMode()) {
            plugin.debug("Form closed by player " + player.getName());
        }
    }
    
    /**
     * Handle when a form is cancelled
     * Default implementation just cleans up without forcing main menu
     */
    public void handleCancel(Player player) {
        cleanup(player);
        
        if (plugin.isDebugMode()) {
            plugin.debug("Form cancelled by player " + player.getName());
        }
    }
    
    /**
     * Clean up form tracking data for a player
     */
    protected void cleanup(Player player) {
        if (plugin.isDebugMode()) {
            plugin.debug("Cleaning up form data in BaseFormHandler");
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
     * Handle custom form response
     */
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        // To be implemented by subclasses
    }
    
    /**
     * Handle simple form response
     */
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        // To be implemented by subclasses
    }
    
    /**
     * Handle modal form response
     */
    protected void handleModalResponse(Player player, FormResponseModal response) {
        // To be implemented by subclasses
    }
    
    /**
     * Validate that the form ID is set correctly
     */
    protected void validateFormId() {
        if (getFormId() == null || getFormId().isEmpty()) {
            throw new IllegalStateException("Form ID must be set for " + getClass().getSimpleName());
        }
    }
    
    /**
     * Normalize permission node to ensure it has the correct prefix
     */
    protected String normalizePermissionNode(String node) {
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
     * Updates area toggle states consistently and returns the number of changes made
     * @param area The area to update
     * @param toggles Map of permission nodes to toggle states
     * @return Number of toggle states that were changed
     */
    protected int updateAreaToggles(Area area, Map<String, Boolean> toggles) {
        if (area == null || toggles == null || toggles.isEmpty()) {
            return 0;
        }
        
        int changedCount = 0;
        
        if (plugin.isDebugMode()) {
            plugin.debug("Updating " + toggles.size() + " toggle states for area: " + area.getName());
        }
        
        // Update toggles through the area manager for better consistency
        try {
            // First normalize all permission nodes - ensure we only use the full prefixed version
            Map<String, Boolean> normalizedToggles = new HashMap<>();
            for (Map.Entry<String, Boolean> entry : toggles.entrySet()) {
                String normalizedKey = normalizePermissionNode(entry.getKey());
                // Avoid adding duplicate keys with different prefixes
                if (!normalizedToggles.containsKey(normalizedKey)) {
                    normalizedToggles.put(normalizedKey, entry.getValue());
                }
            }
            
            if (plugin.isDebugMode() && normalizedToggles.size() != toggles.size()) {
                plugin.debug("Normalized " + toggles.size() + " toggles to " + normalizedToggles.size() + 
                          " after removing duplicates");
            }
            
            // CRITICAL FIX: Apply toggle states directly to the provided area object first
            // This ensures that the caller has an up-to-date area object with the changes applied
            for (Map.Entry<String, Boolean> entry : normalizedToggles.entrySet()) {
                // Important: Apply directly to the original area object
                area.setToggleState(entry.getKey(), entry.getValue());
                
                if (plugin.isDebugMode()) {
                    plugin.debug("  Applied toggle " + entry.getKey() + " = " + entry.getValue() + " to original area object");
                }
            }
            
            // Update the area using AreaManager's specialized method
            Area updatedArea = plugin.getAreaManager().updateAreaToggleStates(area, normalizedToggles);
            changedCount = normalizedToggles.size();
            
            // If successful, return the updated area - otherwise keep the original
            if (updatedArea != null) {
                // Explicitly save toggle states to ensure persistence
                updatedArea.saveToggleStates();
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Successfully updated " + changedCount + " toggle states");
                }
            } else {
                if (plugin.isDebugMode()) {
                    plugin.debug("Failed to update toggle states");
                }
                changedCount = 0;
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error updating area toggle states", e);
            changedCount = 0;
        }
        
        return changedCount;
    }
    
    /**
     * Updates potion effects and notifies the player of changes
     */
    protected void updatePotionEffectsAndNotifyPlayer(Player player, Area area, Map<String, Integer> effectStrengths, String nextFormId) {
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
            Area updatedArea = plugin.getAreaManager().updateAreaPotionEffects(area, effectStrengths);
            
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
     * Updates the area and notifies the player of changes
     * 
     * @param player The player to notify
     * @param area The area that was updated
     * @param changedSettings Number of settings that were changed
     * @param nextFormId The form ID to open next
     * @param settingType Type of settings being updated (for logging)
     */
    protected void updateAreaAndNotifyPlayer(Player player, Area area, int changedSettings, String nextFormId, String settingType) {
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
    
    /**
     * Alias for getPermissionOverrideManager() for backward compatibility.
     */
    public final adminarea.permissions.PermissionOverrideManager getOverrideManager() {
        return plugin.getPermissionOverrideManager(); // Use the main method to ensure consistency
    }
}