package adminarea.area;

/**
 * A simple wrapper class that pairs an Area with a version timestamp.
 * Used for caching areas with their last updated time.
 */
public class VersionedArea {
    private final Area area;
    private final long version;

    /**
     * Creates a new VersionedArea with the specified area and version.
     *
     * @param area The area to wrap
     * @param version The version timestamp (typically System.currentTimeMillis())
     */
    public VersionedArea(Area area, long version) {
        this.area = area;
        this.version = version;
    }

    /**
     * Gets the wrapped area.
     *
     * @return The area
     */
    public Area getArea() {
        return area;
    }

    /**
     * Gets the version timestamp.
     *
     * @return The version timestamp
     */
    public long getVersion() {
        return version;
    }
} 