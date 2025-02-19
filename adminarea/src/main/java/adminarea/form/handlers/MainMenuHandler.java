package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.constants.FormIds;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;

public class MainMenuHandler extends BaseFormHandler {

    public MainMenuHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getFormId() {
        return FormIds.MAIN_MENU;
    }

    @Override
    public FormWindow createForm(Player player) {
        return plugin.getFormFactory().createMainMenu();
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        throw new UnsupportedOperationException("Main menu only uses simple form");
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        int buttonId = response.getClickedButtonId();
        
        switch (buttonId) {
            case 0 -> // Create Area
                plugin.getGuiManager().openCreateForm(player);
            case 1 -> // Edit Area 
                plugin.getGuiManager().openEditList(player);
            case 2 -> // Delete Area
                plugin.getGuiManager().openDeleteList(player);
            case 3 -> // List Areas
                plugin.getGuiManager().openListForm(player);
            case 4 -> { // Permission Settings (only visible if LuckPerms enabled)
                if (plugin.isLuckPermsEnabled()) {
                    plugin.getGuiManager().openEditList(player); // This will lead to area selection for permission editing
                }
            }
            default -> plugin.debug("Invalid button ID: " + buttonId);
        }
    }
}
