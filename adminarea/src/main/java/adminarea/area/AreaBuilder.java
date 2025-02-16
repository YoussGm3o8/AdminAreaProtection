package adminarea.area;

import org.json.JSONObject;

public class AreaBuilder {
    private String name;
    private String world;
    private int xMin, xMax, yMin, yMax, zMin, zMax;
    private int priority;
    private boolean showTitle;
    private JSONObject settings;

    public AreaBuilder() {
        this.settings = new JSONObject();
        this.priority = 0;
        this.showTitle = true;
    }

    public AreaBuilder name(String name) {
        this.name = name;
        return this;
    }

    public AreaBuilder world(String world) {
        this.world = world;
        return this;
    }

    public AreaBuilder coordinates(int xMin, int xMax, int yMin, int yMax, int zMin, int zMax) {
        this.xMin = Math.min(xMin, xMax);
        this.xMax = Math.max(xMin, xMax);
        this.yMin = Math.min(yMin, yMax);
        this.yMax = Math.max(yMin, yMax);
        this.zMin = Math.min(zMin, zMax);
        this.zMax = Math.max(zMin, zMax);
        return this;
    }

    public AreaBuilder priority(int priority) {
        this.priority = priority;
        return this;
    }

    public AreaBuilder showTitle(boolean showTitle) {
        this.showTitle = showTitle;
        return this;
    }

    public AreaBuilder settings(JSONObject settings) {
        this.settings = settings;
        return this;
    }

    public Area build() {
        if (name == null || world == null) {
            throw new IllegalStateException("Area name and world must be set");
        }
        
        // Create area first
        Area area = new Area(name, world, xMin, xMax, yMin, yMax, zMin, zMax, priority, showTitle);
        
        // Apply settings if they exist, otherwise defaults will be used
        if (settings != null && !settings.isEmpty()) {
            area.setSettings(settings);
        }
        
        return area;
    }
}
