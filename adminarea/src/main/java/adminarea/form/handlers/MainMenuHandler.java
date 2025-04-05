package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.form.utils.FormUtils;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.form.window.FormWindowSimple;

/**
 * Handler for the main menu form.
 */
public class MainMenuHandler {
    
    private final AdminAreaProtectionPlugin plugin;
    
    public MainMenuHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Open the main menu form for a player
     */
    public void open(Player player) {
        try {
            FormWindowSimple form = new FormWindowSimple(
                plugin.getLanguageManager().get("gui.mainMenu.title"),
                plugin.getLanguageManager().get("gui.mainMenu.content")
            );

            // Add buttons
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.mainMenu.buttons.createArea")));
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.mainMenu.buttons.editArea")));
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.mainMenu.buttons.deleteArea")));
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.mainMenu.buttons.pluginSettings")));

            // Add response handler
            form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
                if (form.wasClosed()) {
                    FormUtils.cleanup(player);
                    return;
                }
                
                try {
                    int buttonId = form.getResponse().getClickedButtonId();
                    switch (buttonId) {
                        case 0: // Create Area
                            plugin.getFormIdMap().put(player.getName(), 
                                new FormTrackingData(FormIds.CREATE_AREA, System.currentTimeMillis()));
                            plugin.getGuiManager().openFormById(player, FormIds.CREATE_AREA, null);
                            break;
                        case 1: // Edit Area
                            plugin.getFormIdMap().put(player.getName(), 
                                new FormTrackingData(FormIds.EDIT_LIST, System.currentTimeMillis()));
                            plugin.getGuiManager().openFormById(player, FormIds.EDIT_LIST, null);
                            break;
                        case 2: // Delete Area
                            plugin.getFormIdMap().put(player.getName(), 
                                new FormTrackingData(FormIds.DELETE_LIST, System.currentTimeMillis()));
                            plugin.getGuiManager().openFormById(player, FormIds.DELETE_LIST, null);
                            break;
                        case 3: // Plugin Settings
                            plugin.getFormIdMap().put(player.getName(), 
                                new FormTrackingData(FormIds.PLUGIN_SETTINGS, System.currentTimeMillis()));
                            plugin.getGuiManager().openFormById(player, FormIds.PLUGIN_SETTINGS, null);
                            break;
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Error handling main menu response", e);
                    player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
                    FormUtils.cleanup(player);
                }
            }));
            
            // Show the form
            player.showFormWindow(form);
        } catch (Exception e) {
            plugin.getLogger().error("Error creating main menu form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
        }
    }
}
