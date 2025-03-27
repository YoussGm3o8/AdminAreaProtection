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
// Remove MobPlugin import
import cn.nukkit.entity.mob.EntityWalkingMob; // NukkitMOT's walking mob class
import cn.nukkit.entity.mob.EntityMob; // NukkitMOT's base mob class
import adminarea.AdminAreaProtectionPlugin;

/**
 * Handles monster targeting and behavior in protected areas
 * Compatible with NukkitMOT mobs
 */
public class MonsterHandler {
    private static final Random random = new Random();
    private static final Map<String, Long> lastResetTimes = new ConcurrentHashMap<>();
    private static final long RESET_COOLDOWN = 2000; // 2 seconds
    private static boolean initialized = false;
    private static boolean debugMode = false;
    private static PluginLogger logger = null;
    
    /**
     * Initialize the handler with the plugin instance for logging
     * 
     * @param plugin The AdminAreaProtectionPlugin instance
     */
    public static void initialize(AdminAreaProtectionPlugin plugin) {
        if (initialized) return;
        
        logger = plugin.getLogger();
        debugMode = plugin.isDebugMode();
        
        if (debugMode) {
            logger.info("NukkitMOT mob protection enabled");
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
        // Check if entity is a NukkitMOT monster
        if (!(entity instanceof EntityMob)) return;
        
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
            
            // Reset monster targeting
            if (entity instanceof EntityWalkingMob) {
                ((EntityWalkingMob) entity).setTarget(null);
            }
            
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
     * Check if an entity is a monster from NukkitMOT
     * 
     * @param entity The entity to check
     * @return true if entity is a NukkitMOT monster
     */
    public static boolean isNukkitMOTMonster(Entity entity) {
        return entity instanceof EntityMob;
    }
    
    /**
     * Check if an entity is a walking monster from NukkitMOT
     * 
     * @param entity The entity to check
     * @return true if entity is a NukkitMOT walking monster
     */
    public static boolean isWalkingMonster(Entity entity) {
        return entity instanceof EntityWalkingMob;
    }
    
    /**
     * Clean up resources used by the handler
     */
    public static void cleanup() {
        lastResetTimes.clear();
    }
}