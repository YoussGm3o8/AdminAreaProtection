package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.form.IFormHandler;
import adminarea.managers.GuiManager;
import adminarea.constants.AdminAreaConstants;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowSimple;

public class PlayerAreaHandler implements IFormHandler {
    private final AdminAreaProtectionPlugin plugin;

    public PlayerAreaHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getFormId() {
        return AdminAreaConstants.FORM_PLAYER_MANAGE;
    }

    @Override
    public FormWindow createForm(Player player) {
        FormWindowSimple form = new FormWindowSimple("Manage Player Areas", "Select an area to manage players:");
        
        for (Area area : plugin.getAreas()) {
            form.addButton(new ElementButton(area.getName()));
        }
        
        return form;
    }

    @Override
    public void handleResponse(Player player, Object response) {
        if (!(response instanceof FormResponseSimple)) return;
        
        FormResponseSimple simpleResponse = (FormResponseSimple) response;
        int btnId = simpleResponse.getClickedButtonId();
        
        if (btnId >= 0 && btnId < plugin.getAreas().size()) {
            Area area = plugin.getAreas().get(btnId);
            GuiManager gui = new GuiManager(plugin);
            gui.openLuckPermsOverrideForm(player, area);
        }
    }
}
