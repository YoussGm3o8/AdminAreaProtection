package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.form.IFormHandler;
import adminarea.constants.AdminAreaConstants;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowSimple;

public class DeleteAreaHandler implements IFormHandler {
    private final AdminAreaProtectionPlugin plugin;

    public DeleteAreaHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getFormId() {
        return AdminAreaConstants.FORM_DELETE_SELECT;
    }

    @Override
    public FormWindow createForm(Player player) {
        FormWindowSimple form = new FormWindowSimple("Delete Area", "Select an area to delete:");
        
        if (plugin.getAreas().isEmpty()) {
            form.setContent("There are no areas to delete.");
        } else {
            for (Area area : plugin.getAreas()) {
                form.addButton(new ElementButton(area.getName()));
            }
        }
        
        return form;
    }

    @Override
    public void handleResponse(Player player, Object response) {
        if (!(response instanceof FormResponseSimple)) return;
        
        FormResponseSimple simpleResponse = (FormResponseSimple) response;
        if (simpleResponse.getClickedButtonId() == -1) return;
        
        int btnId = simpleResponse.getClickedButtonId();
        if (btnId >= 0 && btnId < plugin.getAreas().size()) {
            Area area = plugin.getAreas().get(btnId);
            String areaName = area.getName();
            
            try {
                plugin.removeArea(area);
                player.sendMessage(String.format(AdminAreaConstants.MSG_AREA_DELETED, areaName));
                
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().debug("Area deleted: " + areaName + " by player: " + player.getName());
                }
            } catch (Exception e) {
                player.sendMessage("§cFailed to delete area: " + e.getMessage());
                if (plugin.getConfigManager().isDebugEnabled()) {
                    e.printStackTrace();
                }
            }
        }
    }
}
