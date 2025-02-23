package adminarea.event;

import adminarea.area.Area;
import cn.nukkit.event.HandlerList;
import cn.nukkit.event.plugin.PluginEvent;
import cn.nukkit.plugin.Plugin;

public class AreaDeletedEvent extends PluginEvent {
    private static final HandlerList handlers = new HandlerList();
    private final Area area;

    public AreaDeletedEvent(Plugin plugin, Area area) {
        super(plugin);
        this.area = area;
    }

    public Area getArea() {
        return area;
    }

    public static HandlerList getHandlers() {
        return handlers;
    }
} 