package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.constants.FormIds;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.window.FormWindowCustom;

import java.util.HashMap;
import java.util.Map;

/**
 * Handler for plugin settings
 */
public class PluginSettingsHandler {
    // Constants for validation
    private static final int MIN_CACHE_EXPIRY = 1;
    private static final int MAX_CACHE_EXPIRY = 1440; // 24 hours
    private static final int MIN_UNDO_HISTORY = 1;
    private static final int MAX_UNDO_HISTORY = 100;
    private static final int MIN_UPDATE_INTERVAL = 60; // 1 minute
    private static final int MAX_UPDATE_INTERVAL = 3600; // 1 hour
    
    private final AdminAreaProtectionPlugin plugin;
    
    /**
     * Constructor
     * 
     * @param plugin Plugin instance
     */
    public PluginSettingsHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Open the plugin settings form for a player
     * 
     * @param player Player to open form for
     */
    public void open(Player player) {
        try {
            FormWindowCustom form = new FormWindowCustom("Plugin Settings");

            // General Settings
            form.addElement(new ElementLabel("General Settings"));
            form.addElement(new ElementToggle("Debug Mode", 
                    plugin.getConfigManager().isDebugEnabled()));
            form.addElement(new ElementToggle("Debug Stack Traces", 
                    plugin.getConfigManager().getBoolean("debugStackTraces", false)));
            form.addElement(new ElementToggle("Enable Messages", 
                    plugin.getConfigManager().getBoolean("enableMessages", true)));

            // Area Settings
            form.addElement(new ElementLabel("Area Settings"));
            form.addElement(new ElementToggle("Use Most Restrictive Merge", 
                    plugin.getConfigManager().getBoolean("areaSettings.useMostRestrictiveMerge", true)));

            // Tool Settings
            form.addElement(new ElementLabel("Tool Settings"));
            form.addElement(new ElementInput("Wand Item Type", "Item ID", 
                    String.valueOf(plugin.getConfigManager().getWandItemType())));
            form.addElement(new ElementToggle("Particle Visualization", 
                    plugin.getConfigManager().isParticleVisualizationEnabled()));
            form.addElement(new ElementInput("Visualization Duration", "Duration in seconds", 
                    String.valueOf(plugin.getConfigManager().getVisualizationDuration())));
            form.addElement(new ElementInput("Selection Cooldown", "Cooldown in seconds", 
                    String.valueOf(plugin.getConfigManager().getSelectionCooldown())));

            // Cache Settings
            form.addElement(new ElementLabel("Cache Settings"));
            form.addElement(new ElementInput("Cache Expiry Time", "Time in minutes", 
                    String.valueOf(plugin.getConfigManager().getCacheExpiryMinutes())));
            form.addElement(new ElementInput("Undo History Size", "Number of entries", 
                    String.valueOf(plugin.getConfigManager().getUndoHistorySize())));

            // Integrations
            form.addElement(new ElementLabel("Integrations"));
            form.addElement(new ElementToggle("LuckPerms Integration", 
                    plugin.getConfigManager().getBoolean("luckperms.enabled", true)));
            form.addElement(new ElementToggle("Inherit LuckPerms Permissions", 
                    plugin.getConfigManager().getBoolean("luckperms.inheritPermissions", true)));
            form.addElement(new ElementInput("LuckPerms Update Interval", "Interval in seconds", 
                    String.valueOf(plugin.getConfigManager().getInt("luckperms.updateInterval", 300))));

            player.showFormWindow(form);
        } catch (Exception e) {
            plugin.getLogger().error("Error creating plugin settings form: " + e.getMessage());
            e.printStackTrace();
            player.sendMessage("§cAn error occurred while creating the plugin settings form.");
            plugin.getFormModuleManager().openMainMenu(player);
        }
    }
    
