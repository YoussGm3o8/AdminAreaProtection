package adminarea.event;

import cn.nukkit.Player;
import cn.nukkit.event.Cancellable;
import cn.nukkit.event.Event;
import cn.nukkit.event.HandlerList;
import cn.nukkit.entity.Entity;

/**
 * Called when a monster targets a player in a protected area
 * Compatible with all monster entity types
 */
public class MonsterTargetEvent extends Event implements Cancellable {
    private static final HandlerList handlers = new HandlerList();
    
    private final Entity entity;
    private final Player target;
    private boolean cancelled = false;
    
    /**
     * Create a new monster target event
     * 
     * @param entity The monster entity targeting a player
     * @param target The player being targeted
     */
    public MonsterTargetEvent(Entity entity, Player target) {
        this.entity = entity;
        this.target = target;
    }
    
    /**
     * Get the monster entity that is targeting a player
     * 
     * @return The monster entity
     */
    public Entity getEntity() {
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