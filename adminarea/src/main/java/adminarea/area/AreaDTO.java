package adminarea.area;

import org.json.JSONObject;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

public record AreaDTO(
    String name,
    String world,
    Bounds bounds,
    int priority,
    boolean showTitle,
    JSONObject settings,
    Map<String, Map<String, Boolean>> groupPermissions,
    Map<String, Map<String, Boolean>> inheritedPermissions,
    JSONObject toggleStates,
    JSONObject defaultToggleStates,
    JSONObject inheritedToggleStates,
    Permissions permissions,
    String enterMessage,
    String leaveMessage,
    Map<String, Map<String, Boolean>> trackPermissions,
    Map<String, Map<String, Boolean>> playerPermissions,
    JSONObject potionEffects
) {
    private static final Bounds GLOBAL_BOUNDS = new Bounds(
        -29000000, 29000000,
        -64, 320,
        -29000000, 29000000
    );

    public record Bounds(
        int xMin, int xMax,
        int yMin, int yMax, 
        int zMin, int zMax
    ) {
        public static Bounds createGlobal() {
            return GLOBAL_BOUNDS;
        }

        public Bounds {
            // Validate and normalize bounds
            if (xMin > xMax) {
                int temp = xMin;
                xMin = xMax;
                xMax = temp;
            }
            if (yMin > yMax) {
                int temp = yMin;
                yMin = yMax;
                yMax = temp;
            }
            if (zMin > zMax) {
                int temp = zMin;
                zMin = zMax;
                zMax = temp;
            }
        }

        public boolean contains(double x, double y, double z) {
            // Fast path for global bounds
            if (this == GLOBAL_BOUNDS) {
                return y >= yMin && y <= yMax; // Only check Y bounds for global areas
            }
            
            return x >= xMin && x <= xMax &&
                   y >= yMin && y <= yMax &&
                   z >= zMin && z <= zMax;
        }

        public boolean isGlobal() {
            return this == GLOBAL_BOUNDS;
        }

        public long volume() {
            return (long)(xMax - xMin + 1) * 
                   (long)(yMax - yMin + 1) * 
                   (long)(zMax - zMin + 1);
        }
    }
    
    public record Permissions(
        boolean allowBlockBreak,
        boolean allowBlockPlace,
        boolean allowInteract,
        boolean allowPvP,
        boolean allowMobSpawn,
        boolean allowRedstone,
        boolean allowBuild,
        boolean allowBreak,
        boolean allowContainer,
        boolean allowItemFrame,
        boolean allowArmorStand,
        boolean allowPistons,
        boolean allowHopper,
        boolean allowDispenser,
        boolean allowFire,
        boolean allowLiquid,
        boolean allowBlockSpread,
        boolean allowLeafDecay,
        boolean allowIceForm,
        boolean allowSnowForm,
        boolean allowMonsterSpawn,
        boolean allowAnimalSpawn,
        boolean allowDamageEntities,
        boolean allowBreeding,
        boolean allowMonsterTarget,
        boolean allowLeashing,
        boolean allowItemDrop,
        boolean allowItemPickup,
        boolean allowXPDrop,
        boolean allowXPPickup,
        boolean allowTNT,
        boolean allowCreeper,
        boolean allowBedExplosion,
        boolean allowCrystalExplosion,
        boolean allowVehiclePlace,
        boolean allowVehicleBreak,
        boolean allowVehicleEnter,
        boolean allowVehicleCollide,
        boolean allowFallDamage,
        boolean allowHunger,
        boolean allowFlight,
        boolean allowEnderPearl,
        boolean allowChorusFruit
    ) {
        public Map<String, Boolean> toMap() {
            Map<String, Boolean> map = new HashMap<>();
            map.put("allowBlockBreak", allowBlockBreak);
            map.put("allowBlockPlace", allowBlockPlace);
            map.put("allowInteract", allowInteract);
            map.put("allowPvP", allowPvP);
            map.put("allowMobSpawn", allowMobSpawn);
            map.put("allowRedstone", allowRedstone);
            map.put("allowBuild", allowBuild);
            map.put("allowBreak", allowBreak);
            map.put("allowContainer", allowContainer);
            map.put("allowItemFrame", allowItemFrame);
            map.put("allowArmorStand", allowArmorStand);
            map.put("allowPistons", allowPistons);
            map.put("allowHopper", allowHopper);
            map.put("allowDispenser", allowDispenser);
            map.put("allowFire", allowFire);
            map.put("allowLiquid", allowLiquid);
            map.put("allowBlockSpread", allowBlockSpread);
            map.put("allowLeafDecay", allowLeafDecay);
            map.put("allowIceForm", allowIceForm);
            map.put("allowSnowForm", allowSnowForm);
            map.put("allowMonsterSpawn", allowMonsterSpawn);
            map.put("allowAnimalSpawn", allowAnimalSpawn);
            map.put("allowDamageEntities", allowDamageEntities);
            map.put("allowBreeding", allowBreeding);
            map.put("allowMonsterTarget", allowMonsterTarget);
            map.put("allowLeashing", allowLeashing);
            map.put("allowItemDrop", allowItemDrop);
            map.put("allowItemPickup", allowItemPickup);
            map.put("allowXPDrop", allowXPDrop);
            map.put("allowXPPickup", allowXPPickup);
            map.put("allowTNT", allowTNT);
            map.put("allowCreeper", allowCreeper);
            map.put("allowBedExplosion", allowBedExplosion);
            map.put("allowCrystalExplosion", allowCrystalExplosion);
            map.put("allowVehiclePlace", allowVehiclePlace);
            map.put("allowVehicleBreak", allowVehicleBreak);
            map.put("allowVehicleEnter", allowVehicleEnter);
            map.put("allowVehicleCollide", allowVehicleCollide);
            map.put("allowFallDamage", allowFallDamage);
            map.put("allowHunger", allowHunger);
            map.put("allowFlight", allowFlight);
            map.put("allowEnderPearl", allowEnderPearl);
            map.put("allowChorusFruit", allowChorusFruit);
            return map;
        }

        public static Permissions fromMap(Map<String, Boolean> map) {
            return new Permissions(
                map.getOrDefault("allowBlockBreak", false),
                map.getOrDefault("allowBlockPlace", false),
                map.getOrDefault("allowInteract", false),
                map.getOrDefault("allowPvP", false),
                map.getOrDefault("allowMobSpawn", true),
                map.getOrDefault("allowRedstone", true),
                map.getOrDefault("allowBuild", false),
                map.getOrDefault("allowBreak", false),
                map.getOrDefault("allowContainer", false),
                map.getOrDefault("allowItemFrame", false),
                map.getOrDefault("allowArmorStand", false),
                map.getOrDefault("allowPistons", true),
                map.getOrDefault("allowHopper", true),
                map.getOrDefault("allowDispenser", true),
                map.getOrDefault("allowFire", false),
                map.getOrDefault("allowLiquid", true),
                map.getOrDefault("allowBlockSpread", true),
                map.getOrDefault("allowLeafDecay", true),
                map.getOrDefault("allowIceForm", true),
                map.getOrDefault("allowSnowForm", true),
                map.getOrDefault("allowMonsterSpawn", false),
                map.getOrDefault("allowAnimalSpawn", true),
                map.getOrDefault("allowDamageEntities", false),
                map.getOrDefault("allowBreeding", true),
                map.getOrDefault("allowMonsterTarget", false),
                map.getOrDefault("allowLeashing", true),
                map.getOrDefault("allowItemDrop", true),
                map.getOrDefault("allowItemPickup", true),
                map.getOrDefault("allowXPDrop", true),
                map.getOrDefault("allowXPPickup", true),
                map.getOrDefault("allowTNT", false),
                map.getOrDefault("allowCreeper", false),
                map.getOrDefault("allowBedExplosion", false),
                map.getOrDefault("allowCrystalExplosion", false),
                map.getOrDefault("allowVehiclePlace", true),
                map.getOrDefault("allowVehicleBreak", false),
                map.getOrDefault("allowVehicleEnter", true),
                map.getOrDefault("allowVehicleCollide", true),
                map.getOrDefault("allowFallDamage", true),
                map.getOrDefault("allowHunger", true),
                map.getOrDefault("allowFlight", false),
                map.getOrDefault("allowEnderPearl", false),
                map.getOrDefault("allowChorusFruit", false)
            );
        }
    }

    public AreaDTO {
        // Validate required fields
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Area name cannot be null or empty");
        }
        if (world == null || world.trim().isEmpty()) {
            throw new IllegalArgumentException("World name cannot be null or empty");
        }
        if (bounds == null) {
            throw new IllegalArgumentException("Bounds cannot be null");
        }
        if (permissions == null) {
            throw new IllegalArgumentException("Permissions cannot be null");
        }

        // Initialize optional fields with defaults if null
        settings = settings != null ? settings : new JSONObject();
        groupPermissions = groupPermissions != null ? groupPermissions : new HashMap<>();
        inheritedPermissions = inheritedPermissions != null ? inheritedPermissions : new HashMap<>();
        toggleStates = toggleStates != null ? toggleStates : new JSONObject();
        defaultToggleStates = defaultToggleStates != null ? defaultToggleStates : new JSONObject();
        inheritedToggleStates = inheritedToggleStates != null ? inheritedToggleStates : new JSONObject();
        enterMessage = enterMessage != null ? enterMessage : "";
        leaveMessage = leaveMessage != null ? leaveMessage : "";
        trackPermissions = trackPermissions != null ? trackPermissions : new HashMap<>();
        playerPermissions = playerPermissions != null ? playerPermissions : new HashMap<>();
        potionEffects = potionEffects != null ? potionEffects : new JSONObject();
    }

    // Override accessors to return mutable copies
    @Override
    public Map<String, Map<String, Boolean>> groupPermissions() {
        return new HashMap<>(groupPermissions);
    }

    @Override
    public Map<String, Map<String, Boolean>> inheritedPermissions() {
        return new HashMap<>(inheritedPermissions);
    }

    @Override
    public Map<String, Map<String, Boolean>> trackPermissions() {
        return new HashMap<>(trackPermissions);
    }

    @Override
    public Map<String, Map<String, Boolean>> playerPermissions() {
        return new HashMap<>(playerPermissions);
    }
}
