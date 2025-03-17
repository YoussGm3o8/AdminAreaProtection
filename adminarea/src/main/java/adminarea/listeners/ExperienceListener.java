package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerDeathEvent;
import cn.nukkit.event.player.PlayerRespawnEvent;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.level.Position;
import io.micrometer.core.instrument.Timer;

import java.util.HashMap;
import java.util.Map;

public class ExperienceListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final ProtectionListener protectionListener;
    // Cache of player XP pickup ability to avoid excessive checks
    private final Map<String, Long> lastXpPickupCheck = new HashMap<>();
    private static final long CHECK_INTERVAL = 500; // ms

    public ExperienceListener(AdminAreaProtectionPlugin plugin, ProtectionListener protectionListener) {
        this.plugin = plugin;
        this.protectionListener = protectionListener;
    }

    /**
     * Monitor player movement to update their ability to pick up XP
     * This is necessary since Nukkit doesn't have an XP pickup event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        
        // Check if we need to update the XP pickup ability (throttle checks to avoid performance issues)
        long now = System.currentTimeMillis();
        if (lastXpPickupCheck.containsKey(playerName) && 
            now - lastXpPickupCheck.get(playerName) < CHECK_INTERVAL) {
            return;
        }
        
        // Update the last check time
        lastXpPickupCheck.put(playerName, now);
        
        // Check if XP pickup is allowed in the area
        Position pos = player.getPosition();
        boolean canPickup = protectionListener.handleProtection(pos, player, "allowXPPickup");
        
        // Update the player's ability to pick up XP
        if (player.canPickupXP() != canPickup) {
            player.setCanPickupXP(canPickup);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Updated player " + playerName + " XP pickup ability to " + 
                    canPickup + " at " + pos.getFloorX() + ", " + pos.getFloorY() + ", " + 
                    pos.getFloorZ());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getEntity();
            Position pos = player.getPosition();
            
            // Check if XP drops are allowed in this area
            if (protectionListener.handleProtection(pos, player, "allowXPDrop")) {
                // Force keep XP instead of dropping it
                // The game will handle restoring XP automatically, so we don't need to save it
                event.setKeepExperience(true);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Set keepExperience flag to true for player " + player.getName() + 
                        " - XP will be preserved by game rules");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "xp_drop_check");
        }
    }
    
    /**
     * Calculate the total experience based on level and progress
     * 
     * @param level The experience level
     * @param progress The progress to the next level (0.0-1.0)
     * @return The total experience points
     */
    private int calculateTotalExperience(int level, float progress) {
        int totalXp = 0;
        
        // XP for complete levels - using more accurate Nukkit formula
        for (int i = 0; i < level; i++) {
            totalXp += getExpToLevel(i);
        }
        
        // Add partial level progress
        int currentLevelXp = getExpToLevel(level);
        totalXp += Math.round(currentLevelXp * progress);
        
        return totalXp;
    }
    
    /**
     * Get the experience required to reach the next level
     * 
     * @param level The current level
     * @return The experience required
     */
    private int getExpToLevel(int level) {
        if (level >= 30) {
            return 112 + (level - 30) * 9;
        } else if (level >= 15) {
            return 37 + (level - 15) * 5;
        } else {
            return 7 + level * 2;
        }
    }
}