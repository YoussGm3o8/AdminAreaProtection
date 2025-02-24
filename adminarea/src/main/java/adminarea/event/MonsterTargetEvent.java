package adminarea.event;

import cn.nukkit.Player;
import cn.nukkit.entity.mob.EntityMob;
import cn.nukkit.event.Cancellable;
import cn.nukkit.event.HandlerList;
import cn.nukkit.event.Event;

public class MonsterTargetEvent extends Event implements Cancellable {
    private static final HandlerList handlers = new HandlerList();
    private final EntityMob monster;
    private final Player target;
    private boolean cancelled = false;

    public MonsterTargetEvent(EntityMob monster, Player target) {
        this.monster = monster;
        this.target = target;
    }

    public EntityMob getMonster() {
        return monster;
    }

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