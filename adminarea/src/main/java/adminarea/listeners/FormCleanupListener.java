package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerQuitEvent;

public class FormCleanupListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;

    public FormCleanupListener(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        String playerName = event.getPlayer().getName();

        // Remove all form tracking data
        plugin.getFormIdMap().remove(playerName);
        plugin.getFormIdMap().remove(playerName + "_editing");
        plugin.getFormIdMap().remove(playerName + "_group");
        plugin.getFormIdMap().remove(playerName + "_track");

        // Clear navigation history
        plugin.getGuiManager().clearNavigationHistory(event.getPlayer());

        if (plugin.isDebugMode()) {
            plugin.debug("Cleaned up form data for disconnected player: " + playerName);
        }
    }
}
