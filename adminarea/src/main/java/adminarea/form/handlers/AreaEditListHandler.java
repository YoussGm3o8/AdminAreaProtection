package adminarea.form.handlers;

import java.util.List;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.AdminAreaConstants;
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
                    buttonText += "\n§3(Global Area - " + area.getWorld() + ")";
                } else {
                    buttonText += "\n§7(World: " + area.getWorld() + ")";
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
