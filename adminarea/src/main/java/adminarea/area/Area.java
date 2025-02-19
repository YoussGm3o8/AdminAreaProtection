package adminarea.area;

import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class Area {
    private final AreaDTO dto;
    private final Map<String, Boolean> effectivePermissionCache;
    private final AreaPermissionHandler permissionHandler;
    
    Area(AreaDTO dto) {
        this.dto = dto;
        this.effectivePermissionCache = new ConcurrentHashMap<>();
        this.permissionHandler = new AreaPermissionHandler(dto.groupPermissions(), dto.inheritedPermissions());
    }

    public static AreaBuilder builder() {
        return new AreaBuilder();
    }

    public boolean isInside(String world, double x, double y, double z) {
        if (!dto.world().equals(world)) return false;
        var bounds = dto.bounds();
        return x >= bounds.xMin() && x <= bounds.xMax() &&
               y >= bounds.yMin() && y <= bounds.yMax() &&
               z >= bounds.zMin() && z <= bounds.zMax();
    }

    public boolean getEffectivePermission(String group, String permission) {
        String cacheKey = group + ":" + permission;
        return effectivePermissionCache.computeIfAbsent(cacheKey,
            k -> permissionHandler.calculateEffectivePermission(group, permission));
    }

    public void setToggleState(String toggle, boolean state) {
        // Get or create toggleStates JSONObject from DTO
        JSONObject toggleStates = dto.toggleStates();
        if (toggleStates == null) {
            toggleStates = new JSONObject();
        }

        // Update the toggle state
        toggleStates.put(toggle, state);
        
        // Clear any cached permissions since toggle state changed
        effectivePermissionCache.clear();
    }

    public boolean getToggleState(String toggle) {
        return dto.toggleStates().optBoolean(toggle, true); // Default to true if not set
    }

    public AreaDTO toDTO() {
        return dto;
    }

    // Simple getters that delegate to DTO
    public String getName() { return dto.name(); }
    public String getWorld() { return dto.world(); }
    public AreaDTO.Bounds getBounds() { return dto.bounds(); }
    public int getPriority() { return dto.priority(); }
}