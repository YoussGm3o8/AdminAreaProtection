package adminarea.area;

import org.json.JSONObject;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.permissions.PermissionToggle;
import adminarea.util.ValidationUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

public final class Area {
    private final String name;
    private final String world;
    private final int xMin, xMax, yMin, yMax, zMin, zMax;
    private final int priority;
    private final boolean showTitle;
    private final JSONObject settings;
    private final Map<String, Map<String, Boolean>> groupPermissions;
    private final Map<String, Map<String, Boolean>> inheritedPermissions;
    private final JSONObject toggleStates;
    private final JSONObject defaultToggleStates;
    private final JSONObject inheritedToggleStates;
    private final boolean allowBlockBreak;
    private final boolean allowBlockPlace;
    private final boolean allowInteract;
    private final boolean allowPvP;
    private final boolean allowMobSpawn;
    private final boolean allowRedstone;
    
    // Permission attributes
    private final boolean allowBuild;
    private final boolean allowBreak; 
    private final boolean allowContainer;
    private final boolean allowItemFrame;
    private final boolean allowArmorStand;
    private final boolean allowPistons;
    private final boolean allowHopper;
    private final boolean allowDispenser;
    private final boolean allowFire;
    private final boolean allowLiquid;
    private final boolean allowBlockSpread;
    private final boolean allowLeafDecay;
    private final boolean allowIceForm;
    private final boolean allowSnowForm;
    private final boolean allowMonsterSpawn;
    private final boolean allowAnimalSpawn;
    private final boolean allowDamageEntities;
    private final boolean allowBreeding;
    private final boolean allowMonsterTarget;
    private final boolean allowLeashing;
    private final boolean allowItemDrop;
    private final boolean allowItemPickup;
    private final boolean allowXPDrop;
    private final boolean allowXPPickup;
    private final boolean allowTNT;
    private final boolean allowCreeper;
    private final boolean allowBedExplosion;
    private final boolean allowCrystalExplosion;
    private final boolean allowVehiclePlace;
    private final boolean allowVehicleBreak;
    private final boolean allowVehicleEnter;
    private final boolean allowVehicleCollide;
    private final boolean allowFallDamage;
    private final boolean allowHunger;
    private final boolean allowFlight;
    private final boolean allowEnderPearl;
    private final boolean allowChorusFruit;
    private String enterMessage;
    private String leaveMessage;
    
    // Package-private constructor - only accessible by builder
    Area(String name, String world, int xMin, int xMax, int yMin, int yMax, 
         int zMin, int zMax, int priority, boolean showTitle,
         boolean allowBlockBreak, boolean allowBlockPlace, boolean allowInteract,
         boolean allowPvP, boolean allowMobSpawn, boolean allowRedstone,
         boolean allowBuild, boolean allowBreak, boolean allowContainer,
         boolean allowItemFrame, boolean allowArmorStand, boolean allowPistons,
         boolean allowHopper, boolean allowDispenser, boolean allowFire,
         boolean allowLiquid, boolean allowBlockSpread, boolean allowLeafDecay,
         boolean allowIceForm, boolean allowSnowForm, boolean allowMonsterSpawn,
         boolean allowAnimalSpawn, boolean allowDamageEntities, boolean allowBreeding,
         boolean allowMonsterTarget, boolean allowLeashing, boolean allowItemDrop,
         boolean allowItemPickup, boolean allowXPDrop, boolean allowXPPickup,
         boolean allowTNT, boolean allowCreeper, boolean allowBedExplosion,
         boolean allowCrystalExplosion, boolean allowVehiclePlace, boolean allowVehicleBreak,
         boolean allowVehicleEnter, boolean allowVehicleCollide, boolean allowFallDamage,
         boolean allowHunger, boolean allowFlight, boolean allowEnderPearl, boolean allowChorusFruit) {
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
        this.groupPermissions = new HashMap<>();
        this.inheritedPermissions = new HashMap<>();
        this.toggleStates = new JSONObject();
        this.defaultToggleStates = new JSONObject();
        this.inheritedToggleStates = new JSONObject();
        
        // Initialize new toggle properties
        this.allowBlockBreak = allowBlockBreak;
        this.allowBlockPlace = allowBlockPlace;
        this.allowInteract = allowInteract;
        this.allowPvP = allowPvP;
        this.allowMobSpawn = allowMobSpawn;
        this.allowRedstone = allowRedstone;
        
        this.allowBuild = allowBuild;
        this.allowBreak = allowBreak;
        this.allowContainer = allowContainer;
        this.allowItemFrame = allowItemFrame;
        this.allowArmorStand = allowArmorStand;
        this.allowPistons = allowPistons;
        this.allowHopper = allowHopper;
        this.allowDispenser = allowDispenser;
        this.allowFire = allowFire;
        this.allowLiquid = allowLiquid;
        this.allowBlockSpread = allowBlockSpread;
        this.allowLeafDecay = allowLeafDecay;
        this.allowIceForm = allowIceForm;
        this.allowSnowForm = allowSnowForm;
        this.allowMonsterSpawn = allowMonsterSpawn;
        this.allowAnimalSpawn = allowAnimalSpawn;
        this.allowDamageEntities = allowDamageEntities;
        this.allowBreeding = allowBreeding;
        this.allowMonsterTarget = allowMonsterTarget;
        this.allowLeashing = allowLeashing;
        this.allowItemDrop = allowItemDrop;
        this.allowItemPickup = allowItemPickup;
        this.allowXPDrop = allowXPDrop;
        this.allowXPPickup = allowXPPickup;
        this.allowTNT = allowTNT;
        this.allowCreeper = allowCreeper;
        this.allowBedExplosion = allowBedExplosion;
        this.allowCrystalExplosion = allowCrystalExplosion;
        this.allowVehiclePlace = allowVehiclePlace;
        this.allowVehicleBreak = allowVehicleBreak;
        this.allowVehicleEnter = allowVehicleEnter;
        this.allowVehicleCollide = allowVehicleCollide;
        this.allowFallDamage = allowFallDamage;
        this.allowHunger = allowHunger;
        this.allowFlight = allowFlight;
        this.allowEnderPearl = allowEnderPearl;
        this.allowChorusFruit = allowChorusFruit;
        
        initializeDefaultSettings();
    }

