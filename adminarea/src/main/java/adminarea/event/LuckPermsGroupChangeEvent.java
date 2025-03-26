package adminarea.event;

import adminarea.area.Area;
import cn.nukkit.event.Event;
import cn.nukkit.event.HandlerList;

import java.util.Map;

public class LuckPermsGroupChangeEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final Area area;
    private final String group;
    private final Map<String, Boolean> oldPermissions;
    private final Map<String, Boolean> newPermissions;
    private boolean cancelled;

    public LuckPermsGroupChangeEvent(Area area, String group,
                                   Map<String, Boolean> oldPermissions,
                                   Map<String, Boolean> newPermissions) {
        this.area = area;
        this.group = group;
        this.oldPermissions = oldPermissions;
        this.newPermissions = newPermissions;
        this.cancelled = false;
    }

    public Area getArea() {
        return area;
    }

    public String getGroup() {
        return group;
    }

    public Map<String, Boolean> getOldPermissions() {
        return oldPermissions;
    }

    public Map<String, Boolean> getNewPermissions() {
        return newPermissions;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }

    public static HandlerList getHandlers() {
        return handlers;
    }
}