    /**
     * Handle the response from the plugin settings form
     *
     * @param player   Player who submitted the form
     * @param response The form response
     */
    public void handleResponse(Player player, Object response) {
        if (response == null) {
            // Handle cancellation
            plugin.getFormModuleManager().openMainMenu(player);
            return;
        }
        
        try {
            FormResponseCustom formResponse = (FormResponseCustom) response;
            
            // General Settings
            boolean debugEnabled = formResponse.getToggleResponse(1);
            plugin.getConfigManager().set("debug", debugEnabled);
            plugin.setDebugMode(debugEnabled);
            
            plugin.getConfigManager().set("debugStackTraces", formResponse.getToggleResponse(2));
            plugin.getConfigManager().set("enableMessages", formResponse.getToggleResponse(3));
            
            // Area Settings
            plugin.getConfigManager().set("areaSettings.useMostRestrictiveMerge", formResponse.getToggleResponse(5));
            
            // Tool Settings
            String wandItemTypeStr = formResponse.getInputResponse(7);
            try {
                int wandItemType = Integer.parseInt(wandItemTypeStr);
                plugin.getConfigManager().set("wandItemType", wandItemType);
            } catch (NumberFormatException e) {
                player.sendMessage("§cInvalid wand item type: " + wandItemTypeStr);
                open(player);
                return;
            }
            
            // Handle particle visualization settings
            Map<String, Object> particleSection = new HashMap<>();
            particleSection.put("enabled", formResponse.getToggleResponse(8));
            
            String durationStr = formResponse.getInputResponse(9);
            try {
                int duration = Integer.parseInt(durationStr);
                particleSection.put("duration", duration);
            } catch (NumberFormatException e) {
                player.sendMessage("§cInvalid visualization duration: " + durationStr);
                open(player);
                return;
            }
            
            plugin.getConfigManager().set("particleVisualization", particleSection);
            
            String cooldownStr = formResponse.getInputResponse(10);
            try {
                int cooldown = Integer.parseInt(cooldownStr);
                plugin.getConfigManager().set("selectionCooldown", cooldown);
            } catch (NumberFormatException e) {
                player.sendMessage("§cInvalid selection cooldown: " + cooldownStr);
                open(player);
                return;
            }
            
            // Cache Settings
            String cacheExpiryStr = formResponse.getInputResponse(12);
            try {
                int cacheExpiry = Integer.parseInt(cacheExpiryStr);
                validateRange("Cache expiry", cacheExpiry, MIN_CACHE_EXPIRY, MAX_CACHE_EXPIRY);
                plugin.getConfigManager().set("cacheExpiryMinutes", cacheExpiry);
            } catch (NumberFormatException e) {
                player.sendMessage("§cInvalid cache expiry: " + cacheExpiryStr);
                open(player);
                return;
            } catch (IllegalArgumentException e) {
                player.sendMessage("§c" + e.getMessage());
                open(player);
                return;
            }
            
            String undoHistoryStr = formResponse.getInputResponse(13);
            try {
                int undoHistory = Integer.parseInt(undoHistoryStr);
                validateRange("Undo history", undoHistory, MIN_UNDO_HISTORY, MAX_UNDO_HISTORY);
                plugin.getConfigManager().set("undoHistorySize", undoHistory);
            } catch (NumberFormatException e) {
                player.sendMessage("§cInvalid undo history size: " + undoHistoryStr);
                open(player);
                return;
            } catch (IllegalArgumentException e) {
                player.sendMessage("§c" + e.getMessage());
                open(player);
                return;
            }
            
            // Integrations
            plugin.getConfigManager().set("luckperms.enabled", formResponse.getToggleResponse(15));
            plugin.getConfigManager().set("luckperms.inheritPermissions", formResponse.getToggleResponse(16));
            
            String updateIntervalStr = formResponse.getInputResponse(17);
            try {
                int updateInterval = Integer.parseInt(updateIntervalStr);
                validateRange("Update interval", updateInterval, MIN_UPDATE_INTERVAL, MAX_UPDATE_INTERVAL);
                plugin.getConfigManager().set("luckperms.updateInterval", updateInterval);
            } catch (NumberFormatException e) {
                player.sendMessage("§cInvalid update interval: " + updateIntervalStr);
                open(player);
                return;
            } catch (IllegalArgumentException e) {
                player.sendMessage("§c" + e.getMessage());
                open(player);
                return;
            }
            
            // Save and notify
            plugin.getConfigManager().getConfig().save();
            player.sendMessage("§aPlugin settings updated successfully!");
            
            // Return to main menu
            plugin.getFormModuleManager().openMainMenu(player);
        } catch (Exception e) {
            plugin.getLogger().error("Error handling plugin settings form: " + e.getMessage());
            e.printStackTrace();
            player.sendMessage("§cAn error occurred while processing the form.");
            plugin.getFormModuleManager().openMainMenu(player);
        }
    }
    
    /**
     * Validate a value is within a range
     * 
     * @param field Field name for error message
     * @param value Value to validate
     * @param min   Minimum allowed value
     * @param max   Maximum allowed value
     * @throws IllegalArgumentException if the value is out of range
     */
    private void validateRange(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max + 
                    ", got " + value);
        }
    }
}
