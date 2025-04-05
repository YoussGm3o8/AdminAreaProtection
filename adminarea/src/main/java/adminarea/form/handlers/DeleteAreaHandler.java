package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.form.utils.FormUtils;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.form.window.FormWindowSimple;

import java.util.List;
import java.util.Map;

/**
 * Handler for the delete area list form.
 */
public class DeleteAreaHandler {
    
    private final AdminAreaProtectionPlugin plugin;
    
    public DeleteAreaHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Open the delete area list form for a player
     */
    public void open(Player player) {
        try {
            FormWindowSimple form = new FormWindowSimple(
                plugin.getLanguageManager().get("gui.deleteArea.title"),
                plugin.getLanguageManager().get("gui.deleteArea.content")
            );
            
            // Get the current areas and ensure they're unique to prevent duplicates
            List<Area> areas = plugin.getAreas();
            
            // Deduplicate areas by name
            java.util.Set<String> areaNames = new java.util.HashSet<>();
            java.util.List<Area> uniqueAreas = new java.util.ArrayList<>();
            
            for (Area area : areas) {
                if (!areaNames.contains(area.getName().toLowerCase())) {
                    areaNames.add(area.getName().toLowerCase());
                    uniqueAreas.add(area);
                } else if (plugin.isDebugMode()) {
                    plugin.debug("Found duplicate area in delete list: " + area.getName() + " - skipping it");
                }
            }
            
            // Use the deduplicated list
            areas = uniqueAreas;
            
            if (areas.isEmpty()) {
                player.sendMessage(plugin.getLanguageManager().get("messages.area.list.empty"));
                // Clean up form tracking data when there are no areas
                FormUtils.cleanup(player);
                // Set form tracking data for main menu
                plugin.getFormIdMap().put(player.getName(), 
                    new FormTrackingData(FormIds.MAIN_MENU, System.currentTimeMillis()));
                // Reopen main menu
                plugin.getGuiManager().openMainMenu(player);
                return;
            }

            // Add buttons for each area, with special formatting for global areas
            for (Area area : areas) {
                String buttonText = area.getName();
                String worldName = area.getWorld().length() > 20 ? area.getWorld().substring(0, 17) + "..." : area.getWorld();
                if (area.toDTO().bounds().isGlobal()) {
                    buttonText += "\n§3(Global - " + worldName + ")";
                } else {
                    buttonText += "\n§8(World - " + worldName + ")";
                }
                form.addButton(new ElementButton(buttonText));
            }
            
            // Add response handler
            form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
                if (form.wasClosed()) {
                    handleCancel(player);
                    return;
                }
                
                try {
                    int buttonId = form.getResponse().getClickedButtonId();
                    List<Area> currentAreas = plugin.getAreas();
                    
                    if (buttonId < 0 || buttonId >= currentAreas.size()) {
                        player.sendMessage(plugin.getLanguageManager().get("validation.form.error.generic"));
                        return;
                    }

                    Area area = currentAreas.get(buttonId);
                    if (area == null) {
                        player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                        return;
                    }

                    // Store area being deleted and show confirmation form
                    plugin.getFormIdMap().put(player.getName() + "_editing", 
                        new FormTrackingData(area.getName(), System.currentTimeMillis()));
                    
                    // Open the delete confirmation form
                    DeleteConfirmHandler confirmHandler = new DeleteConfirmHandler(plugin);
                    confirmHandler.open(player, area);

                } catch (Exception e) {
                    plugin.getLogger().error("Error handling delete area response", e);
                    player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
                    handleCancel(player);
                }
            }));
            
            // Show the form
            player.showFormWindow(form);
            
        } catch (Exception e) {
            plugin.getLogger().error("Error creating delete area form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            FormUtils.cleanup(player);
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
