package adminarea.event;

import adminarea.area.Area;
import cn.nukkit.event.Event;
import cn.nukkit.event.HandlerList;

/**
 * Called when a LuckPerms track permission changes in an area
 */
public class LuckPermsTrackChangeEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    
    private final Area area;
    private final String track;
    private final String permission;
    private final boolean oldValue;
    private final boolean newValue;
    
    /**
     * @param area       The area where the permission changed
     * @param track      The track name
     * @param permission The permission that changed
     * @param oldValue   The old permission value
     * @param newValue   The new permission value
     */
    public LuckPermsTrackChangeEvent(Area area, String track, String permission, boolean oldValue, boolean newValue) {
        this.area = area;
        this.track = track;
        this.permission = permission;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }
    
    public Area getArea() {
        return area;
    }
    
    public String getTrack() {
        return track;
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
    
    public static HandlerList getHandlers() {
        return handlers;
    }
} 