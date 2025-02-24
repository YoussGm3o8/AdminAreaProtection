package adminarea.form.handlers;

import java.util.Map;
import java.util.HashMap;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowSimple;

public class DeleteConfirmHandler extends BaseFormHandler {

    public DeleteConfirmHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        validateFormId();
    }

    @Override
    public String getFormId() {
        return FormIds.DELETE_CONFIRM;
    }

    @Override
    public FormWindow createForm(Player player, Area area) {
        try {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("area", area.getName());

            FormWindowSimple form = new FormWindowSimple(
                plugin.getLanguageManager().get("gui.deleteArea.confirmTitle"),
                plugin.getLanguageManager().get("gui.deleteArea.confirmPrompt", placeholders)
            );

            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.deleteArea.buttons.confirm")));
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.deleteArea.buttons.cancel")));

            return form;
        } catch (Exception e) {
            plugin.getLogger().error("Error creating delete confirmation form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
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
            String areaName = plugin.getFormIdMap().get(player.getName() + "_editing").getFormId();
            Area area = plugin.getArea(areaName);

            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                handleCancel(player);
                return;
            }

            int buttonId = response.getClickedButtonId();
            if (buttonId == 0) { // Confirm
                plugin.getAreaManager().removeArea(area);
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("area", area.getName());
                player.sendMessage(plugin.getLanguageManager().get("success.area.delete.single", placeholders));
            }

            // Set form tracking data for main menu before opening it
            plugin.getFormIdMap().put(player.getName(), 
                new FormTrackingData(FormIds.MAIN_MENU, System.currentTimeMillis()));
            cleanup(player);
            plugin.getGuiManager().openMainMenu(player);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling delete confirmation response", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            handleCancel(player);
        }
    }

    @Override
    public FormWindow createForm(Player player) {
        return null; // Not used - requires area context
    }
}
