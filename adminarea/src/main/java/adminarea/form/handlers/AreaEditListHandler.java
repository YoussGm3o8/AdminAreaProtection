package adminarea.form.handlers;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.FormIds;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowSimple;
import adminarea.data.FormTrackingData;

public class AreaEditListHandler extends BaseFormHandler {

    public AreaEditListHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        validateFormId();
    }

    @Override
    public String getFormId() {
        return FormIds.EDIT_LIST;
    }

    @Override
    public FormWindow createForm(Player player) {
        try {
            FormWindowSimple form = new FormWindowSimple(
                plugin.getLanguageManager().get("gui.editArea.listtitle"),
                plugin.getLanguageManager().get("gui.editArea.content")
            );

            // IMPORTANT: Rather than using the nuclear reloadAllAreasFromDatabase method 
            // which clears all areas first (potentially leading to data loss),
            // just refresh the area list from the database without clearing anything
            if (plugin.isDebugMode()) {
                plugin.debug("Safely refreshing area list from database");
            }
            
            // Get the current areas
            List<Area> areas = plugin.getAreas();
            
            // As an additional safety check, make sure there are no duplicates in the list
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
                // Clean up form tracking data when there are no areas
                cleanup(player);
                // Set form tracking data for main menu
                plugin.getFormIdMap().put(player.getName(), 
                    new FormTrackingData(FormIds.MAIN_MENU, System.currentTimeMillis()));
                // Reopen main menu
                plugin.getGuiManager().openMainMenu(player);
                return null;
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

            return form;
        } catch (Exception e) {
            plugin.getLogger().error("Error creating area edit list form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            cleanup(player);
            return null;
        }
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        // Not used for simple form
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        if (response == null) {
            handleCancel(player);
            return;
        }

        try {
            int buttonId = response.getClickedButtonId();
            List<Area> areas = plugin.getAreas();
            
            if (buttonId < 0 || buttonId >= areas.size()) {
                player.sendMessage(plugin.getLanguageManager().get("validation.form.error.generic"));
                // Set form tracking data before reopening edit list
                plugin.getFormIdMap().put(player.getName(),
                    new FormTrackingData(FormIds.EDIT_LIST, System.currentTimeMillis()));
                plugin.getGuiManager().openEditListForm(player);
                return;
            }

            Area area = areas.get(buttonId);
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                // Set form tracking data before reopening edit list
                plugin.getFormIdMap().put(player.getName(),
                    new FormTrackingData(FormIds.EDIT_LIST, System.currentTimeMillis()));
                plugin.getGuiManager().openEditListForm(player);
                return;
            }
            
            // IMPORTANT: Load fresh area from database to prevent cache issues
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
    }
}
