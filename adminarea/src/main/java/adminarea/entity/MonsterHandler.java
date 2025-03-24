package adminarea.entity;

import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.plugin.PluginLogger;
import cn.nukkit.plugin.PluginManager;
import cn.nukkit.Server;
import cn.nukkit.level.Level;
import java.util.Random;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
// Remove direct import to prevent ClassNotFoundException
// import nukkitcoders.mobplugin.entities.monster.WalkingMonster;
import adminarea.AdminAreaProtectionPlugin;

/**
 * Handles monster targeting and behavior in protected areas
 * Compatible with MobPlugin monsters
 */
public class MonsterHandler {
    private static final Random random = new Random();
    private static final Map<String, Long> lastResetTimes = new ConcurrentHashMap<>();
    private static final long RESET_COOLDOWN = 2000; // 2 seconds
    private static boolean initialized = false;
    private static boolean mobPluginDetected = false;
    private static String mobPluginVersion = "unknown";
    private static PluginLogger logger = null;
    private static boolean debugMode = false;
    
    // Store references to classes from MobPlugin using Class<?> to avoid ClassNotFoundException
    private static Class<?> walkingMonsterClass = null;
    
    /**
     * Initialize the handler with the plugin instance for logging
     * 
     * @param plugin The AdminAreaProtectionPlugin instance
     */
    public static void initialize(AdminAreaProtectionPlugin plugin) {
        if (initialized) return;
        
        logger = plugin.getLogger();
        debugMode = plugin.isDebugMode();
        
        // Detect MobPlugin and its version
        PluginManager pm = Server.getInstance().getPluginManager();
        Plugin mobPlugin = pm.getPlugin("MobPlugin");
        
        if (mobPlugin != null) {
            mobPluginDetected = true;
            mobPluginVersion = mobPlugin.getDescription().getVersion();
            
            // Try to load WalkingMonster class using reflection
            try {
                walkingMonsterClass = Class.forName("nukkitcoders.mobplugin.entities.monster.WalkingMonster");
                if (debugMode) {
                    logger.info("Successfully loaded WalkingMonster class from MobPlugin");
                }
            } catch (ClassNotFoundException e) {
                if (debugMode) {
                    logger.warning("MobPlugin found, but could not load WalkingMonster class: " + e.getMessage());
                }
            }
            
            if (debugMode) {
                logger.info("MobPlugin detected, version: " + mobPluginVersion);
            }
        } else {
            if (debugMode) {
                logger.warning("MobPlugin not detected, monster protection features will be limited");
            }
        }
        
        initialized = true;
    }
    
    /**
     * Reset a monster's targeting to prevent it from attacking in protected areas
     * 
     * @param entity The monster entity
     * @param target The player target to avoid
     */
    public static void resetTarget(Entity entity, Player target) {
        // Only proceed if MobPlugin is detected
        if (!mobPluginDetected || walkingMonsterClass == null) return;
        
        // Check if entity is a MobPlugin monster using reflection
        if (!walkingMonsterClass.isInstance(entity)) return;
        
        // Skip if we've recently reset this entity (prevents excessive processing)
        String entityKey = entity.getId() + ":" + target.getId();
        long now = System.currentTimeMillis();
        Long lastReset = lastResetTimes.get(entityKey);
        if (lastReset != null && now - lastReset < RESET_COOLDOWN) {
            return;
        }
        lastResetTimes.put(entityKey, now);
        
        try {
            // Calculate a new position away from player that doesn't go through walls
            Vector3 newPos = calculateSafePosition(entity, target);
            
            // Use reflection to reset monster targeting
            resetMonsterTargeting(entity);
            
            // Move entity away from player if safe position was found
            if (newPos != null) {
                Vector3 direction = newPos.subtract(entity.getPosition());
                entity.setPosition(newPos);
                entity.setMotion(direction.normalize().multiply(0.1)); // Add slight momentum
            }
        } catch (Exception e) {
            if (debugMode && logger != null) {
                logger.warning("Error resetting monster target: " + e.getMessage());
            }
        }
    }
    
