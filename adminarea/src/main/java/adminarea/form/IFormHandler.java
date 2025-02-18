package adminarea.form;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.data.FormTrackingData;
import cn.nukkit.Player;
import cn.nukkit.form.window.FormWindow;

public interface IFormHandler {
    String getFormId();
    FormWindow createForm(Player player);
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

            // Get form tracking data to determine previous form
            FormTrackingData trackingData = plugin.getFormIdMap().get(player.getName() + "_editing");
            if (trackingData != null) {
                Area area = plugin.getArea(trackingData.getFormId());
                if (area != null) {
                    // Return to edit form if we have area context
                    plugin.getGuiManager().openEditForm(player, area);
                    return;
                }
            }

            // Fallback to main menu if no context available
            plugin.getGuiManager().openMainMenu(player);
            
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.debug("Form cancelled by player: " + player.getName());
            }

        } catch (Exception e) {
            // Log the error first
            AdminAreaProtectionPlugin plugin = player.getServer().getPluginManager()
                .getPlugin("AdminAreaProtection") instanceof AdminAreaProtectionPlugin p ? p : null;
                
            if (plugin != null) {
                plugin.getLogger().error("Error handling form cancel", e);
                player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
                plugin.getGuiManager().openMainMenu(player);
            } else {
                // Ultimate fallback if plugin instance is not available
                player.sendMessage("§cAn error occurred. Please try again.");
            }
        }
    }
}
