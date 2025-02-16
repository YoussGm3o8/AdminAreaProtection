package adminarea.events;

import adminarea.area.Area;
import cn.nukkit.event.HandlerList;
import cn.nukkit.event.Cancellable;
import cn.nukkit.event.plugin.PluginEvent;
import cn.nukkit.plugin.Plugin;

public class AreaCreatedEvent extends PluginEvent implements Cancellable {
    private static final HandlerList handlers = new HandlerList();
    private final Area area;
    private boolean cancelled = false;

    public AreaCreatedEvent(Plugin plugin, Area area) {
        super(plugin);
        this.area = area;
    }

    public Area getArea() {
        return area;
    }

    public static HandlerList getHandlers() {
        return handlers;
    }

    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
