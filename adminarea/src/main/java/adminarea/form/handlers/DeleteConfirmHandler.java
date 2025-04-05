package adminarea.form.handlers;

import java.util.Map;
import java.util.HashMap;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.form.utils.FormUtils;
import cn.nukkit.Player;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.form.window.FormWindowModal;

/**
 * Modal form for confirming area deletion.
 */
public class DeleteConfirmHandler {
    
    private final AdminAreaProtectionPlugin plugin;
    
    public DeleteConfirmHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Open the delete confirmation form for a player and area
     */
    public void open(Player player, Area area) {
        try {
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                return;
            }
            
            // Store the area being edited in the form tracking data
            plugin.getFormIdMap().put(player.getName() + "_editing", 
                new FormTrackingData(area.getName(), System.currentTimeMillis()));
            
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("area", area.getName());

            // Create modal form with confirm/cancel buttons
            FormWindowModal form = new FormWindowModal(
                plugin.getLanguageManager().get("gui.deleteArea.confirmTitle"),
                plugin.getLanguageManager().get("gui.deleteArea.confirmPrompt", placeholders),
                plugin.getLanguageManager().get("gui.deleteArea.buttons.confirm"),
                plugin.getLanguageManager().get("gui.deleteArea.buttons.cancel")
            );

            // Add response handler
            form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
                if (form.wasClosed()) {
                    handleCancel(player);
                    return;
                }
                
                try {
                    String areaName = plugin.getFormIdMap().get(player.getName() + "_editing").getFormId();
                    Area targetArea = plugin.getArea(areaName);

                    if (targetArea == null) {
                        player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                        handleCancel(player);
                        return;
                    }

                    int buttonId = form.getResponse().getClickedButtonId();
                    if (buttonId == 0) { // Confirm button
                        // Check deletion permission
                        if (!player.hasPermission("adminarea.area.delete")) {
                            player.sendMessage(plugin.getLanguageManager().get("messages.permissions.noPermission"));
                            handleCancel(player);
                            return;
                        }
                        
                        String worldName = targetArea.getWorld();
                        
                        // Delete area from manager
                        plugin.getAreaManager().removeArea(targetArea);
                        
                        // Remove area title configuration from config
                        plugin.getConfigManager().remove("areaTitles." + areaName);
                        plugin.getConfigManager().save();
                        
                        // Invalidate permission cache for this area
                        plugin.getPermissionOverrideManager().getPermissionChecker().invalidateCache(areaName);
                        
                        // Show success message with area name and world
                        Map<String, String> resultPlaceholders = Map.of(
                            "area", areaName,
                            "world", worldName
                        );
                        player.sendMessage(plugin.getLanguageManager().get("success.area.delete.single", resultPlaceholders));
                        
                        if (plugin.isDebugMode()) {
                            plugin.debug("Deleted area: " + areaName + " from world: " + worldName);
                        }
                    } else {
                        // Cancel button was clicked
                        if (plugin.isDebugMode()) {
                            plugin.debug("Area deletion cancelled by user for: " + targetArea.getName());
                        }
                    }

                    // Clean up resources and return to main menu
                    plugin.getFormIdMap().remove(player.getName() + "_editing");
                    plugin.getFormIdMap().put(player.getName(),
                        new FormTrackingData(FormIds.MAIN_MENU, System.currentTimeMillis()));
                    FormUtils.cleanup(player);
                    plugin.getGuiManager().openMainMenu(player);

                } catch (Exception e) {
                    plugin.getLogger().error("Error handling delete confirmation response", e);
                    player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
                    handleCancel(player);
                }
            }));
            
            // Show the form
            player.showFormWindow(form);
        } catch (Exception e) {
            plugin.getLogger().error("Error creating delete confirmation form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
        }
    }
    
    /**
     * Handle when the form is cancelled
     */
    private void handleCancel(Player player) {
        FormUtils.cleanup(player);
        plugin.getGuiManager().openMainMenu(player);
    }
}
