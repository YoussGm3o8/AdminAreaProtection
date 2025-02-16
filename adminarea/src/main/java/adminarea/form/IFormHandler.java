package adminarea.form;

import cn.nukkit.Player;
import cn.nukkit.form.window.FormWindow;

public interface IFormHandler {
    String getFormId();
    FormWindow createForm(Player player);
    void handleResponse(Player player, Object response);
}