    // Create a new instance with updated settings
    public Area withSettings(JSONObject newSettings) {
        Area newArea = new Area(name, world, xMin, xMax, yMin, yMax, zMin, zMax, priority, showTitle,
            newSettings.optBoolean("allowBlockBreak", allowBlockBreak),
            newSettings.optBoolean("allowBlockPlace", allowBlockPlace),
            newSettings.optBoolean("allowInteract", allowInteract),
            newSettings.optBoolean("allowPvP", allowPvP),
            newSettings.optBoolean("allowMobSpawn", allowMobSpawn),
            newSettings.optBoolean("allowRedstone", allowRedstone),
            newSettings.optBoolean("allowBuild", allowBuild),
            newSettings.optBoolean("allowBreak", allowBreak),
            newSettings.optBoolean("allowContainer", allowContainer),
            newSettings.optBoolean("allowItemFrame", allowItemFrame),
            newSettings.optBoolean("allowArmorStand", allowArmorStand),
            newSettings.optBoolean("allowPistons", allowPistons),
            newSettings.optBoolean("allowHopper", allowHopper),
            newSettings.optBoolean("allowDispenser", allowDispenser),
            newSettings.optBoolean("allowFire", allowFire),
            newSettings.optBoolean("allowLiquid", allowLiquid),
            newSettings.optBoolean("allowBlockSpread", allowBlockSpread),
            newSettings.optBoolean("allowLeafDecay", allowLeafDecay),
            newSettings.optBoolean("allowIceForm", allowIceForm),
            newSettings.optBoolean("allowSnowForm", allowSnowForm),
            newSettings.optBoolean("allowMonsterSpawn", allowMonsterSpawn),
            newSettings.optBoolean("allowAnimalSpawn", allowAnimalSpawn),
            newSettings.optBoolean("allowDamageEntities", allowDamageEntities),
            newSettings.optBoolean("allowBreeding", allowBreeding),
            newSettings.optBoolean("allowMonsterTarget", allowMonsterTarget),
            newSettings.optBoolean("allowLeashing", allowLeashing),
            newSettings.optBoolean("allowItemDrop", allowItemDrop),
            newSettings.optBoolean("allowItemPickup", allowItemPickup),
            newSettings.optBoolean("allowXPDrop", allowXPDrop),
            newSettings.optBoolean("allowXPPickup", allowXPPickup),
            newSettings.optBoolean("allowTNT", allowTNT),
            newSettings.optBoolean("allowCreeper", allowCreeper),
            newSettings.optBoolean("allowBedExplosion", allowBedExplosion),
            newSettings.optBoolean("allowCrystalExplosion", allowCrystalExplosion),
            newSettings.optBoolean("allowVehiclePlace", allowVehiclePlace),
            newSettings.optBoolean("allowVehicleBreak", allowVehicleBreak),
            newSettings.optBoolean("allowVehicleEnter", allowVehicleEnter),
            newSettings.optBoolean("allowVehicleCollide", allowVehicleCollide),
            newSettings.optBoolean("allowFallDamage", allowFallDamage),
            newSettings.optBoolean("allowHunger", allowHunger),
            newSettings.optBoolean("allowFlight", allowFlight),
            newSettings.optBoolean("allowEnderPearl", allowEnderPearl),
            newSettings.optBoolean("allowChorusFruit", allowChorusFruit));

        // Copy over any other settings
        for (String key : newSettings.keySet()) {
            if (!key.startsWith("allow")) { // Skip permission fields as they're handled above
                newArea.settings.put(key, newSettings.get(key));
            }
        }
        return newArea;
    }

