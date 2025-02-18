package adminarea.event;

import adminarea.area.Area;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.event.Event;
import cn.nukkit.event.HandlerList;

import java.util.Map;

public class AreaPermissionUpdateEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final Area area;
    private final PermissionToggle.Category category;
    private final Map<String, Boolean> oldPermissions;
    private final Map<String, Boolean> newPermissions;
    private boolean cancelled;

    public AreaPermissionUpdateEvent(Area area, PermissionToggle.Category category, 
                                   Map<String, Boolean> oldPermissions,
                                   Map<String, Boolean> newPermissions) {
        this.area = area;
        this.category = category;
        this.oldPermissions = oldPermissions;
        this.newPermissions = newPermissions;
        this.cancelled = false;
    }

    public Area getArea() {
        return area;
    }

    public PermissionToggle.Category getCategory() {
        return category;
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
