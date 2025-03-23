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
        boolean canPickup = !protectionListener.handleProtection(pos, player, "allowXPPickup");
        
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getEntity();
            Position pos = player.getPosition();
            
            // Use Nukkit's built-in XP preservation when allowXPDrop is disabled
            // Note: handleProtection returns true if protection should be applied (action blocked)
            if (protectionListener.handleProtection(pos, player, "allowXPDrop")) {
                // Set Nukkit to preserve XP
                event.setKeepExperience(true);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("XP preserved: Using Nukkit's built-in preservation for player " + 
                        player.getName() + " (level: " + player.getExperienceLevel() + 
                        ", progress: " + player.getExperience() + ")");
                }
            } else if (plugin.isDebugMode()) {
                plugin.debug("XP not preserved: Player " + player.getName() + 
                        " died in area where XP drops are allowed");
            }
            
            // Check if we should keep inventory when item drops are disabled
            if (protectionListener.handleProtection(pos, player, "allowItemDrop")) {
                // If item drops are protected (not allowed), keep inventory
                event.setKeepInventory(true);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Inventory preserved: Player " + player.getName() + 
                        " died in area where item drops are not allowed");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "xp_drop_check");
        }
    }
    
    /**
     * Cleanup method to be called when the plugin is disabled or reloaded
     * Clears all cached data to prevent memory leaks
     */
    public void cleanup() {
        lastXpPickupCheck.clear();
    }
}