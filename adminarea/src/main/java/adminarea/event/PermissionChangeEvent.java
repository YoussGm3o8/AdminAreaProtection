package adminarea.event;

import adminarea.area.Area;
import cn.nukkit.event.Event;
import cn.nukkit.event.HandlerList;

public class PermissionChangeEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final Area area;
    private final String group;
    private final String permission;
    private final boolean oldValue;
    private final boolean newValue;
    private boolean cancelled;

    public PermissionChangeEvent(Area area, String group, String permission, boolean oldValue, boolean newValue) {
        this.area = area;
        this.group = group;
        this.permission = permission;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.cancelled = false;
    }

    public Area getArea() {
        return area;
    }

    public String getGroup() {
        return group;
    }

    public String getPermission() {
        return permission;
    }

    public boolean getOldValue() {
        return oldValue;
    }

    public boolean getNewValue() {
        return newValue;
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
