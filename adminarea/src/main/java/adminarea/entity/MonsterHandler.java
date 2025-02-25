package adminarea.entity;

import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.math.Vector3;
import java.util.Random;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import nukkitcoders.mobplugin.entities.monster.WalkingMonster;

/**
 * Handles monster targeting and behavior in protected areas
 * Compatible with MobPlugin monsters
 */
public class MonsterHandler {
    private static final Random random = new Random();
    private static final Map<String, Long> lastResetTimes = new ConcurrentHashMap<>();
    private static final long RESET_COOLDOWN = 2000; // 2 seconds
    
    /**
     * Reset a monster's targeting to prevent it from attacking in protected areas
     * 
     * @param entity The monster entity
     * @param target The player target to avoid
     */
    public static void resetTarget(Entity entity, Player target) {
        // Check if entity is a MobPlugin monster
        if (!(entity instanceof WalkingMonster)) return;
        
        WalkingMonster monster = (WalkingMonster) entity;
        
        // Skip if we've recently reset this entity (prevents excessive processing)
        String entityKey = entity.getId() + ":" + target.getId();
        long now = System.currentTimeMillis();
        Long lastReset = lastResetTimes.get(entityKey);
        if (lastReset != null && now - lastReset < RESET_COOLDOWN) {
            return;
        }
        lastResetTimes.put(entityKey, now);
        
        try {
            // Calculate a new position away from player
            Vector3 playerPos = target.getPosition();
            Vector3 mobPos = entity.getPosition();
            
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
            
            // Scale to push 5 blocks away
            dx *= 5;
            dz *= 5;
            
            // Calculate new position
            Vector3 newPos = new Vector3(
                mobPos.x + dx,
                mobPos.y,
                mobPos.z + dz
            );
            
            // Use MobPlugin's WalkingMonster methods to reset targeting
            try {
                // Clear target and make monster not angry
                monster.setFollowTarget(null, false);
                
                // Set isAngryTo to -1 to make monster not angry to any player
                // Using reflection as the field is not directly accessible
                java.lang.reflect.Field angryToField = 
                    WalkingMonster.class.getDeclaredField("isAngryTo");
                angryToField.setAccessible(true);
                angryToField.set(monster, -1L);
                
                // Disable attacking temporarily
                java.lang.reflect.Field canAttackField = 
                    WalkingMonster.class.getDeclaredField("canAttack");
                canAttackField.setAccessible(true);
                canAttackField.set(monster, false);
            } catch (Exception ignored) {
                // Reflection failed, try direct method
                monster.setFollowTarget(null);
            }
            
            // Move entity away from player
            monster.setPosition(newPos);
            monster.setMotion(new Vector3(dx * 0.1, 0, dz * 0.1)); // Add slight movement
            
        } catch (Exception e) {
            // Silently fail - non-critical function
        }
    }
    
    /**
     * Check if an entity is a monster from MobPlugin
     * 
     * @param entity The entity to check
     * @return true if entity is a MobPlugin monster
     */
    public static boolean isMobPluginMonster(Entity entity) {
        // Check if entity is a WalkingMonster from MobPlugin
        return entity instanceof WalkingMonster;
    }
    
    /**
     * Clean up the reset times map to prevent memory leaks
     */
    public static void cleanup() {
        long now = System.currentTimeMillis();
        lastResetTimes.entrySet().removeIf(entry -> now - entry.getValue() > 30000); // 30 seconds
    }
}