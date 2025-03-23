package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.*;
import cn.nukkit.event.player.PlayerDeathEvent;
import cn.nukkit.event.player.PlayerRespawnEvent;
import cn.nukkit.item.Item;
import cn.nukkit.item.ItemPotion;
import cn.nukkit.level.Position;
import cn.nukkit.potion.Effect;
import cn.nukkit.scheduler.TaskHandler;
import io.micrometer.core.instrument.Timer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listener for handling potion effects and experience in protected areas.
 * This class merges functionality from the former PlayerEffectListener and ExperienceListener.
 */
public class PlayerEffectListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final ProtectionListener protectionListener;
    
    // Map of potion damage values to permission nodes
    private static final Map<Integer, String> POTION_PERMISSIONS = new HashMap<>();
    
    // Map of permission nodes to effect IDs
    private static final Map<String, Integer> EFFECT_IDS = new HashMap<>();
    
    // Map to track which players are in which areas
    private final Map<String, String> playerAreaMap = new ConcurrentHashMap<>();
    
    // Map to track active effects on players - stores effect key to amplifier
    private final Map<String, Map<String, Integer>> playerEffects = new ConcurrentHashMap<>();
    
    // Map to save player experience for respawning - from ExperienceListener
    private final Map<String, Integer> savedPlayerExperience = new HashMap<>();
    
    // Task handler for effect application
    private TaskHandler effectTask;
    
    // Maximum effect strength - changing from 255 to 10
    private static final int MAX_EFFECT_STRENGTH = 10;
    
    static {
        // Initialize potion permissions mapping
        // Speed Potion
        POTION_PERMISSIONS.put(8194, "allowPotionSpeed"); // Regular
        POTION_PERMISSIONS.put(8226, "allowPotionSpeed"); // Extended
        POTION_PERMISSIONS.put(8258, "allowPotionSpeed"); // Enhanced
        
        // Slowness Potion
        POTION_PERMISSIONS.put(8202, "allowPotionSlowness"); // Regular
        POTION_PERMISSIONS.put(8234, "allowPotionSlowness"); // Extended
        POTION_PERMISSIONS.put(8266, "allowPotionSlowness"); // Enhanced
        
        // Strength Potion
        POTION_PERMISSIONS.put(8201, "allowPotionStrength"); // Regular
        POTION_PERMISSIONS.put(8233, "allowPotionStrength"); // Extended
        POTION_PERMISSIONS.put(8265, "allowPotionStrength"); // Enhanced
        
        // Healing Potion
        POTION_PERMISSIONS.put(8197, "allowPotionInstantHealth"); // Regular
        POTION_PERMISSIONS.put(8229, "allowPotionInstantHealth"); // Enhanced
        
        // Harming Potion
        POTION_PERMISSIONS.put(8204, "allowPotionInstantDamage"); // Regular
        POTION_PERMISSIONS.put(8236, "allowPotionInstantDamage"); // Enhanced
        
        // Leaping Potion
        POTION_PERMISSIONS.put(8195, "allowPotionJumpBoost"); // Regular
        POTION_PERMISSIONS.put(8227, "allowPotionJumpBoost"); // Extended
        POTION_PERMISSIONS.put(8259, "allowPotionJumpBoost"); // Enhanced
        
        // Regeneration Potion
        POTION_PERMISSIONS.put(8193, "allowPotionRegeneration"); // Regular
        POTION_PERMISSIONS.put(8225, "allowPotionRegeneration"); // Extended
        POTION_PERMISSIONS.put(8257, "allowPotionRegeneration"); // Enhanced
        
        // Fire Resistance Potion
        POTION_PERMISSIONS.put(8195, "allowPotionFireResistance"); // Regular
        POTION_PERMISSIONS.put(8227, "allowPotionFireResistance"); // Extended
        
        // Water Breathing Potion
        POTION_PERMISSIONS.put(8205, "allowPotionWaterBreathing"); // Regular
        POTION_PERMISSIONS.put(8237, "allowPotionWaterBreathing"); // Extended
        
        // Invisibility Potion
        POTION_PERMISSIONS.put(8206, "allowPotionInvisibility"); // Regular
        POTION_PERMISSIONS.put(8238, "allowPotionInvisibility"); // Extended
        
        // Night Vision Potion
        POTION_PERMISSIONS.put(8198, "allowPotionNightVision"); // Regular
        POTION_PERMISSIONS.put(8230, "allowPotionNightVision"); // Extended
        
        // Weakness Potion
        POTION_PERMISSIONS.put(8200, "allowPotionWeakness"); // Regular
        POTION_PERMISSIONS.put(8232, "allowPotionWeakness"); // Extended
        
        // Poison Potion
        POTION_PERMISSIONS.put(8196, "allowPotionPoison"); // Regular
        POTION_PERMISSIONS.put(8228, "allowPotionPoison"); // Extended
        POTION_PERMISSIONS.put(8260, "allowPotionPoison"); // Enhanced
        
        // Initialize effect ID mapping
        EFFECT_IDS.put("allowPotionSpeed", Effect.SPEED);
        EFFECT_IDS.put("allowPotionSlowness", Effect.SLOWNESS);
        EFFECT_IDS.put("allowPotionHaste", Effect.HASTE);
        EFFECT_IDS.put("allowPotionMiningFatigue", Effect.MINING_FATIGUE);
        EFFECT_IDS.put("allowPotionStrength", Effect.STRENGTH);
        EFFECT_IDS.put("allowPotionInstantHealth", Effect.HEALING);
        EFFECT_IDS.put("allowPotionInstantDamage", Effect.HARMING);
        EFFECT_IDS.put("allowPotionJumpBoost", Effect.JUMP);
        EFFECT_IDS.put("allowPotionNausea", Effect.NAUSEA);
        EFFECT_IDS.put("allowPotionRegeneration", Effect.REGENERATION);
        EFFECT_IDS.put("allowPotionResistance", Effect.RESISTANCE);
        EFFECT_IDS.put("allowPotionFireResistance", Effect.FIRE_RESISTANCE);
        EFFECT_IDS.put("allowPotionWaterBreathing", Effect.WATER_BREATHING);
        EFFECT_IDS.put("allowPotionInvisibility", Effect.INVISIBILITY);
        EFFECT_IDS.put("allowPotionBlindness", Effect.BLINDNESS);
        EFFECT_IDS.put("allowPotionNightVision", Effect.NIGHT_VISION);
        EFFECT_IDS.put("allowPotionHunger", Effect.HUNGER);
        EFFECT_IDS.put("allowPotionWeakness", Effect.WEAKNESS);
        EFFECT_IDS.put("allowPotionPoison", Effect.POISON);
        EFFECT_IDS.put("allowPotionWither", Effect.WITHER);
        EFFECT_IDS.put("allowPotionHealthBoost", Effect.HEALTH_BOOST);
        EFFECT_IDS.put("allowPotionAbsorption", Effect.ABSORPTION);
        EFFECT_IDS.put("allowPotionSaturation", Effect.SATURATION);
        EFFECT_IDS.put("allowPotionLevitation", Effect.LEVITATION);
    }

    public PlayerEffectListener(AdminAreaProtectionPlugin plugin, ProtectionListener protectionListener) {
        this.plugin = plugin;
        this.protectionListener = protectionListener;
        startEffectTask();
    }
    
    /**
     * Start the task that applies effects to players in areas
     */
    private void startEffectTask() {
        try {
            // Stop existing task if running
            if (effectTask != null && !effectTask.isCancelled()) {
                effectTask.cancel();
            }
            
            // Run every 2 seconds (40 ticks) instead of 5 seconds for more responsive effects
            effectTask = plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, this::applyAreaEffects, 40);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Started potion effect application task - will run every 2 seconds");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to start potion effect task", e);
        }
    }
    
    /**
     * Apply area effects to all players
     */
    private void applyAreaEffects() {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (plugin.isDebugMode()) {
                plugin.debug("Running area effects application task...");
            }
            
            // Process each online player
            for (Player player : plugin.getServer().getOnlinePlayers().values()) {
                if (player == null || !player.isConnected()) continue;
                
                // Skip if player is bypassing protection
                if (plugin.isBypassing(player.getName())) {
                    if (plugin.isDebugMode()) {
                        plugin.debug("Skipping player " + player.getName() + " who is bypassing protection");
                    }
                    continue;
                }
                
                // Get the area at the player's position
                Area area = plugin.getAreaManager().getHighestPriorityAreaAtPosition(player.getPosition());
                String areaName = (area != null) ? area.getName() : null;
                String playerName = player.getName();
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Checking effects for player " + playerName + " in area: " + 
                        (areaName != null ? areaName : "none"));
                }
                
                // Check if player has changed areas
                String previousArea = playerAreaMap.get(playerName);
                if (!Objects.equals(previousArea, areaName)) {
                    // Player has changed areas
                    if (previousArea != null) {
                        if (plugin.isDebugMode()) {
                            plugin.debug("Player " + playerName + " moved from area " + 
                                previousArea + " to " + (areaName != null ? areaName : "none"));
                        }
                        
                        // Remove effects from previous area
                        removeAreaEffects(player, previousArea);
                    }
                    
                    // Update area tracking
                    if (areaName != null) {
                        playerAreaMap.put(playerName, areaName);
                    } else {
                        playerAreaMap.remove(playerName);
                    }
                }
                
                // Apply effects for current area if any
                if (area != null) {
                    if (plugin.isDebugMode()) {
                        plugin.debug("Applying area effects to player " + playerName + " in area " + areaName);
                    }
                    // This is just a regular periodic refresh, not an entry event
                    applyAreaEffectsToPlayer(player, area, false);
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "apply_area_effects");
        }
    }
    
    /**
     * Reload effects for all players
     * This is called when the plugin reloads
     */
    public void reloadEffects() {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Clear all tracking data
            playerAreaMap.clear();
            playerEffects.clear();
            
            // Clear all active effects from players
            for (Player player : plugin.getServer().getOnlinePlayers().values()) {
                if (player == null || !player.isConnected()) continue;
                
                // Remove all potion effects (we'll reapply the correct ones)
                for (Effect effect : player.getEffects().values()) {
                    // Only remove effects that match our managed types
                    if (EFFECT_IDS.containsValue(effect.getId())) {
                        player.removeEffect(effect.getId());
                    }
                }
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("Cleared all potion effects from players");
            }
            
            // Reapply effects based on current player positions
            applyAreaEffects();
            
            if (plugin.isDebugMode()) {
                plugin.debug("Reapplied potion effects to all players");
            }
            
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "reload_effects");
        }
    }
    
    /**
     * Applies the potion effects from an area to a player.
     *
     * @param player The player to apply the effects to
     * @param area The area to get the effects from
     * @param isEntryEvent Whether the player just entered the area
     */
    private void applyAreaEffectsToPlayer(Player player, Area area, boolean isEntryEvent) {
        if (player == null || area == null) return;

        String playerName = player.getName();
        String areaName = area.getName();

        if (plugin.isDebugMode()) {
            plugin.debug("Applying area effects to player " + playerName + " in area " + areaName + 
                         (isEntryEvent ? " (entry event)" : " (refresh)"));
            // Debug log for potion effects in the area
            plugin.debug("Potion effects: " + area.getAllPotionEffects());
        }

        // Get or create player effects map
        Map<String, Integer> activeEffects = playerEffects.computeIfAbsent(playerName, k -> new HashMap<>());
        boolean effectsUpdated = false;

        // Process each possible effect
        for (Map.Entry<String, Integer> entry : EFFECT_IDS.entrySet()) {
            String effectKey = entry.getKey();
            int effectId = entry.getValue();
            
            // Check if this effect is enabled for the area
            boolean isEnabled = area.getToggleState(effectKey);
            
            // Get effect strength directly from potionEffects first, then fall back to settings
            int strengthFromPotionEffects = area.getPotionEffectStrength(effectKey);
            int strengthFromSettings = area.getSettingInt(effectKey + "Strength", 0);
            int configuredAmplifier = Math.min(Math.max(strengthFromPotionEffects, strengthFromSettings), MAX_EFFECT_STRENGTH);
            
            // if (plugin.isDebugMode()) {
            //     plugin.debug("Effect check: " + effectKey + 
            //                  " - isEnabled: " + isEnabled + 
            //                  ", strengthFromPotionEffects: " + strengthFromPotionEffects + 
            //                  ", strengthFromSettings: " + strengthFromSettings + 
            //                  ", configuredAmplifier: " + configuredAmplifier);
            // }
            
            if (isEnabled && configuredAmplifier > 0) {
                // Create the effect with 12 seconds duration and no particles
                Effect effect = Effect.getEffect(effectId)
                    .setAmplifier(configuredAmplifier - 1) // Amplifier is 0-based
                    .setDuration(30000) // 12? seconds (20 ticks per second)
                    .setVisible(false); // Disable particles
                
                // Apply the effect
                player.addEffect(effect);
                activeEffects.put(effectKey, configuredAmplifier);
                effectsUpdated = true;

                // Only show messages when player enters the area, not on effect refreshes
                if (isEntryEvent && area.getToggleState("showEffectMessages")) {
                    String effectName = getEffectName(effectId);
                    player.sendMessage(plugin.getLanguageManager().get(
                        "potionEffect.applied",
                        Map.of(
                            "effect", effectName,
                            "strength", getRomanNumeralFromAmplifier(configuredAmplifier),
                            "area", areaName
                        )
                    ));
                }

                if (plugin.isDebugMode()) {
                    plugin.debug("Applied effect " + effectKey + " with amplifier " + configuredAmplifier + " to " + playerName);
                }
            } else {
                // Remove effect if it was previously active
                if (activeEffects.containsKey(effectKey)) {
                    player.removeEffect(effectId);
                    activeEffects.remove(effectKey);
                    effectsUpdated = true;

                    if (plugin.isDebugMode()) {
                        plugin.debug("Removed effect " + effectKey + " from player " + playerName);
                    }
                }
            }
        }
        
        if (plugin.isDebugMode() && effectsUpdated) {
            plugin.debug("Updated " + playerName + "'s effects in area " + areaName +
                         " - active effects: " + activeEffects.keySet());
        }
        
        // Always update the effects map if anything changed
        if (effectsUpdated) {
            playerEffects.put(playerName, activeEffects);
        }
    }
    
    /**
     * Remove all effects applied by an area
     */
    private void removeAreaEffects(Player player, String areaName) {
        String playerName = player.getName();
        Map<String, Integer> activeEffects = playerEffects.get(playerName);
        
        if (activeEffects != null && !activeEffects.isEmpty()) {
            // Remove each active effect
            for (Map.Entry<String, Integer> entry : new HashMap<>(activeEffects).entrySet()) {
                String permissionNode = entry.getKey();
                Integer effectId = EFFECT_IDS.get(permissionNode);
                
                if (effectId != null) {
                    player.removeEffect(effectId);
                    String effectName = getEffectName(effectId);
                    
                    // Get amplifier for more detailed logging
                    int amplifier = entry.getValue();
                    
                    // Check if effect messages should be shown for this area
                    Area area = plugin.getArea(areaName);
                    if (area != null && area.getToggleState("showEffectMessages")) {
                        player.sendMessage(plugin.getLanguageManager().get(
                            "potionEffect.removed",
                            Map.of(
                                "effect", effectName,
                                "area", areaName
                            )
                        ));
                    }
                    
                    activeEffects.remove(permissionNode);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Removed " + effectName + " effect with amplifier " + amplifier + 
                            " from player " + playerName + " when leaving area " + areaName);
                    }
                }
            }
            
            // Remove player from tracking if no effects left
            if (activeEffects.isEmpty()) {
                playerEffects.remove(playerName);
            }
        }
    }

    /**
     * Handle when a player consumes a potion
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerConsumePotion(PlayerItemConsumeEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getPlayer();
            Item item = event.getItem();
            
            // Only handle potions
            if (!(item instanceof ItemPotion)) {
                return;
            }
            
            ItemPotion potion = (ItemPotion) item;
            int potionId = potion.getDamage();
            
            // Check if this potion type requires permission
            String permNode = POTION_PERMISSIONS.get(potionId);
            if (permNode == null) {
                // No permission needed for this potion
                return;
            }
            
            // Check if player is in a protected area
            Area area = plugin.getAreaManager().getHighestPriorityAreaAtPosition(player.getPosition());
            if (area == null) {
                // Not in a protected area
                return;
            }
            
            // Get the full permission node
            String fullPermNode = "gui.permissions.toggles." + permNode;
            
            // Check if player can bypass protection
            if (plugin.isBypassing(player.getName())) {
                if (plugin.isDebugMode()) {
                    plugin.debug("Player " + player.getName() + " bypassed potion restriction " +
                        fullPermNode + " in area " + area.getName());
                }
                return;
            }
            
            // Check if potion is allowed in this area
            int strength = area.getSettingInt(fullPermNode + "Strength", 0);
            if (strength == 0) {
                // Potion effect is not allowed
                event.setCancelled(true);
                
                // Inform player
                String effectName = getPotionName(potionId);
                String message = plugin.getLanguageManager().get(
                    "messages.potion.blocked",
                    Map.of(
                        "effect", effectName,
                        "area", area.getName()
                    )
                );
                player.sendMessage(message);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Blocked player " + player.getName() + " from using " +
                        effectName + " potion in area " + area.getName());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error checking potion permission", e);
            if (plugin.isDebugMode()) {
                e.printStackTrace();
            }
        } finally {
            // Record performance metrics
            plugin.getPerformanceMonitor().stopTimer(sample, "potion_consume_check");
        }
    }
    
    /**
     * Handle player movement to track area changes
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Only process if player has changed blocks
        if (event.getFrom().getFloorX() == event.getTo().getFloorX() &&
            event.getFrom().getFloorY() == event.getTo().getFloorY() &&
            event.getFrom().getFloorZ() == event.getTo().getFloorZ()) {
            return;
        }
        
        Player player = event.getPlayer();
        
        // Skip if player is bypassing protection
        if (plugin.isBypassing(player.getName())) {
            return;
        }
        
        // Get the area at the player's new position
        Area area = plugin.getAreaManager().getHighestPriorityAreaAtPosition(event.getTo());
        String areaName = (area != null) ? area.getName() : null;
        String playerName = player.getName();
        
        // Check if player has changed areas
        String previousArea = playerAreaMap.get(playerName);
        if (!Objects.equals(previousArea, areaName)) {
            // Player has changed areas
            if (previousArea != null) {
                // Remove effects from previous area
                removeAreaEffects(player, previousArea);
            }
            
            // Update area tracking
            if (areaName != null) {
                playerAreaMap.put(playerName, areaName);
                
                // Apply effects for new area - this is an entry event
                applyAreaEffectsToPlayer(player, area, true);
            } else {
                playerAreaMap.remove(playerName);
            }
        }
    }
    
    /**
     * Handle player join to start tracking
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Skip if player is bypassing protection
        if (plugin.isBypassing(player.getName())) {
            return;
        }
        
        // Get the area at the player's position
        Area area = plugin.getAreaManager().getHighestPriorityAreaAtPosition(player.getPosition());
        if (area != null) {
            // Track player in this area
            playerAreaMap.put(player.getName(), area.getName());
            
            // Apply area effects - this is considered an entry event
            applyAreaEffectsToPlayer(player, area, true);
        }
    }
    
    /**
     * Handle player quit to clean up tracking
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        String playerName = event.getPlayer().getName();
        
        // Remove from area tracking
        String areaName = playerAreaMap.remove(playerName);
        
        // Remove from effects tracking
        playerEffects.remove(playerName);
        
        if (plugin.isDebugMode() && areaName != null) {
            plugin.debug("Removed player " + playerName + " from area tracking when they left the server");
        }
    }
    
    /**
     * Get a human-readable name for a potion damage value
     */
    private String getPotionName(int potionDamage) {
        // Extract the base potion type from the damage value
        int baseType = potionDamage & 0xF;
        
        return switch (baseType) {
            case 1 -> "Regeneration";
            case 2 -> "Speed";
            case 3 -> "Fire Resistance";
            case 4 -> "Poison";
            case 5 -> "Instant Health";
            case 6 -> "Night Vision";
            case 8 -> "Weakness";
            case 9 -> "Strength";
            case 10 -> "Slowness";
            case 11 -> "Jump Boost";
            case 12 -> "Instant Damage";
            case 13 -> "Water Breathing";
            case 14 -> "Invisibility";
            default -> "Unknown Potion";
        };
    }
    
    /**
     * Get a human-readable name for an effect ID
     */
    private String getEffectName(int effectId) {
        return switch (effectId) {
            case Effect.SPEED -> "Speed";
            case Effect.SLOWNESS -> "Slowness";
            case Effect.HASTE -> "Haste";
            case Effect.MINING_FATIGUE -> "Mining Fatigue";
            case Effect.STRENGTH -> "Strength";
            case Effect.HEALING -> "Instant Health";
            case Effect.HARMING -> "Instant Damage";
            case Effect.JUMP -> "Jump Boost";
            case Effect.NAUSEA -> "Nausea";
            case Effect.REGENERATION -> "Regeneration";
            case Effect.RESISTANCE -> "Resistance";
            case Effect.FIRE_RESISTANCE -> "Fire Resistance";
            case Effect.WATER_BREATHING -> "Water Breathing";
            case Effect.INVISIBILITY -> "Invisibility";
            case Effect.BLINDNESS -> "Blindness";
            case Effect.NIGHT_VISION -> "Night Vision";
            case Effect.HUNGER -> "Hunger";
            case Effect.WEAKNESS -> "Weakness";
            case Effect.POISON -> "Poison";
            case Effect.WITHER -> "Wither";
            case Effect.HEALTH_BOOST -> "Health Boost";
            case Effect.ABSORPTION -> "Absorption";
            case Effect.SATURATION -> "Saturation";
            case Effect.LEVITATION -> "Levitation";
            default -> "Unknown Effect";
        };
    }
    
    /**
     * Convert an amplifier value to a Roman numeral for display
     * 
     * @param amplifier The potion amplifier (0-based)
     * @return The Roman numeral representation (I for amplifier 0, II for amplifier 1, etc.)
     */
    private String getRomanNumeralFromAmplifier(int amplifier) {
        // Add 1 to convert 0-based amplifier to 1-based level
        int level = amplifier + 1;
        
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(level);
        };
    }

    //
    // Experience-related functionality (merged from ExperienceListener)
    //
    
    /**
     * Handle player death to preserve experience in protected areas
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getEntity();
            Position pos = player.getPosition();
            
            // Check if XP drops are allowed in this area
            if (protectionListener.handleProtection(pos, player, "allowXPDrop")) {
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
    
    /**
     * Restore saved XP when player respawns
     */
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
        player.setExperience(Math.round(progress * 100), level);
        
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
        if (level <= 15) {
            return 2 * level + 7;
        } else if (level <= 30) {
            return 5 * level - 38;
        } else {
            return 9 * level - 158;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Only handle chorus fruit teleportation
            if (event.getCause() == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) {
                Player player = event.getPlayer();
                
                // Skip if player is bypassing protection
                if (plugin.isBypassing(player.getName())) {
                    return;
                }
                
                // Check the target location for protection
                Position target = event.getTo();
                
                // Check if chorus fruit teleportation is allowed in this area
                if (protectionListener.handleProtection(target, player, "allowChorusFruit")) {
                    event.setCancelled(true);
                    protectionListener.sendProtectionMessage(player, "messages.protection.chorusFruit");
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Prevented chorus fruit teleportation for " + player.getName() + 
                                   " at " + target.getFloorX() + "," + target.getFloorY() + "," + target.getFloorZ());
                    }
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "chorus_fruit_teleport_check");
        }
    }
}