package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerDeathEvent;
import cn.nukkit.event.player.PlayerRespawnEvent;
import cn.nukkit.level.Position;
import io.micrometer.core.instrument.Timer;

import java.util.HashMap;
import java.util.Map;

public class ExperienceListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final ProtectionListener protectionListener;
    private final Map<String, Integer> savedPlayerExperience = new HashMap<>();

    public ExperienceListener(AdminAreaProtectionPlugin plugin, ProtectionListener protectionListener) {
        this.plugin = plugin;
        this.protectionListener = protectionListener;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getEntity();
            Position pos = player.getPosition();
            
            // Check if XP drops are allowed in this area
            if (protectionListener.shouldCancel(pos, player, "allowXPDrop")) {
                // Calculate and save the player's total XP for respawn
                int totalExp = calculateTotalExperience(player.getExperienceLevel(), player.getExperience());
                
                savedPlayerExperience.put(player.getName(), totalExp);
                
                // Force keep XP instead of dropping it
                event.setKeepExperience(true);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Saved " + totalExp + " XP for player " + player.getName() + 
                        " to be restored on respawn");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "xp_drop_check");
        }
    }
    
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        
        // Check if we have saved XP for this player
        if (savedPlayerExperience.containsKey(playerName)) {
            // Get the saved XP value
            int savedExp = savedPlayerExperience.remove(playerName);
            
            // Schedule XP restoration with a longer delay to ensure player is fully spawned
            // This avoids potential race conditions with the vanilla respawn handler
            plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> {
                // Make sure player is still online
                if (player.isOnline()) {
                    // Reset first to avoid conflicts with any XP already given
                    player.setExperience(0, 0);
                    
                    // Set the player's XP directly using our accurate method
                    setPlayerTotalExperience(player, savedExp);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Successfully restored " + savedExp + " XP for player " + playerName);
                    }
                }
            }, 10); // Wait 10 ticks (0.5 seconds) to ensure player is fully spawned
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
     * Set a player's total experience points
     * 
     * @param player The player
     * @param totalExp The total experience points
     */
    private void setPlayerTotalExperience(Player player, int totalExp) {
        // Reset the player's XP first
        player.setExperience(0, 0);
        
        // Calculate and set level and progress
        int level = 0;
        int remainingExp = totalExp;
        
        // Find the highest completed level
        while (remainingExp >= getExpToLevel(level)) {
            remainingExp -= getExpToLevel(level);
            level++;
        }
        
        // Calculate progress to next level
        float progress = 0;
        int expToNextLevel = getExpToLevel(level);
        if (expToNextLevel > 0) {
            progress = (float)remainingExp / expToNextLevel;
        }
        
        // Set the XP directly
        player.setExperience((int) progress, level);
        
        if (plugin.isDebugMode()) {
            plugin.debug("Set player " + player.getName() + " XP to level " + level + 
                       " with progress " + progress + " (total XP: " + totalExp + ")");
        }
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