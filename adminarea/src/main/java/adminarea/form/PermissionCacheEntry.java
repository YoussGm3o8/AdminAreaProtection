package adminarea.form;

public record PermissionCacheEntry(String permission, boolean value, long timestamp) {
    public static PermissionCacheEntry create(String permission, boolean value) {
        return new PermissionCacheEntry(permission, value, System.currentTimeMillis());
    }
}

