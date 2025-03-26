package adminarea.event;

import adminarea.area.Area;
import cn.nukkit.event.HandlerList;
import cn.nukkit.event.Cancellable;
import cn.nukkit.event.plugin.PluginEvent;
import cn.nukkit.plugin.Plugin;

public class AreaModifiedEvent extends PluginEvent implements Cancellable {
    private static final HandlerList handlers = new HandlerList();
    private final Area oldArea;
    private final Area newArea;
    private boolean cancelled = false;

    public AreaModifiedEvent(Plugin plugin, Area oldArea, Area newArea) {
        super(plugin);
        this.oldArea = oldArea;
        this.newArea = newArea;
    }

    public Area getOldArea() {
        return oldArea;
    }

    public Area getNewArea() {
        return newArea;
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
