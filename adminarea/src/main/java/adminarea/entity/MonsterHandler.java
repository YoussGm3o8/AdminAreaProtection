package adminarea.entity;

import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.mob.EntityMob;
import cn.nukkit.level.Position;
import cn.nukkit.math.Vector3;

/**
 * Handles monster targeting and behavior.
 * This class is designed to work with any mob plugin.
 */
public class MonsterHandler {
    /**
     * Resets a monster's target and moves it away from the player.
     * This is a generic implementation that works with any mob plugin.
     * 
     * @param monster The monster entity
     * @param player The player being targeted
     */
    public static void resetTarget(Entity monster, Player player) {
        if (!(monster instanceof EntityMob)) {
            return;
        }

        // Calculate direction away from player
        Vector3 direction = monster.getPosition().subtract(player.getPosition()).normalize();
        
        // Move monster 10 blocks away in that direction
        Position newPos = monster.getPosition().add(direction.multiply(10));
        
        // Ensure new position is safe
        newPos.y = monster.getLevel().getHighestBlockAt((int) newPos.x, (int) newPos.z) + 1;
        
        // Teleport monster to new position
        monster.teleport(newPos);
        
        // Try to reset any mob plugin-specific targeting
        try {
            // Try MobPlugin's WalkingMonster
            Class<?> walkingMonsterClass = Class.forName("nukkitcoders.mobplugin.entities.monster.WalkingMonster");
            if (walkingMonsterClass.isInstance(monster)) {
                walkingMonsterClass.getMethod("setFollowTarget", Entity.class).invoke(monster, (Entity) null);
                return;
            }
        } catch (Exception ignored) {
            // MobPlugin not available
        }
        
        // Try other common mob plugin methods
        try {
            monster.getClass().getMethod("setTarget", Entity.class).invoke(monster, (Entity) null);
        } catch (Exception ignored) {
            // Method not available
        }
        
        try {
            monster.getClass().getMethod("clearTarget").invoke(monster);
        } catch (Exception ignored) {
            // Method not available
        }
    }
} 