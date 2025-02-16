package adminarea.interfaces;

import adminarea.area.Area;
import cn.nukkit.Player;

public interface IGuiManager {
    void openMainMenu(Player player);
    void openCreateForm(Player player);
    void openEditForm(Player player, Area area);
    void openEditList(Player player);
    void openDeleteList(Player player);
    void openPlayerAreaManagement(Player player);
}
