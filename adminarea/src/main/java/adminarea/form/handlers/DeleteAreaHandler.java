package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.form.IFormHandler;
import adminarea.constants.AdminAreaConstants;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowSimple;

import java.util.List;

public class DeleteAreaHandler extends BaseFormHandler {
    public DeleteAreaHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        validateFormId();
    }

    @Override
    public String getFormId() {
        return FormIds.DELETE_LIST;
    }

    @Override
    public FormWindow createForm(Player player) {
        try {
            FormWindowSimple form = new FormWindowSimple(
                plugin.getLanguageManager().get("gui.deleteArea.title"),
                plugin.getLanguageManager().get("gui.deleteArea.content")
            );

            List<Area> areas = plugin.getAreas();
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
                if (area.toDTO().bounds().isGlobal()) {
                    buttonText += "\n§3(Global Area)";
                }
                form.addButton(new ElementButton(buttonText));
            }

            return form;
        } catch (Exception e) {
            plugin.getLogger().error("Error creating delete area form", e);
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
                return;
            }

            Area area = areas.get(buttonId);
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                return;
            }

            // Store area being deleted and show confirmation form
            plugin.getFormIdMap().put(player.getName() + "_editing", 
                new FormTrackingData(area.getName(), System.currentTimeMillis()));
            plugin.getGuiManager().openFormById(player, FormIds.DELETE_CONFIRM, area);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling delete area response", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            handleCancel(player);
        }
    }

    @Override
    public void handleCancel(Player player) {
        // Clean up data before returning to main menu
        cleanup(player);
        
        // Set main menu as current form
        plugin.getFormIdMap().put(player.getName(), 
            new FormTrackingData(FormIds.MAIN_MENU, System.currentTimeMillis()));
            
        if (plugin.isDebugMode()) {
            plugin.debug("Delete area cancelled, returning to main menu");
        }
        
        // Open main menu directly instead of using back navigation
        plugin.getGuiManager().openMainMenu(player);
    }

    private void processAreaDeletion(Player player, Area area) {
        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.areaNotFound"));
            plugin.getGuiManager().openMainMenu(player);
            return;
        }

        try {
            plugin.removeArea(area);
            player.sendMessage(plugin.getLanguageManager().get("messages.area.deleted")
                .replace("%area%", area.getName()));
            plugin.getGuiManager().openMainMenu(player);
        } catch (Exception e) {
            plugin.getLogger().error("Error deleting area: " + area.getName(), e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.deleteFailed"));
            plugin.getGuiManager().openMainMenu(player);
        }
    }
}
