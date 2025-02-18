package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.FormIds;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;

import java.util.List;

public class ListAreasHandler extends BaseFormHandler {

    public ListAreasHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getFormId() {
        return FormIds.AREA_LIST;
    }

    @Override
    public FormWindow createForm(Player player) {
        List<Area> areas = plugin.getAreas();
        if (areas.isEmpty()) {
            player.sendMessage(plugin.getLanguageManager().get("messages.area.list.empty"));
            return null;
        }
        
        String title = plugin.getLanguageManager().get("gui.areaList.title");
        String content = plugin.getLanguageManager().get("gui.areaList.content");
        return plugin.getFormFactory().createAreaListForm(title, content, areas);
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        throw new UnsupportedOperationException("Area list only uses simple form");
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        int selection = response.getClickedButtonId();
        List<Area> areas = plugin.getAreas();
        
        if (selection < 0 || selection >= areas.size()) {
            // Back button clicked
            plugin.getGuiManager().openMainMenu(player);
            return;
        }

        Area selectedArea = areas.get(selection);
        plugin.getGuiManager().openEditForm(player, selectedArea);
    }
}
