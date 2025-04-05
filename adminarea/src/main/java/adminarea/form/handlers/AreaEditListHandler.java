package adminarea.form.handlers;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.FormIds;
import adminarea.form.utils.FormUtils;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.form.window.FormWindowSimple;
import adminarea.data.FormTrackingData;

/**
 * Handler for displaying a list of areas for editing.
 */
public class AreaEditListHandler {
    
    private final AdminAreaProtectionPlugin plugin;
    
    public AreaEditListHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Open the area edit list form for a player
     */
    public void open(Player player) {
        try {
            FormWindowSimple form = new FormWindowSimple(
                plugin.getLanguageManager().get("gui.editArea.listtitle"),
                plugin.getLanguageManager().get("gui.editArea.content")
            );

            // Get the current areas, making sure to remove duplicates
            List<Area> areas = plugin.getAreas();
            
            // Ensure there are no duplicates in the list
            Set<String> areaNames = new HashSet<>();
            List<Area> uniqueAreas = new ArrayList<>();
            
            for (Area area : areas) {
                if (!areaNames.contains(area.getName().toLowerCase())) {
                    areaNames.add(area.getName().toLowerCase());
                    uniqueAreas.add(area);
                } else if (plugin.isDebugMode()) {
                    plugin.debug("Found duplicate area in list: " + area.getName() + " - skipping it");
                }
            }
            
            // Use the deduplicated list
            areas = uniqueAreas;
            
            if (areas.isEmpty()) {
                player.sendMessage(plugin.getLanguageManager().get("messages.area.list.empty"));
                // Clean up form tracking data and return to main menu
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
                        // Set form tracking data before reopening edit list
                        plugin.getFormIdMap().put(player.getName(),
                            new FormTrackingData(FormIds.EDIT_LIST, System.currentTimeMillis()));
                        open(player);
                        return;
                    }

                    Area area = currentAreas.get(buttonId);
                    if (area == null) {
                        player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                        // Set form tracking data before reopening edit list
                        plugin.getFormIdMap().put(player.getName(),
                            new FormTrackingData(FormIds.EDIT_LIST, System.currentTimeMillis()));
                        open(player);
                        return;
                    }
                    
                    // Load fresh area from database to prevent cache issues
                    try {
                        Area freshArea = plugin.getDatabaseManager().loadArea(area.getName());
                        if (freshArea != null) {
                            area = freshArea;
                            if (plugin.isDebugMode()) {
                                plugin.debug("Using fresh area from database for edit form: " + area.getName());
                            }
                        }
                    } catch (Exception e) {
                        if (plugin.isDebugMode()) {
                            plugin.debug("Failed to load fresh area from database: " + e.getMessage());
                        }
                        // Continue with the original area if refresh fails
                    }

                    // Store area being edited
                    plugin.getFormIdMap().put(player.getName() + "_editing", 
                        new FormTrackingData(area.getName(), System.currentTimeMillis()));
                    // Set form tracking data before opening edit area form
                    plugin.getFormIdMap().put(player.getName(),
                        new FormTrackingData(FormIds.EDIT_AREA, System.currentTimeMillis()));
                    plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);

                } catch (Exception e) {
                    plugin.getLogger().error("Error handling area edit list response", e);
                    player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
                    handleCancel(player);
                }
            }));
            
            // Show the form to the player
            player.showFormWindow(form);
            
        } catch (Exception e) {
            plugin.getLogger().error("Error creating area edit list form", e);
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
