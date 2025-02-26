package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerItemConsumeEvent;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.item.Item;
import cn.nukkit.item.ItemPotion;
import cn.nukkit.potion.Effect;
import cn.nukkit.scheduler.TaskHandler;
import io.micrometer.core.instrument.Timer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listener for handling potion effect-related events in protected areas.
 */
public class PlayerEffectListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    
    // Map of potion damage values to permission nodes
    private static final Map<Integer, String> POTION_PERMISSIONS = new HashMap<>();
    
    // Map of permission nodes to effect IDs
    private static final Map<String, Integer> EFFECT_IDS = new HashMap<>();
    
    // Map to track which players are in which areas
    private final Map<String, String> playerAreaMap = new ConcurrentHashMap<>();
    
    // Map to track active effects on players
    private final Map<String, Map<String, Integer>> playerEffects = new ConcurrentHashMap<>();
    
    // Task handler for effect application
    private TaskHandler effectTask;
    
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

    public PlayerEffectListener(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        startEffectTask();
    }
    
    /**
     * Start the task that applies effects to players in areas
     */
    private void startEffectTask() {
        // Run every 5 seconds (100 ticks)
        effectTask = plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, this::applyAreaEffects, 100);
    }
    
    /**
     * Apply area effects to all players
     */
    private void applyAreaEffects() {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Process each online player
            for (Player player : plugin.getServer().getOnlinePlayers().values()) {
                if (player == null || !player.isConnected()) continue;
                
                // Skip if player is bypassing protection
                if (plugin.isBypassing(player.getName())) continue;
                
                // Get the area at the player's position
                Area area = plugin.getAreaManager().getHighestPriorityAreaAtPosition(player.getPosition());
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
                    } else {
                        playerAreaMap.remove(playerName);
                    }
                }
                
                // Apply effects for current area if any
                if (area != null) {
                    applyAreaEffectsToPlayer(player, area);
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
     * Apply effects from an area to a player
     */
    private void applyAreaEffectsToPlayer(Player player, Area area) {
        String playerName = player.getName();
        String areaName = area.getName();
        
        // Get or create player effects map
        Map<String, Integer> activeEffects = playerEffects.computeIfAbsent(playerName, k -> new HashMap<>());
        
        // Check each potion effect toggle
        for (Map.Entry<String, Integer> entry : EFFECT_IDS.entrySet()) {
            String permissionNode = entry.getKey();
            int effectId = entry.getValue();
            
            // Check if effect is enabled for this area
            if (area.getToggleState(permissionNode)) {
                // Get effect strength (0-255)
                String strengthNode = permissionNode + "Strength";
                int strength = area.getSettingInt(strengthNode, 0);
                
                // Skip if strength is 0 (disabled)
                if (strength == 0) continue;
                
                // Calculate amplifier (strength - 1, capped at 0-255)
                int amplifier = Math.max(0, Math.min(255, strength - 1));
                
                // Check if effect is already active with same strength
                Integer currentAmplifier = activeEffects.get(permissionNode);
                if (currentAmplifier != null && currentAmplifier == amplifier) {
                    // Effect already active with same strength, skip
                    continue;
                }
                
                // Apply the effect (duration 10 seconds, will be refreshed by task)
                Effect effect = Effect.getEffect(effectId);
                if (effect != null) {
                    // Set effect properties
                    effect.setAmplifier(amplifier);
                    effect.setDuration(20 * 10); // 10 seconds in ticks
                    effect.setVisible(true);
                    
                    // Apply to player
                    player.addEffect(effect);
                    
                    // Track the effect
                    activeEffects.put(permissionNode, amplifier);
                    
                    // Send message if this is a new effect or strength changed
                    if (currentAmplifier == null) {
                        // New effect
                        String effectName = getEffectName(effectId);
                        player.sendMessage(plugin.getLanguageManager().get("potionEffect.applied", 
                            Map.of(
                                "effect", effectName,
                                "strength", String.valueOf(strength),
                                "area", areaName
                            )));
                        
                        if (plugin.isDebugMode()) {
                            plugin.debug("Applied " + effectName + " effect (level " + strength + 
                                ") to player " + playerName + " in area " + areaName);
                        }
                    } else if (currentAmplifier != amplifier) {
                        // Strength changed
                        String effectName = getEffectName(effectId);
                        player.sendMessage(plugin.getLanguageManager().get("potionEffect.strength.set", 
                            Map.of(
                                "effect", effectName,
                                "strength", String.valueOf(strength),
                                "area", areaName
                            )));
                        
                        if (plugin.isDebugMode()) {
                            plugin.debug("Updated " + effectName + " effect from level " + (currentAmplifier + 1) + 
                                " to level " + strength + " for player " + playerName + " in area " + areaName);
                        }
                    }
                }
            } else {
                // Effect is disabled for this area, remove if active
                if (activeEffects.containsKey(permissionNode)) {
                    player.removeEffect(effectId);
                    String effectName = getEffectName(effectId);
                    player.sendMessage(plugin.getLanguageManager().get("potionEffect.removed", 
                        Map.of(
                            "effect", effectName,
                            "area", areaName
                        )));
                    
                    activeEffects.remove(permissionNode);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Removed " + effectName + " effect from player " + 
                            playerName + " in area " + areaName);
                    }
                }
            }
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
                    player.sendMessage(plugin.getLanguageManager().get("potionEffect.removed", 
                        Map.of(
                            "effect", effectName,
                            "area", areaName
                        )));
                    
                    activeEffects.remove(permissionNode);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Removed " + effectName + " effect from player " + 
                            playerName + " when leaving area " + areaName);
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
            
            // Only handle potion items
            if (!(item instanceof ItemPotion)) {
                return;
            }
            
            // Skip if player is bypassing protection
            if (plugin.isBypassing(player.getName())) {
                return;
            }
            
            // Get the area at the player's position
            Area area = plugin.getAreaManager().getHighestPriorityAreaAtPosition(player.getPosition());
            if (area == null) {
                // No area, allow the potion
                return;
            }
            
            // Get the potion damage value (meta)
            int potionDamage = item.getDamage();
            
            // Get the permission node for this potion
            String permissionNode = POTION_PERMISSIONS.get(potionDamage);
            if (permissionNode == null) {
                // If we don't have a specific permission for this potion, allow it
                return;
            }
            
            // Check if the potion is allowed in this area
            if (!area.getToggleState(permissionNode)) {
                // Potion is not allowed, cancel the event
                event.setCancelled(true);
                
                // Send a message to the player
                String potionName = getPotionName(potionDamage);
                player.sendMessage(plugin.getLanguageManager().get("messages.protection.potionEffect", 
                    Map.of("effect", potionName, "area", area.getName())));
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Blocked " + potionName + " potion for player " + player.getName() + 
                                " in area " + area.getName());
                }
            }
        } finally {
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
                
                // Apply effects for new area
                applyAreaEffectsToPlayer(player, area);
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
            
            // Apply area effects
            applyAreaEffectsToPlayer(player, area);
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
}