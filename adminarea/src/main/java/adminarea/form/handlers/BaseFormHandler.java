package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.form.IFormHandler;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponse;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;

public abstract class BaseFormHandler implements IFormHandler {
    protected final AdminAreaProtectionPlugin plugin;

    public BaseFormHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handleResponse(Player player, Object response) {
        if (player == null || response == null) {
            // Navigate back to a safe place if needed
            plugin.getGuiManager().openMainMenu(player);
            return;
        }
        try {
            if (response instanceof FormResponseCustom) {
                handleCustomResponse(player, (FormResponseCustom) response);
            } else if (response instanceof FormResponseSimple) {
                handleSimpleResponse(player, (FormResponseSimple) response);
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error handling form response", e);
            player.sendMessage("§cAn error occurred while processing your input.");
            plugin.getGuiManager().openMainMenu(player);
        }
    }

    @Override
    public FormWindow createForm(Player player) {
        // Base implementation returns null for safety
        // Each handler should override this with proper null checks
        return null;
    }

    protected abstract void handleCustomResponse(Player player, FormResponseCustom response);
    protected abstract void handleSimpleResponse(Player player, FormResponseSimple response);
}