    // Create a new instance with updated group permissions
    public Area withGroupPermissions(JSONObject newPermissions) {
        Area newArea = new Area(name, world, xMin, xMax, yMin, yMax, zMin, zMax, priority, showTitle,
                                allowBlockBreak, allowBlockPlace, allowInteract, allowPvP, 
                                allowMobSpawn, allowRedstone, allowBuild, allowBreak, allowContainer,
                                allowItemFrame, allowArmorStand, allowPistons, allowHopper, allowDispenser,
                                allowFire, allowLiquid, allowBlockSpread, allowLeafDecay, allowIceForm,
                                allowSnowForm, allowMonsterSpawn, allowAnimalSpawn, allowDamageEntities,
                                allowBreeding, allowMonsterTarget, allowLeashing, allowItemDrop, allowItemPickup,
                                allowXPDrop, allowXPPickup, allowTNT, allowCreeper, allowBedExplosion,
                                allowCrystalExplosion, allowVehiclePlace, allowVehicleBreak, allowVehicleEnter,
                                allowVehicleCollide, allowFallDamage, allowHunger, allowFlight, allowEnderPearl,
                                allowChorusFruit);
        for (String key : newPermissions.keySet()) {
            JSONObject permObject = newPermissions.getJSONObject(key);
            Map<String, Boolean> permMap = new HashMap<>();
            for (String perm : permObject.keySet()) {
                permMap.put(perm, permObject.getBoolean(perm));
            }
            newArea.groupPermissions.put(key, permMap);
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
    public boolean getAllowBlockBreak() { return allowBlockBreak; }
    public boolean getAllowBlockPlace() { return allowBlockPlace; }
    public boolean getAllowInteract() { return allowInteract; }
    public boolean getAllowPvP() { return allowPvP; }
    public boolean getAllowMobSpawn() { return allowMobSpawn; }
    public boolean getAllowRedstone() { return allowRedstone; }
    public boolean getAllowBuild() { return allowBuild; }
    public boolean getAllowBreak() { return allowBreak; }
    public boolean getAllowContainer() { return allowContainer; }
    public boolean getAllowItemFrame() { return allowItemFrame; }
    public boolean getAllowArmorStand() { return allowArmorStand; }
    public boolean getAllowPistons() { return allowPistons; }
    public boolean getAllowHopper() { return allowHopper; }
    public boolean getAllowDispenser() { return allowDispenser; }
    public boolean getAllowFire() { return allowFire; }
    public boolean getAllowLiquid() { return allowLiquid; }
    public boolean getAllowBlockSpread() { return allowBlockSpread; }
    public boolean getAllowLeafDecay() { return allowLeafDecay; }
    public boolean getAllowIceForm() { return allowIceForm; }
    public boolean getAllowSnowForm() { return allowSnowForm; }
    public boolean getAllowMonsterSpawn() { return allowMonsterSpawn; }
    public boolean getAllowAnimalSpawn() { return allowAnimalSpawn; }
    public boolean getAllowDamageEntities() { return allowDamageEntities; }
    public boolean getAllowBreeding() { return allowBreeding; }
    public boolean getAllowMonsterTarget() { return allowMonsterTarget; }
    public boolean getAllowLeashing() { return allowLeashing; }
    public boolean getAllowItemDrop() { return allowItemDrop; }
    public boolean getAllowItemPickup() { return allowItemPickup; }
    public boolean getAllowXPDrop() { return allowXPDrop; }
    public boolean getAllowXPPickup() { return allowXPPickup; }
    public boolean getAllowTNT() { return allowTNT; }
    public boolean getAllowCreeper() { return allowCreeper; }
    public boolean getAllowBedExplosion() { return allowBedExplosion; }
    public boolean getAllowCrystalExplosion() { return allowCrystalExplosion; }
    public boolean getAllowVehiclePlace() { return allowVehiclePlace; }
    public boolean getAllowVehicleBreak() { return allowVehicleBreak; }
    public boolean getAllowVehicleEnter() { return allowVehicleEnter; }
    public boolean getAllowVehicleCollide() { return allowVehicleCollide; }
    public boolean getAllowFallDamage() { return allowFallDamage; }
    public boolean getAllowHunger() { return allowHunger; }
    public boolean getAllowFlight() { return allowFlight; }
    public boolean getAllowEnderPearl() { return allowEnderPearl; }
    public boolean getAllowChorusFruit() { return allowChorusFruit; }

    public void setSettings(JSONObject newSettings) {
        // Validate the new settings
        ValidationUtils.validateAreaSettings(newSettings);
        
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

    /**
     * Sets a permission value for a specific group
     * @param group The group name
     * @param permission The permission node
     * @param value The permission value
     */
    public void setGroupPermission(String group, String permission, boolean value) {
        groupPermissions.computeIfAbsent(group, k -> new HashMap<>())
                       .put(permission, value);
    }

    /**
     * Gets a permission value for a specific group
     * @param group The group name
     * @param permission The permission node
     * @return The permission value, defaulting to true if not set
     */
    public boolean getGroupPermission(String group, String permission) {
        return groupPermissions.getOrDefault(group, new HashMap<>())
                             .getOrDefault(permission, true);
    }

    /**
     * Gets all group permissions as a JSONObject
     * @return JSONObject containing all group permissions
     */
    public JSONObject getGroupPermissions() {
        JSONObject perms = new JSONObject();
        for (Map.Entry<String, Map<String, Boolean>> entry : groupPermissions.entrySet()) {
            JSONObject groupPerms = new JSONObject();
            for (Map.Entry<String, Boolean> permEntry : entry.getValue().entrySet()) {
                groupPerms.put(permEntry.getKey(), permEntry.getValue());
            }
            perms.put(entry.getKey(), groupPerms);
        }
        return perms;
    }

    /**
     * Gets all permissions for a group
     * @param group The group name 
     * @return Map of permissions and their values
     */
    public Map<String, Boolean> getGroupPermissions(String group) {
        return new HashMap<>(groupPermissions.getOrDefault(group, new HashMap<>()));
    }

    /**
     * Sets all permissions for a group
     * @param group The group name
     * @param permissions Map of permissions and their values
     */
    public void setGroupPermissions(String group, Map<String, Boolean> permissions) {
        groupPermissions.put(group, new HashMap<>(permissions));
    }

    /**
     * Gets all group permissions
     * @return Map of all group permissions
     */
    public Map<String, Map<String, Boolean>> getAllGroupPermissions() {
        return new HashMap<>(groupPermissions);
    }

    /**
     * Sets inherited permissions for a group
     * @param group The group name
     * @param permissions Map of inherited permissions
     */
    public void setInheritedPermissions(String group, Map<String, Boolean> permissions) {
        inheritedPermissions.put(group, new HashMap<>(permissions));
    }

    /**
     * Gets the effective permission value for a group, considering inheritance
     * @param group The group name
     * @param permission The permission node
     * @return The effective permission value
     */
    public boolean getEffectivePermission(String group, String permission) {
        // Get the plugin instance
        AdminAreaProtectionPlugin plugin = AdminAreaProtectionPlugin.getInstance();
        
        // If LuckPerms isn't enabled, default to true
        if (!plugin.isLuckPermsEnabled()) {
            return true;
        }
        
        // Check direct permissions first
        Map<String, Boolean> directPerms = groupPermissions.get(group);
        if (directPerms != null && directPerms.containsKey(permission)) {
            return directPerms.get(permission);
        }

        // Get inheritance chain from LuckPermsCache
        List<String> inheritance = plugin.getLuckPermsCache().getInheritanceChain(group);
        
        // Check inherited permissions in order (excluding the current group)
        for (String inheritedGroup : inheritance) {
            if (!inheritedGroup.equals(group)) {
                Map<String, Boolean> inheritedPerms = groupPermissions.get(inheritedGroup);
                if (inheritedPerms != null && inheritedPerms.containsKey(permission)) {
                    return inheritedPerms.get(permission);
                }
            }
        }

        // Default to true if no permission is set
        return true;
    }

    /**
     * Checks if a group has an explicit permission set
     */
    public boolean hasExplicitGroupPermission(String group, String permission) {
        Map<String, Boolean> groupPerms = groupPermissions.get(group);
        return groupPerms != null && groupPerms.containsKey(permission);
    }

    /**
     * Gets inherited permission value from parent groups
     */
    private boolean getInheritedPermission(String group, String permission) {
        Map<String, Boolean> inherited = inheritedPermissions.get(group);
        return inherited != null && inherited.getOrDefault(permission, false);
    }

    /**
     * Bulk update permissions for a category
     */
    public void updateCategoryPermissions(PermissionToggle.Category category, Map<String, Boolean> permissions) {
        ValidationUtils.validatePermissionMap(permissions);
        for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
            settings.put(entry.getKey(), entry.getValue());
        }
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
        serialized.put("settings", settings.toMap());
        serialized.put("groupPermissions", groupPermissions);
        serialized.put("inheritedPermissions", inheritedPermissions);
        serialized.put("toggleStates", toggleStates.toMap());
        serialized.put("defaultToggleStates", defaultToggleStates.toMap());
        serialized.put("inheritedToggleStates", inheritedToggleStates.toMap());
        if (enterMessage != null) {
            serialized.put("enterMessage", enterMessage);
        }
        if (leaveMessage != null) {
            serialized.put("leaveMessage", leaveMessage);
        }
        
        // Add new toggle properties
        serialized.put("allowBlockBreak", allowBlockBreak);
        serialized.put("allowBlockPlace", allowBlockPlace);
        serialized.put("allowInteract", allowInteract);
        serialized.put("allowPvP", allowPvP);
        serialized.put("allowMobSpawn", allowMobSpawn);
        serialized.put("allowRedstone", allowRedstone);
        
        serialized.put("allowBuild", allowBuild);
        serialized.put("allowBreak", allowBreak);
        serialized.put("allowContainer", allowContainer);
        serialized.put("allowItemFrame", allowItemFrame);
        serialized.put("allowArmorStand", allowArmorStand);
        serialized.put("allowPistons", allowPistons);
        serialized.put("allowHopper", allowHopper);
        serialized.put("allowDispenser", allowDispenser);
        serialized.put("allowFire", allowFire);
        serialized.put("allowLiquid", allowLiquid);
        serialized.put("allowBlockSpread", allowBlockSpread);
        serialized.put("allowLeafDecay", allowLeafDecay);
        serialized.put("allowIceForm", allowIceForm);
        serialized.put("allowSnowForm", allowSnowForm);
        serialized.put("allowMonsterSpawn", allowMonsterSpawn);
        serialized.put("allowAnimalSpawn", allowAnimalSpawn);
        serialized.put("allowDamageEntities", allowDamageEntities);
        serialized.put("allowBreeding", allowBreeding);
        serialized.put("allowMonsterTarget", allowMonsterTarget);
        serialized.put("allowLeashing", allowLeashing);
        serialized.put("allowItemDrop", allowItemDrop);
        serialized.put("allowItemPickup", allowItemPickup);
        serialized.put("allowXPDrop", allowXPDrop);
        serialized.put("allowXPPickup", allowXPPickup);
        serialized.put("allowTNT", allowTNT);
        serialized.put("allowCreeper", allowCreeper);
        serialized.put("allowBedExplosion", allowBedExplosion);
        serialized.put("allowCrystalExplosion", allowCrystalExplosion);
        serialized.put("allowVehiclePlace", allowVehiclePlace);
        serialized.put("allowVehicleBreak", allowVehicleBreak);
        serialized.put("allowVehicleEnter", allowVehicleEnter);
        serialized.put("allowVehicleCollide", allowVehicleCollide);
        serialized.put("allowFallDamage", allowFallDamage);
        serialized.put("allowHunger", allowHunger);
        serialized.put("allowFlight", allowFlight);
        serialized.put("allowEnderPearl", allowEnderPearl);
        serialized.put("allowChorusFruit", allowChorusFruit);
        
        return serialized;
    }

    public String getEnterMessage() {
        return enterMessage != null ? enterMessage : "Welcome to " + name + "!";
    }

    public String getLeaveMessage() {
        return leaveMessage != null ? leaveMessage : "Goodbye from " + name + "!";
    }

    public Area setEnterMessage(String message) {
        this.enterMessage = message;
        return this;
    }

    public void setLeaveMessage(String message) {
        this.leaveMessage = message;
    }

    @SuppressWarnings("unchecked")
    public static Area deserialize(Map<String, Object> data) {
        // Convert maps safely during deserialization
        JSONObject settings = new JSONObject((Map<String, Object>)data.getOrDefault("settings", new HashMap<>()));
        JSONObject toggleStates = new JSONObject((Map<String, Object>)data.getOrDefault("toggleStates", new HashMap<>()));
        JSONObject defaultToggleStates = new JSONObject((Map<String, Object>)data.getOrDefault("defaultToggleStates", new HashMap<>()));
        JSONObject inheritedToggleStates = new JSONObject((Map<String, Object>)data.getOrDefault("inheritedToggleStates", new HashMap<>()));

        Area area = Area.builder()
            .name((String) data.get("name"))
            .world((String) data.get("world"))
            .coordinates(
                (int) data.get("xMin"),
                (int) data.get("xMax"),
                (int) data.get("yMin"),
                (int) data.get("yMax"),
                (int) data.get("zMin"),
                (int) data.get("zMax")
            )
            .priority((int) data.get("priority"))
            .showTitle((boolean) data.getOrDefault("showTitle", true))
            .settings(settings)
            .toggleStates(toggleStates)
            .defaultToggleStates(defaultToggleStates)
            .inheritedToggleStates(inheritedToggleStates)
            // Add new toggle properties
            .allowBlockBreak((boolean) data.getOrDefault("allowBlockBreak", false))
            .allowBlockPlace((boolean) data.getOrDefault("allowBlockPlace", false))
            .allowInteract((boolean) data.getOrDefault("allowInteract", false))
            .allowPvP((boolean) data.getOrDefault("allowPvP", false))
            .allowMobSpawn((boolean) data.getOrDefault("allowMobSpawn", true))
            .allowRedstone((boolean) data.getOrDefault("allowRedstone", true))
            .allowContainer((boolean) data.getOrDefault("allowContainer", false))
            .allowItemFrame((boolean) data.getOrDefault("allowItemFrame", false))
            .allowArmorStand((boolean) data.getOrDefault("allowArmorStand", false))
            .allowPistons((boolean) data.getOrDefault("allowPistons", false))
            .allowHopper((boolean) data.getOrDefault("allowHopper", false))
            .allowDispenser((boolean) data.getOrDefault("allowDispenser", false))
            .allowFire((boolean) data.getOrDefault("allowFire", false))
            .allowLiquid((boolean) data.getOrDefault("allowLiquid", false))
            .allowBlockSpread((boolean) data.getOrDefault("allowBlockSpread", false))
            .allowLeafDecay((boolean) data.getOrDefault("allowLeafDecay", false))
            .allowIceForm((boolean) data.getOrDefault("allowIceForm", false))
            .allowSnowForm((boolean) data.getOrDefault("allowSnowForm", false))
            .allowMonsterSpawn((boolean) data.getOrDefault("allowMonsterSpawn", false))
            .allowAnimalSpawn((boolean) data.getOrDefault("allowAnimalSpawn", false))
            .allowDamageEntities((boolean) data.getOrDefault("allowDamageEntities", false))
            .allowBreeding((boolean) data.getOrDefault("allowBreeding", false))
            .allowMonsterTarget((boolean) data.getOrDefault("allowMonsterTarget", false))
            .allowLeashing((boolean) data.getOrDefault("allowLeashing", false))
            .allowItemDrop((boolean) data.getOrDefault("allowItemDrop", false))
            .allowItemPickup((boolean) data.getOrDefault("allowItemPickup", false))
            .allowXPDrop((boolean) data.getOrDefault("allowXPDrop", false))
            .allowXPPickup((boolean) data.getOrDefault("allowXPPickup", false))
            .allowTNT((boolean) data.getOrDefault("allowTNT", false))
            .allowCreeper((boolean) data.getOrDefault("allowCreeper", false))
            .allowBedExplosion((boolean) data.getOrDefault("allowBedExplosion", false))
            .allowCrystalExplosion((boolean) data.getOrDefault("allowCrystalExplosion", false))
            .allowVehiclePlace((boolean) data.getOrDefault("allowVehiclePlace", false))
            .allowVehicleBreak((boolean) data.getOrDefault("allowVehicleBreak", false))
            .allowVehicleEnter((boolean) data.getOrDefault("allowVehicleEnter", false))
            .allowVehicleCollide((boolean) data.getOrDefault("allowVehicleCollide", false))
            .allowFallDamage((boolean) data.getOrDefault("allowFallDamage", false))
            .allowHunger((boolean) data.getOrDefault("allowHunger", false))
            .allowFlight((boolean) data.getOrDefault("allowFlight", false))
            .allowEnderPearl((boolean) data.getOrDefault("allowEnderPearl", false))
            .allowChorusFruit((boolean) data.getOrDefault("allowChorusFruit", false))
            .build();

        if (data.containsKey("enterMessage")) {
            area.setEnterMessage((String) data.get("enterMessage"));
        }
        if (data.containsKey("leaveMessage")) {
            area.setLeaveMessage((String) data.get("leaveMessage"));
        }

        // Handle group permissions
        if (data.containsKey("groupPermissions")) {
            Map<String, Map<String, Boolean>> groupPerms = (Map<String, Map<String, Boolean>>) data.get("groupPermissions");
            for (String group : groupPerms.keySet()) {
                Map<String, Boolean> groupPermMap = groupPerms.get(group);
                if (groupPermMap != null) {
                    area.groupPermissions.put(group, groupPermMap);
                }
            }
        }

        // Handle inherited permissions
        if (data.containsKey("inheritedPermissions")) {
            Map<String, Map<String, Boolean>> inheritedPerms = (Map<String, Map<String, Boolean>>) data.get("inheritedPermissions");
            for (String group : inheritedPerms.keySet()) {
                Map<String, Boolean> inheritedPermMap = inheritedPerms.get(group);
                if (inheritedPermMap != null) {
                    area.inheritedPermissions.put(group, inheritedPermMap);
                }
            }
        }

        return area;
    }

    public JSONObject getToggleStates() {
        return new JSONObject(toggleStates.toString());
    }

    public JSONObject getDefaultToggleStates() {
        return new JSONObject(defaultToggleStates.toString());
    }

    public JSONObject getInheritedToggleStates() {
        return new JSONObject(inheritedToggleStates.toString());
    }

    public boolean getToggleState(String toggle) {
        // Check explicit toggle states first
        if (toggleStates.has(toggle)) {
            return toggleStates.getBoolean(toggle);
        }
        
        // Check inherited states next
        if (inheritedToggleStates.has(toggle)) {
            return inheritedToggleStates.getBoolean(toggle);
        }
        
        // Fall back to default states
        if (defaultToggleStates.has(toggle)) {
            return defaultToggleStates.getBoolean(toggle);
        }
        
        // Return false if no state is found
        return false;
    }

    public void setToggleState(String toggle, boolean state) {
        // Validate the toggle state before setting
        ValidationUtils.validateToggleState(toggle, state, this.settings);
        toggleStates.put(toggle, state);
    }

    public void setDefaultToggleState(String toggle, boolean state) {
        defaultToggleStates.put(toggle, state);
    }

    public void setInheritedToggleState(String toggle, boolean state) {
        inheritedToggleStates.put(toggle, state);
    }

    public void clearToggleState(String toggle) {
        toggleStates.remove(toggle);
    }

    public boolean hasExplicitToggle(String toggle) {
        return toggleStates.has(toggle);
    }

    public boolean hasInheritedToggle(String toggle) {
        return inheritedToggleStates.has(toggle);
    }

    public void clearAllToggles() {
        toggleStates.clear();
        defaultToggleStates.clear();
        inheritedToggleStates.clear();
    }

    /**
     * Creates a new Area instance with an updated name
     * @param newName The new name for the area
     * @return A new Area instance with the updated name
     * @throws IllegalArgumentException if the name is invalid
     */
    public Area setName(String newName) {
        // Validate the new name
        ValidationUtils.validateAreaName(newName);
        
        // Create new instance with updated name using builder
        return Area.builder()
            .name(newName)
            .world(this.world)
            .coordinates(this.xMin, this.xMax, this.yMin, this.yMax, this.zMin, this.zMax)
            .priority(this.priority)
            .showTitle(this.showTitle)
            .settings(new JSONObject(this.settings.toString()))
            .toggleStates(new JSONObject(this.toggleStates.toString()))
            .defaultToggleStates(new JSONObject(this.defaultToggleStates.toString()))
            .inheritedToggleStates(new JSONObject(this.inheritedToggleStates.toString()))
            // Copy all permission flags
            .allowBlockBreak(this.allowBlockBreak)
            .allowBlockPlace(this.allowBlockPlace)
            .allowInteract(this.allowInteract)
            .allowPvP(this.allowPvP)
            .allowMobSpawn(this.allowMobSpawn)
            .allowRedstone(this.allowRedstone)
            .allowContainer(this.allowContainer)
            .allowItemFrame(this.allowItemFrame)
            .allowArmorStand(this.allowArmorStand)
            .allowPistons(this.allowPistons)
            .allowHopper(this.allowHopper)
            .allowDispenser(this.allowDispenser)
            .allowFire(this.allowFire)
            .allowLiquid(this.allowLiquid)
            .allowBlockSpread(this.allowBlockSpread)
            .allowLeafDecay(this.allowLeafDecay)
            .allowIceForm(this.allowIceForm)
            .allowSnowForm(this.allowSnowForm)
            .allowMonsterSpawn(this.allowMonsterSpawn)
            .allowAnimalSpawn(this.allowAnimalSpawn)
            .allowDamageEntities(this.allowDamageEntities)
            .allowBreeding(this.allowBreeding)
            .allowMonsterTarget(this.allowMonsterTarget)
            .allowLeashing(this.allowLeashing)
            .allowItemDrop(this.allowItemDrop)
            .allowItemPickup(this.allowItemPickup)
            .allowXPDrop(this.allowXPDrop)
            .allowXPPickup(this.allowXPPickup)
            .allowTNT(this.allowTNT)
            .allowCreeper(this.allowCreeper)
            .allowBedExplosion(this.allowBedExplosion)
            .allowCrystalExplosion(this.allowCrystalExplosion)
            .allowVehiclePlace(this.allowVehiclePlace)
            .allowVehicleBreak(this.allowVehicleBreak)
            .allowVehicleEnter(this.allowVehicleEnter)
            .allowVehicleCollide(this.allowVehicleCollide)
            .allowFallDamage(this.allowFallDamage)
            .allowHunger(this.allowHunger)
            .allowFlight(this.allowFlight)
            .allowEnderPearl(this.allowEnderPearl)
            .allowChorusFruit(this.allowChorusFruit)
            .build();
    }

    public Area setPriority(int newPriority) {
        return Area.builder()
            .name(this.name)
            .world(this.world)
            .coordinates(this.xMin, this.xMax, this.yMin, this.yMax, this.zMin, this.zMax)
            .priority(newPriority)
            .showTitle(this.showTitle)
            .settings(new JSONObject(this.settings.toString()))
            .toggleStates(new JSONObject(this.toggleStates.toString()))
            .defaultToggleStates(new JSONObject(this.defaultToggleStates.toString()))
            .inheritedToggleStates(new JSONObject(this.inheritedToggleStates.toString()))
            // Copy all permission flags
            .allowBlockBreak(this.allowBlockBreak)
            .allowBlockPlace(this.allowBlockPlace)
            .allowInteract(this.allowInteract)
            .allowPvP(this.allowPvP)
            .allowMobSpawn(this.allowMobSpawn)
            .allowRedstone(this.allowRedstone)
            .allowContainer(this.allowContainer)
            .allowItemFrame(this.allowItemFrame)
            .allowArmorStand(this.allowArmorStand)
            .allowPistons(this.allowPistons)
            .allowHopper(this.allowHopper)
            .allowDispenser(this.allowDispenser)
            .allowFire(this.allowFire)
            .allowLiquid(this.allowLiquid)
            .allowBlockSpread(this.allowBlockSpread)
            .allowLeafDecay(this.allowLeafDecay)
            .allowIceForm(this.allowIceForm)
            .allowSnowForm(this.allowSnowForm)
            .allowMonsterSpawn(this.allowMonsterSpawn)
            .allowAnimalSpawn(this.allowAnimalSpawn)
            .allowDamageEntities(this.allowDamageEntities)
            .allowBreeding(this.allowBreeding)
            .allowMonsterTarget(this.allowMonsterTarget)
            .allowLeashing(this.allowLeashing)
            .allowItemDrop(this.allowItemDrop)
            .allowItemPickup(this.allowItemPickup)
            .allowXPDrop(this.allowXPDrop)
            .allowXPPickup(this.allowXPPickup)
            .allowTNT(this.allowTNT)
            .allowCreeper(this.allowCreeper)
            .allowBedExplosion(this.allowBedExplosion)
            .allowCrystalExplosion(this.allowCrystalExplosion)
            .allowVehiclePlace(this.allowVehiclePlace)
            .allowVehicleBreak(this.allowVehicleBreak)
            .allowVehicleEnter(this.allowVehicleEnter)
            .allowVehicleCollide(this.allowVehicleCollide)
            .allowFallDamage(this.allowFallDamage)
            .allowHunger(this.allowHunger)
            .allowFlight(this.allowFlight)
            .allowEnderPearl(this.allowEnderPearl)
            .allowChorusFruit(this.allowChorusFruit)
            .build();
    }

    public Area setShowTitle(Boolean showTitle) {
        return Area.builder()
            .name(this.name)
            .world(this.world)
            .coordinates(this.xMin, this.xMax, this.yMin, this.yMax, this.zMin, this.zMax)
            .priority(this.priority)
            .showTitle(showTitle)
            .settings(new JSONObject(this.settings.toString()))
            .toggleStates(new JSONObject(this.toggleStates.toString()))
            .defaultToggleStates(new JSONObject(this.defaultToggleStates.toString()))
            .inheritedToggleStates(new JSONObject(this.inheritedToggleStates.toString()))
            // Copy all permission flags
            .allowBlockBreak(this.allowBlockBreak)
            .allowBlockPlace(this.allowBlockPlace)
            .allowInteract(this.allowInteract)
            .allowPvP(this.allowPvP)
            .allowMobSpawn(this.allowMobSpawn)
            .allowRedstone(this.allowRedstone)
            .allowContainer(this.allowContainer)
            .allowItemFrame(this.allowItemFrame)
            .allowArmorStand(this.allowArmorStand)
            .allowPistons(this.allowPistons)
            .allowHopper(this.allowHopper)
            .allowDispenser(this.allowDispenser)
            .allowFire(this.allowFire)
            .allowLiquid(this.allowLiquid)
            .allowBlockSpread(this.allowBlockSpread)
            .allowLeafDecay(this.allowLeafDecay)
            .allowIceForm(this.allowIceForm)
            .allowSnowForm(this.allowSnowForm)
            .allowMonsterSpawn(this.allowMonsterSpawn)
            .allowAnimalSpawn(this.allowAnimalSpawn)
            .allowDamageEntities(this.allowDamageEntities)
            .allowBreeding(this.allowBreeding)
            .allowMonsterTarget(this.allowMonsterTarget)
            .allowLeashing(this.allowLeashing)
            .allowItemDrop(this.allowItemDrop)
            .allowItemPickup(this.allowItemPickup)
            .allowXPDrop(this.allowXPDrop)
            .allowXPPickup(this.allowXPPickup)
            .allowTNT(this.allowTNT)
            .allowCreeper(this.allowCreeper)
            .allowBedExplosion(this.allowBedExplosion)
            .allowCrystalExplosion(this.allowCrystalExplosion)
            .allowVehiclePlace(this.allowVehiclePlace)
            .allowVehicleBreak(this.allowVehicleBreak)
            .allowVehicleEnter(this.allowVehicleEnter)
            .allowVehicleCollide(this.allowVehicleCollide)
            .allowFallDamage(this.allowFallDamage)
            .allowHunger(this.allowHunger)
            .allowFlight(this.allowFlight)
            .allowEnderPearl(this.allowEnderPearl)
            .allowChorusFruit(this.allowChorusFruit)
            .build();
    }

}