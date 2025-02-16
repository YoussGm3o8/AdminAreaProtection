package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.constants.AdminAreaConstants;
import adminarea.form.IFormHandler;
import adminarea.managers.GuiManager;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowSimple;

public class MainMenuHandler implements IFormHandler {
    private final AdminAreaProtectionPlugin plugin;

    public MainMenuHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getFormId() {
        return AdminAreaConstants.FORM_MAIN_MENU;
    }

    @Override
    public FormWindow createForm(Player player) {
        FormWindowSimple form = new FormWindowSimple(AdminAreaConstants.TITLE_MAIN_MENU, "");
        form.addButton(new ElementButton("Create Area"));
        form.addButton(new ElementButton("Edit Area"));
        form.addButton(new ElementButton("Delete Area"));
        form.addButton(new ElementButton("Manage Player Permissions"));
        return form;
    }

    @Override
    public void handleResponse(Player player, Object response) {
        if (!(response instanceof FormResponseSimple)) return;
        
        FormResponseSimple simpleResponse = (FormResponseSimple) response;
        int btnId = simpleResponse.getClickedButtonId();
        GuiManager gui = new GuiManager(plugin);

        switch (btnId) {
            case 0: // Create
                gui.openCreateForm(player);
                break;
            case 1: // Edit
                gui.openEditList(player);
                break;
            case 2: // Delete
                gui.openDeleteList(player);
                break;
            case 3: // Player Areas (Admin only)
                if (player.hasPermission("adminarea.admin.manageplayerareas")) {
                    gui.openPlayerAreaManagement(player);
                }
                break;
        }
    }
}