    /**
     * Reset a monster's targeting using reflection for compatibility
     * 
     * @param monster The monster entity to reset
     */
    private static void resetMonsterTargeting(Entity monster) {
        if (walkingMonsterClass == null) return;
        
        // Try direct API methods first using reflection
        try {
            // Try method with boolean param first
            try {
                java.lang.reflect.Method setFollowTargetMethod = 
                    walkingMonsterClass.getMethod("setFollowTarget", Entity.class, boolean.class);
                setFollowTargetMethod.invoke(monster, null, false);
            } catch (Exception ignored) {
                // Fallback to simpler method if the one with boolean param fails
                try {
                    java.lang.reflect.Method setFollowTargetMethod = 
                        walkingMonsterClass.getMethod("setFollowTarget", Entity.class);
                    setFollowTargetMethod.invoke(monster, null);
                } catch (Exception ignored2) {
                    // Both direct methods failed, try reflection on fields
                }
            }
        } catch (Exception e) {
            if (debugMode) {
                logger.warning("Failed to reset monster targeting: " + e.getMessage());
            }
        }
        
        // Use reflection for fields not exposed through API
        try {
            // Reset isAngryTo field
            java.lang.reflect.Field angryToField = 
                walkingMonsterClass.getDeclaredField("isAngryTo");
            angryToField.setAccessible(true);
            angryToField.set(monster, -1L);
        } catch (Exception ignored) {
            // Field may not exist in this version, that's okay
        }
        
        try {
            // Disable canAttack field
            java.lang.reflect.Field canAttackField = 
                walkingMonsterClass.getDeclaredField("canAttack");
            canAttackField.setAccessible(true);
            canAttackField.set(monster, false);
        } catch (Exception ignored) {
            // Field may not exist in this version, that's okay
        }
        
        // Try the newer API methods that might exist in newer versions
        try {
            // Some versions use this method
            monster.getClass().getMethod("setAngry", boolean.class)
                .invoke(monster, false);
        } catch (Exception ignored) {
            // Method doesn't exist in this version
        }
    }
    
    /**
     * Calculate a safe position to move the monster to
     * 
     * @param monster The monster entity
     * @param target The player target to move away from
     * @return A safe position, or null if no safe position found
     */
    private static Vector3 calculateSafePosition(Entity monster, Player target) {
        Vector3 playerPos = target.getPosition();
        Vector3 mobPos = monster.getPosition();
        Level level = monster.getLevel();
        
        // Get direction vector from player to mob
        double dx = mobPos.x - playerPos.x;
        double dz = mobPos.z - playerPos.z;
        
        // Normalize and scale for pushing monster away
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 0.1) {
            // If too close, pick random direction
            double angle = random.nextDouble() * 2 * Math.PI;
            dx = Math.cos(angle);
            dz = Math.sin(angle);
        } else {
            // Normalize
            dx /= distance;
            dz /= distance;
        }
        
        // Scale to push monster away (try different distances)
        for (int scale = 5; scale > 1; scale--) {
            Vector3 testPos = new Vector3(
                mobPos.x + dx * scale,
                mobPos.y,
                mobPos.z + dz * scale
            );
            
            // Check if position is safe (not in a block)
            if (level != null && level.getBlock(testPos).canPassThrough()) {
                return testPos;
            }
        }
        
        // If no safe position found, return null
        return null;
    }
    
    /**
     * Check if an entity is a monster from MobPlugin
     * 
     * @param entity The entity to check
     * @return true if entity is a MobPlugin monster
     */
    public static boolean isMobPluginMonster(Entity entity) {
        // Check if entity is a WalkingMonster from MobPlugin
        return walkingMonsterClass != null && walkingMonsterClass.isInstance(entity);
    }
    
    /**
     * Check if MobPlugin has been detected
     * 
     * @return true if MobPlugin is installed and detected
     */
    public static boolean isMobPluginDetected() {
        return mobPluginDetected;
    }
    
    /**
     * Check if WalkingMonster class was loaded successfully
     *
     * @return true if WalkingMonster class was loaded
     */
    public static boolean isWalkingMonsterAvailable() {
        return walkingMonsterClass != null;
    }
    
    /**
     * Get the detected version of MobPlugin
     * 
     * @return The version string of the detected MobPlugin
     */
    public static String getMobPluginVersion() {
        return mobPluginVersion;
    }
    
    /**
     * Clean up the reset times map to prevent memory leaks
     */
    public static void cleanup() {
        long now = System.currentTimeMillis();
        lastResetTimes.entrySet().removeIf(entry -> now - entry.getValue() > 30000); // 30 seconds
    }
}