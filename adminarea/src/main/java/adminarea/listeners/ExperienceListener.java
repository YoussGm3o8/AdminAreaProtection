package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerDeathEvent;
import cn.nukkit.level.Position;
import io.micrometer.core.instrument.Timer;

public class ExperienceListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final ProtectionListener protectionListener;

    public ExperienceListener(AdminAreaProtectionPlugin plugin, ProtectionListener protectionListener) {
        this.plugin = plugin;
        this.protectionListener = protectionListener;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Skip if no active areas
            if (plugin.getAreaManager().getActiveAreas().isEmpty()) {
                return;
            }

            Player player = event.getEntity();
            Position pos = player.getPosition();
            
            // Check if XP drops are allowed in this area
            if (protectionListener.shouldCancel(pos, player, "allowXPDrop")) {
                event.setKeepExperience(true); // Keep XP instead of dropping it
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "xp_drop_check");
        }
    }
}