package adminarea.area;

import org.json.JSONObject;
import java.util.Map;
import java.util.HashMap;

public class AreaSerializer {

    public static Map<String, Object> serialize(Area area) {
        var dto = area.toDTO();
        Map<String, Object> data = new HashMap<>();
        
        data.put("name", dto.name());
        data.put("world", dto.world());
        data.put("bounds", Map.of(
            "xMin", dto.bounds().xMin(),
            "xMax", dto.bounds().xMax(),
            "yMin", dto.bounds().yMin(),
            "yMax", dto.bounds().yMax(),
            "zMin", dto.bounds().zMin(),
            "zMax", dto.bounds().zMax()
        ));
        data.put("priority", dto.priority());
        data.put("showTitle", dto.showTitle());
        data.put("settings", dto.settings().toMap());
        data.put("groupPermissions", dto.groupPermissions());
        data.put("inheritedPermissions", dto.inheritedPermissions());
        data.put("toggleStates", dto.toggleStates().toMap());
        data.put("defaultToggleStates", dto.defaultToggleStates().toMap());
        data.put("inheritedToggleStates", dto.inheritedToggleStates().toMap());
        data.put("permissions", dto.permissions().toMap());
        data.put("enterMessage", dto.enterMessage());
        data.put("leaveMessage", dto.leaveMessage());
        
        return data;
    }

    @SuppressWarnings("unchecked")
    public static Area deserialize(Map<String, Object> data) {
        // Validate required fields
        if (data == null || !data.containsKey("name") || !data.containsKey("world") || !data.containsKey("bounds")) {
            throw new IllegalArgumentException("Missing required fields in area data");
        }

        // Extract bounds data
        Map<String, Object> bounds = (Map<String, Object>) data.get("bounds");
        if (!bounds.containsKey("xMin") || !bounds.containsKey("xMax") ||
            !bounds.containsKey("yMin") || !bounds.containsKey("yMax") ||
            !bounds.containsKey("zMin") || !bounds.containsKey("zMax")) {
            throw new IllegalArgumentException("Invalid bounds data");
        }

        // Convert settings and permissions to JSONObject
        JSONObject settings = new JSONObject(data.getOrDefault("settings", new HashMap<>()));
        JSONObject toggleStates = new JSONObject(data.getOrDefault("toggleStates", new HashMap<>()));
        JSONObject defaultToggleStates = new JSONObject(data.getOrDefault("defaultToggleStates", new HashMap<>()));
        JSONObject inheritedToggleStates = new JSONObject(data.getOrDefault("inheritedToggleStates", new HashMap<>()));

        // Create bounds record
        AreaDTO.Bounds areaBounds = new AreaDTO.Bounds(
            ((Number) bounds.get("xMin")).intValue(),
            ((Number) bounds.get("xMax")).intValue(),
            ((Number) bounds.get("yMin")).intValue(),
            ((Number) bounds.get("yMax")).intValue(),
            ((Number) bounds.get("zMin")).intValue(),
            ((Number) bounds.get("zMax")).intValue()
        );

        // Convert group permissions
        Map<String, Map<String, Boolean>> groupPermissions = 
            (Map<String, Map<String, Boolean>>) data.getOrDefault("groupPermissions", new HashMap<>());
        Map<String, Map<String, Boolean>> inheritedPermissions = 
            (Map<String, Map<String, Boolean>>) data.getOrDefault("inheritedPermissions", new HashMap<>());

        // Create permissions record with full field mapping
        Map<String, Boolean> permMap = (Map<String, Boolean>) data.getOrDefault("permissions", new HashMap<>());
        AreaDTO.Permissions permissions = AreaDTO.Permissions.fromMap(permMap);

        // Create DTO
        AreaDTO dto = new AreaDTO(
            (String) data.get("name"),
            (String) data.get("world"),
            areaBounds,
            ((Number) data.getOrDefault("priority", 0)).intValue(),
            (Boolean) data.getOrDefault("showTitle", true),
            settings,
            groupPermissions,
            inheritedPermissions,
            toggleStates,
            defaultToggleStates,
            inheritedToggleStates,
            permissions,
            (String) data.getOrDefault("enterMessage", ""),
            (String) data.getOrDefault("leaveMessage", "")
        );

        // Create Area instance using private constructor via reflection
        try {
            return AreaBuilder
                .fromDTO(dto)
                .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create Area instance", e);
        }
    }
}
