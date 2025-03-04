package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.form.IFormHandler;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseModal;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;

import java.util.Map;

public abstract class BaseFormHandler implements IFormHandler {
    protected final AdminAreaProtectionPlugin plugin;

    public BaseFormHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    protected void validateFormId() {
        String formId = getFormId();
        if (formId == null || formId.trim().isEmpty()) {
            throw new IllegalStateException("Form handler must provide a non-null, non-empty formId");
        }
    }

    @Override
    public void handleResponse(Player player, Object response) {
        try {
            // Save current form tracking data
            var currentFormData = plugin.getFormIdMap().get(player.getName());
            var editingData = plugin.getFormIdMap().get(player.getName() + "_editing");
            
            if (plugin.isDebugMode()) {
                plugin.debug("Handling form response with tracking data:");
                if (currentFormData != null) {
                    plugin.debug("  Current form: " + currentFormData.getFormId());
                }
                if (editingData != null) {
                    plugin.debug("  Editing area: " + editingData.getFormId());
                }
            }

            // Handle null response (form closed) first
            if (response == null) {
                if (plugin.isDebugMode()) {
                    plugin.debug("Form was closed by player, cleaning up");
                }
                cleanup(player);
                return;
            }

            // Handle the response based on form type
            if (response instanceof FormResponseCustom) {
                handleCustomResponse(player, (FormResponseCustom) response);
            } else if (response instanceof FormResponseSimple) {
                handleSimpleResponse(player, (FormResponseSimple) response);
            } else if (response instanceof FormResponseModal) {
                handleModalResponse(player, (FormResponseModal) response);
            }

            // Check if we're transitioning to a new form
            var newFormData = plugin.getFormIdMap().get(player.getName());
            if (newFormData == null || newFormData.equals(currentFormData)) {
                if (plugin.isDebugMode()) {
                    plugin.debug("No form transition detected, cleaning up");
                }
                cleanup(player);
            } else if (plugin.isDebugMode()) {
                plugin.debug("Form transition detected, preserving data");
                plugin.debug("  New form: " + newFormData.getFormId());
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error handling form response", e);
            cleanup(player);
            throw e;
        }
    }

    @Override
    public void handleCancel(Player player) {
        var currentFormData = plugin.getFormIdMap().get(player.getName());
        
        // Just cleanup for all forms when cancelled/closed
        cleanup(player);
    }

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

    @Override
    public FormWindow createForm(Player player, Area area) {
        return createForm(player);
    }

    protected abstract void handleCustomResponse(Player player, FormResponseCustom response);
    protected abstract void handleSimpleResponse(Player player, FormResponseSimple response);
    protected void handleModalResponse(Player player, FormResponseModal response) {
        // Default implementation does nothing
    }

    /**
     * Standard method to update an area with changes, ensure cache invalidation, and show a consistent success message
     * 
     * @param player The player who is updating the area
     * @param area The area being updated
     * @param changedSettings Number of settings that were changed
     * @param returnFormId The form ID to return to after showing the success message
     * @param settingsType The type of settings being updated (used in debug messages)
     */
    protected void updateAreaAndNotifyPlayer(Player player, Area area, int changedSettings, String returnFormId, String settingsType) {
        if (changedSettings > 0) {
            // Save the area to ensure toggle states are updated in the database
            area.saveToggleStates();
            
            // Update area in the plugin to ensure all systems are aware of the changes
            plugin.updateArea(area);
            
            // Show success message with change count
            player.sendMessage(plugin.getLanguageManager().get("messages.area.updated",
                Map.of(
                    "area", area.getName(),
                    "count", String.valueOf(changedSettings)
                )));

            if (plugin.isDebugMode()) {
                plugin.debug("Updated " + settingsType + " settings for area " + area.getName() + " with " + changedSettings + " changes");
            }
        } else {
            // No changes were made
            player.sendMessage(plugin.getLanguageManager().get("messages.form.noChanges"));
            
            if (plugin.isDebugMode()) {
                plugin.debug("No " + settingsType + " settings were changed for area " + area.getName());
            }
        }

        // Return to specified form
        plugin.getGuiManager().openFormById(player, returnFormId, area);
    }

    /**
     * Update multiple toggle states in an area while tracking changes
     * 
     * @param area The area to update
     * @param toggleChanges Map of permission nodes to their new values
     * @return Number of settings that were actually changed
     */
    protected int updateAreaToggles(Area area, Map<String, Boolean> toggleChanges) {
        int changedSettings = 0;

        for (Map.Entry<String, Boolean> entry : toggleChanges.entrySet()) {
            String permissionNode = normalizePermissionNode(entry.getKey());
            boolean newValue = entry.getValue();
            boolean currentValue = area.getToggleState(permissionNode);
            
            // Only update if value changed
            if (currentValue != newValue) {
                area.setToggleState(permissionNode, newValue);
                changedSettings++;
                
                if (plugin.isDebugMode()) {
                    plugin.debug("  Changed toggle " + permissionNode + " from " + currentValue + " to " + newValue);
                }
            }
        }

        return changedSettings;
    }

    /**
     * Normalizes a permission node to ensure it has the correct prefix
     * @param permissionNode The permission node to normalize
     * @return The normalized permission node
     */
    protected String normalizePermissionNode(String permissionNode) {
        if (permissionNode == null || permissionNode.isEmpty()) {
            return "gui.permissions.toggles.default";
        }
        
        // If already has the prefix, return as is
        if (permissionNode.startsWith("gui.permissions.toggles.")) {
            return permissionNode;
        }
        
        // Add the prefix
        return "gui.permissions.toggles." + permissionNode;
    }

    /**
     * Update potion effect settings and notify player
     *
     * @param player The player who is updating the area
     * @param area The area being updated
     * @param effectStrengths Map of effect permission nodes to their strength values
     * @param returnFormId The form ID to return to after showing the success message
     */
    protected void updatePotionEffectsAndNotifyPlayer(Player player, Area area, Map<String, Integer> effectStrengths, String returnFormId) {
        if (effectStrengths.isEmpty()) {
            // No changes were made
            player.sendMessage(plugin.getLanguageManager().get("messages.form.noChanges"));
            plugin.getGuiManager().openFormById(player, returnFormId, area);
            return;
        }
        
        // Update the area with all potion effect changes in one operation
        int changedSettings = effectStrengths.size() / 2; // Each effect has both toggle and strength nodes
        Area updatedArea = plugin.getAreaManager().updateAreaPotionEffects(area, effectStrengths);
        
        // Show success message with change count
        player.sendMessage(plugin.getLanguageManager().get("messages.area.updated",
            Map.of(
                "area", area.getName(),
                "count", String.valueOf(changedSettings)
            )));

        if (plugin.isDebugMode()) {
            plugin.debug("Updated potion effect settings for area " + area.getName() + " with " + changedSettings + " changes");
            plugin.debug("Updated potion effects: " + updatedArea.toDTO().potionEffects());
        }

        // Return to specified form
        plugin.getGuiManager().openFormById(player, returnFormId, area);
    }
}