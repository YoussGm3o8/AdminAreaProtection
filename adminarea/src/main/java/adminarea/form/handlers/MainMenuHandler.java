package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowSimple;

public class MainMenuHandler extends BaseFormHandler {

    public MainMenuHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        validateFormId();
    }

    @Override
    public String getFormId() {
        return FormIds.MAIN_MENU;
    }

    @Override
    public FormWindow createForm(Player player) {
        try {
            FormWindowSimple form = new FormWindowSimple(
                plugin.getLanguageManager().get("gui.mainMenu.title"),
                plugin.getLanguageManager().get("gui.mainMenu.content")
            );

            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.mainMenu.buttons.createArea")));
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.mainMenu.buttons.editArea")));
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.mainMenu.buttons.deleteArea")));
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.mainMenu.buttons.pluginSettings")));

            return form;
        } catch (Exception e) {
            plugin.getLogger().error("Error creating main menu form", e);
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
            // Just cleanup and return for main menu closure
            cleanup(player);
            return;
        }

        try {
            int button = response.getClickedButtonId();
            switch (button) {
                case 0:
                    plugin.getFormIdMap().put(player.getName(), 
                        new FormTrackingData(FormIds.CREATE_AREA, System.currentTimeMillis()));
                    plugin.getGuiManager().openFormById(player, FormIds.CREATE_AREA, null);
                    break;
                case 1:
                    plugin.getFormIdMap().put(player.getName(), 
                        new FormTrackingData(FormIds.EDIT_LIST, System.currentTimeMillis()));
                    plugin.getGuiManager().openFormById(player, FormIds.EDIT_LIST, null);
                    break;
                case 2:
                    plugin.getFormIdMap().put(player.getName(), 
                        new FormTrackingData(FormIds.DELETE_LIST, System.currentTimeMillis()));
                    plugin.getGuiManager().openFormById(player, FormIds.DELETE_LIST, null);
                    break;
                case 3:
                    plugin.getFormIdMap().put(player.getName(), 
                        new FormTrackingData(FormIds.PLUGIN_SETTINGS, System.currentTimeMillis()));
                    plugin.getGuiManager().openFormById(player, FormIds.PLUGIN_SETTINGS, null);
                    break;
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error handling main menu response", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            cleanup(player);
        }
    }

    @Override
    public void handleCancel(Player player) {
        // Just cleanup for main menu - don't reopen anything
        cleanup(player);
    }
}
