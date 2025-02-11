package adminarea;

import org.json.JSONObject;

public class Area {
    private String name;
    private String world;
    private int xMin, xMax, yMin, yMax, zMin, zMax;
    private int priority;
    private boolean showTitle;
    private JSONObject settings;

    public Area(String name, String world, int xMin, int xMax, int yMin, int yMax, int zMin, int zMax, int priority, boolean showTitle) {
        this.name = name;
        this.world = world;
        // Ensure min and max are set correctly
        this.xMin = Math.min(xMin, xMax);
        this.xMax = Math.max(xMin, xMax);
        this.yMin = Math.min(yMin, yMax);
        this.yMax = Math.max(yMin, yMax);
        this.zMin = Math.min(zMin, zMax);
        this.zMax = Math.max(zMin, zMax);
        this.priority = priority;
        this.showTitle = showTitle;
        this.settings = new JSONObject();
    }

    // Getters and setters
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
    public void setSettings(JSONObject settings) { this.settings = settings; }

    public void setName(String name) { 
        this.name = name; 
    }
    public void setWorld(String world) { this.world = world; }
    public void setXMin(int xMin) { this.xMin = xMin; }
    public void setXMax(int xMax) { this.xMax = xMax; }
    public void setYMin(int yMin) { this.yMin = yMin; }
    public void setYMax(int yMax) { this.yMax = yMax; }
    public void setZMin(int zMin) { this.zMin = zMin; }
    public void setZMax(int zMax) { this.zMax = zMax; }
    public void setPriority(int priority) { this.priority = priority; }
    public void setShowTitle(boolean showTitle) { this.showTitle = showTitle; }

    // Check whether the given location is inside the area.
    public boolean isInside(String world, double x, double y, double z) {
        if (!this.world.equals(world)) return false;
        return (x >= xMin && x <= xMax &&
                y >= yMin && y <= yMax &&
                z >= zMin && z <= zMax);
    }
}
