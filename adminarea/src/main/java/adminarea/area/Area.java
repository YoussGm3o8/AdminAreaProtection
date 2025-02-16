package adminarea.area;

import org.json.JSONObject;

import adminarea.permissions.PermissionToggle;
import adminarea.util.ValidationUtils;
import java.util.HashMap;
import java.util.Map;

public final class Area {
    private final String name;
    private final String world;
    private final int xMin, xMax, yMin, yMax, zMin, zMax;
    private final int priority;
    private final boolean showTitle;
    private final JSONObject settings;
    private JSONObject groupPermissions;
    private String enterMessage;
    private String leaveMessage;
    
        // Package-private constructor - only accessible by builder
        Area(String name, String world, int xMin, int xMax, int yMin, int yMax, 
             int zMin, int zMax, int priority, boolean showTitle) {
            ValidationUtils.validateAreaName(name);
            ValidationUtils.validateCoordinates(xMin, xMax, "X");
            ValidationUtils.validateCoordinates(yMin, yMax, "Y");
            ValidationUtils.validateCoordinates(zMin, zMax, "Z");
            ValidationUtils.validatePriority(priority);
    
            this.name = name;
            this.world = world;
            this.xMin = Math.min(xMin, xMax);
            this.xMax = Math.max(xMin, xMax);
            this.yMin = Math.min(yMin, yMax);
            this.yMax = Math.max(yMin, yMax);
            this.zMin = Math.min(zMin, zMax);
            this.zMax = Math.max(zMin, zMax);
            this.priority = priority;
            this.showTitle = showTitle;
            this.settings = new JSONObject();
            this.groupPermissions = new JSONObject();
            initializeDefaultSettings();
        }
    
        // Create a new instance with updated settings
        public Area withSettings(JSONObject newSettings) {
            Area newArea = new Area(name, world, xMin, xMax, yMin, yMax, zMin, zMax, priority, showTitle);
            for (String key : newSettings.keySet()) {
                newArea.settings.put(key, newSettings.get(key));
            }
            return newArea;
        }
    
        // Create a new instance with updated group permissions
        public Area withGroupPermissions(JSONObject newPermissions) {
            Area newArea = new Area(name, world, xMin, xMax, yMin, yMax, zMin, zMax, priority, showTitle);
            for (String key : newPermissions.keySet()) {
                newArea.groupPermissions.put(key, newPermissions.get(key));
            }
            return newArea;
        }
    
        private void initializeDefaultSettings() {
            for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
                settings.put(toggle.getPermissionNode(), toggle.getDefaultValue());
            }
        }
    
        public boolean getSetting(String key) {
            return settings.optBoolean(key, true); // Returns true if setting doesn't exist
        }
    
        // Static method to get a builder
        public static AreaBuilder builder() {
            return new AreaBuilder();
        }
    
        // Getters
        public String getName() { return name; }
        public String getWorld() { return world; }
        public int getXMin() { return xMin; }
        public int getXMax() { return xMax; }
        public int getYMin() { return yMin; }
        public int getYMax() { return yMax; }
        public int getZMin() { return zMin; }
        public int getZMax() { return zMax; }
        public int getPriority() { return priority; }
        public boolean isShowTitle() { return showTitle; }
        public JSONObject getSettings() { return settings; }

        public void setSettings(JSONObject newSettings) {
            // First clear the settings
            settings.clear();
            
            // Initialize with defaults
            initializeDefaultSettings();
            
            // Then apply new settings if provided
            if (newSettings != null) {
                for (String key : newSettings.keySet()) {
                    settings.put(key, newSettings.get(key));
                }
            }
        }
    
        // Check whether the given location is inside the area.
        public boolean isInside(String world, double x, double y, double z) {
            if (!this.world.equals(world)) return false;
            return (x >= xMin && x <= xMax &&
                    y >= yMin && y <= yMax &&
                    z >= zMin && z <= zMax);
        }
    
        public void setGroupPermission(String group, String permission, boolean value) {
            if (!groupPermissions.has(group)) {
                groupPermissions.put(group, new JSONObject());
            }
            groupPermissions.getJSONObject(group).put(permission, value);
        }
    
        public boolean getGroupPermission(String group, String permission) {
            if (groupPermissions.has(group)) {
                JSONObject groupPerms = groupPermissions.getJSONObject(group);
                return groupPerms.optBoolean(permission, true);
            }
            return true; // Default to allowed if no specific permission is set
        }
    
        public JSONObject getGroupPermissions() {
            return groupPermissions;
        }
    
        public void setGroupPermissions(JSONObject permissions) {
            this.groupPermissions = permissions;
        }

        public Map<String, Object> serialize() {
            Map<String, Object> serialized = new HashMap<>();
            serialized.put("name", name);
            serialized.put("world", world);
            serialized.put("xMin", xMin);
            serialized.put("xMax", xMax);
            serialized.put("yMin", yMin);
            serialized.put("yMax", yMax);
            serialized.put("zMin", zMin);
            serialized.put("zMax", zMax);
            serialized.put("priority", priority);
            serialized.put("showTitle", showTitle);
            serialized.put("settings", settings.toString());
            serialized.put("groupPermissions", groupPermissions.toString());
            if (enterMessage != null) {
                serialized.put("enterMessage", enterMessage);
            }
            if (leaveMessage != null) {
                serialized.put("leaveMessage", leaveMessage);
            }
            return serialized;
        }

        public String getEnterMessage() {
            return enterMessage != null ? enterMessage : "Welcome to " + name + "!";
        }
    
        public String getLeaveMessage() {
            return leaveMessage != null ? leaveMessage : "Goodbye from " + name + "!";
        }
    
        public void setEnterMessage(String message) {
            this.enterMessage = message;
        }
    
        public void setLeaveMessage(String message) {
            this.leaveMessage = message;
        }
    
        public static Area deserialize(JSONObject json) {
            Area area = Area.builder()
                .name(json.getString("name"))
                .world(json.getString("world"))
                .coordinates(
                    json.getInt("xMin"),
                    json.getInt("xMax"),
                    json.getInt("yMin"),
                    json.getInt("yMax"),
                    json.getInt("zMin"),
                    json.getInt("zMax")
                )
                .priority(json.getInt("priority"))
                .showTitle(json.optBoolean("showTitle", true))
                .settings(new JSONObject(json.optString("settings", "{}")))
                .build();

            if (json.has("enterMessage")) {
                area.setEnterMessage(json.getString("enterMessage"));
            }
            if (json.has("leaveMessage")) {
                area.setLeaveMessage(json.getString("leaveMessage"));
            }
            if (json.has("groupPermissions")) {
                area.setGroupPermissions(new JSONObject(json.getString("groupPermissions")));
            }
            
            return area;
        }
}
