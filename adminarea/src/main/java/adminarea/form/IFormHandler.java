package adminarea.form;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import cn.nukkit.Player;
import cn.nukkit.form.window.FormWindow;

public interface IFormHandler {
    String getFormId();
    
    /**
     * Creates a form for a player without area context
     */
    FormWindow createForm(Player player);

    /**
     * Creates a form for a player with area context
     */
    FormWindow createForm(Player player, Area area);
    
    void handleResponse(Player player, Object response);
    
    default boolean canHandle(String formId) {
        return getFormId().equals(formId);
    }
    
    default void handleCancel(Player player) {
        try {
            AdminAreaProtectionPlugin plugin = (AdminAreaProtectionPlugin) player.getServer()
                .getPluginManager()
                .getPlugin("AdminAreaProtection");
                
            if (plugin == null) {
                throw new IllegalStateException("Plugin not found");
            }

            // Get area context 
            FormTrackingData trackingData = plugin.getFormIdMap().get(player.getName() + "_editing");
            if (trackingData != null) {
                Area area = plugin.getArea(trackingData.getFormId());
                if (area != null && !getFormId().equals(FormIds.MAIN_MENU)) {
                    // Return to main menu if not already there
                    plugin.getGuiManager().openMainMenu(player);
                }
            }
            
            // Log cancel
            if (plugin.isDebugMode()) {
                plugin.debug("Form cancelled by player: " + player.getName());
            }

        } catch (Exception e) {
            // Check for validation errors first
            if (e instanceof RuntimeException && e.getMessage() != null && 
                e.getMessage().contains("_validation_error_already_shown")) {
                // Skip showing generic error for validation errors
                return;
            }
            
            // Handle errors
            AdminAreaProtectionPlugin plugin = player.getServer().getPluginManager()
                .getPlugin("AdminAreaProtection") instanceof AdminAreaProtectionPlugin p ? p : null;
                
            if (plugin != null) {
                plugin.getLogger().error("Error handling form cancel", e);
                player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            } else {
                player.sendMessage("§cAn error occurred. Please try again.");
            }
        }
    }
}
