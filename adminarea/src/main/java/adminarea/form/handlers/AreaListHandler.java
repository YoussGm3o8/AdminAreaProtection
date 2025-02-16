package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.form.IFormHandler;
import adminarea.managers.GuiManager;
import adminarea.constants.AdminAreaConstants;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.form.element.ElementButton;

public class AreaListHandler implements IFormHandler {
    private final AdminAreaProtectionPlugin plugin;

    public AreaListHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getFormId() {
        return AdminAreaConstants.FORM_EDIT_LIST;  // Changed from FORM_EDIT_SELECT
    }

    @Override
    public FormWindow createForm(Player player) {
        FormWindowSimple form = new FormWindowSimple("Edit Area", "Select an area to edit:");
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
            plugin.getFormIdMap().put(player.getName() + "_editing", area.getName());
            GuiManager gui = plugin.getGuiManager(); // Use existing GuiManager instance
            gui.openEditForm(player, area);
        }
    }
}
