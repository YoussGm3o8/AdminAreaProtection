package adminarea.event;

import cn.nukkit.Player;
import cn.nukkit.event.Cancellable;
import cn.nukkit.event.Event;
import cn.nukkit.event.HandlerList;
import nukkitcoders.mobplugin.entities.monster.WalkingMonster;

/**
 * Called when a monster targets a player in a protected area
 * Compatible with MobPlugin monsters
 */
public class MonsterTargetEvent extends Event implements Cancellable {
    private static final HandlerList handlers = new HandlerList();
    
    private final WalkingMonster entity;
    private final Player target;
    private boolean cancelled = false;
    
    public MonsterTargetEvent(WalkingMonster entity, Player target) {
        this.entity = entity;
        this.target = target;
    }
    
    /**
     * Get the monster entity that is targeting a player
     * 
     * @return The monster entity
     */
    public WalkingMonster getEntity() {
        return entity;
    }
    
    /**
     * Get the player being targeted by the monster
     * 
     * @return The player target
     */
    public Player getTarget() {
        return target;
    }
    
    @Override
    public boolean isCancelled() {
        return cancelled;
    }
    
    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
    
    public static HandlerList getHandlers() {
        return handlers;
    }
}