package adminarea.area;

import org.json.JSONObject;

import adminarea.AdminAreaProtectionPlugin;

public class AreaBuilder {
    private String name;
    private String world;
    private int xMin, xMax, yMin, yMax, zMin, zMax;
    private int priority;
    private boolean showTitle;
    private JSONObject settings;
    private JSONObject toggleStates;
    private JSONObject defaultToggleStates;
    private JSONObject inheritedToggleStates;
    private boolean allowBlockBreak;
    private boolean allowBlockPlace;
    private boolean allowInteract;
    private boolean allowPvP;
    private boolean allowMobSpawn;
    private boolean allowRedstone;
    private String enterMessage;
    private String leaveMessage;

    // New permission fields to match Area class
    private boolean allowBuild = false;
    private boolean allowBreak = false;
    private boolean allowContainer = false;
    private boolean allowItemFrame = false;
    private boolean allowArmorStand = false;
    private boolean allowPistons = true;
    private boolean allowHopper = true;
    private boolean allowDispenser = true;
    private boolean allowFire = false;
    private boolean allowLiquid = true;
    private boolean allowBlockSpread = true;
    private boolean allowLeafDecay = true;
    private boolean allowIceForm = true;
    private boolean allowSnowForm = true;
    private boolean allowMonsterSpawn = false;
    private boolean allowAnimalSpawn = true;
    private boolean allowDamageEntities = false;
    private boolean allowBreeding = true;
    private boolean allowMonsterTarget = false;
    private boolean allowLeashing = true;
    private boolean allowItemDrop = true;
    private boolean allowItemPickup = true;
    private boolean allowXPDrop = true;
    private boolean allowXPPickup = true;
    private boolean allowTNT = false;
    private boolean allowCreeper = false;
    private boolean allowBedExplosion = false;
    private boolean allowCrystalExplosion = false;
    private boolean allowVehiclePlace = true;
    private boolean allowVehicleBreak = false;
    private boolean allowVehicleEnter = true;
    private boolean allowVehicleCollide = true;
    private boolean allowFallDamage = true;
    private boolean allowHunger = true;
    private boolean allowFlight = false;
    private boolean allowEnderPearl = false;
    private boolean allowChorusFruit = false;

    public AreaBuilder() {
        this.settings = new JSONObject();
        this.toggleStates = new JSONObject();
        this.defaultToggleStates = new JSONObject();
        this.inheritedToggleStates = new JSONObject();
        this.priority = 0;
        this.showTitle = true;
        this.allowBlockBreak = false;
        this.allowBlockPlace = false;
        this.allowInteract = false;
        this.allowPvP = false;
        this.allowMobSpawn = true;
        this.allowRedstone = true;
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

    public AreaBuilder toggleStates(JSONObject toggleStates) {
        this.toggleStates = toggleStates;
        return this;
    }

    public AreaBuilder defaultToggleStates(JSONObject defaultToggleStates) {
        this.defaultToggleStates = defaultToggleStates;
        return this;
    }

    public AreaBuilder inheritedToggleStates(JSONObject inheritedToggleStates) {
        this.inheritedToggleStates = inheritedToggleStates;
        return this;
    }

    public AreaBuilder allowBlockBreak(boolean allow) {
        this.allowBlockBreak = allow;
        return this;
    }

    public AreaBuilder allowBlockPlace(boolean allow) {
        this.allowBlockPlace = allow;
        return this;
    }

    public AreaBuilder allowInteract(boolean allow) {
        this.allowInteract = allow;
        return this;
    }

    public AreaBuilder allowPvP(boolean allow) {
        this.allowPvP = allow;
        return this;
    }

    public AreaBuilder allowMobSpawn(boolean allow) {
        this.allowMobSpawn = allow;
        return this;
    }

    public AreaBuilder allowRedstone(boolean allow) {
        this.allowRedstone = allow;
        return this;
    }

    public AreaBuilder enterMessage(String message) {
        this.enterMessage = message;
        return this;
    }

    public AreaBuilder leaveMessage(String message) {
        this.leaveMessage = message;
        return this;
    }

    // Individual permission setters
    public AreaBuilder allowBuild(boolean allow) { this.allowBuild = allow; return this; }
    public AreaBuilder allowBreak(boolean allow) { this.allowBreak = allow; return this; }
    public AreaBuilder allowContainer(boolean allow) { this.allowContainer = allow; return this; }
    public AreaBuilder allowItemFrame(boolean allow) { this.allowItemFrame = allow; return this; }
    public AreaBuilder allowArmorStand(boolean allow) { this.allowArmorStand = allow; return this; }
    public AreaBuilder allowPistons(boolean allow) { this.allowPistons = allow; return this; }
    public AreaBuilder allowHopper(boolean allow) { this.allowHopper = allow; return this; }
    public AreaBuilder allowDispenser(boolean allow) { this.allowDispenser = allow; return this; }
    public AreaBuilder allowFire(boolean allow) { this.allowFire = allow; return this; }
    public AreaBuilder allowLiquid(boolean allow) { this.allowLiquid = allow; return this; }
    public AreaBuilder allowBlockSpread(boolean allow) { this.allowBlockSpread = allow; return this; }
    public AreaBuilder allowLeafDecay(boolean allow) { this.allowLeafDecay = allow; return this; }
    public AreaBuilder allowIceForm(boolean allow) { this.allowIceForm = allow; return this; }
    public AreaBuilder allowSnowForm(boolean allow) { this.allowSnowForm = allow; return this; }
    public AreaBuilder allowMonsterSpawn(boolean allow) { this.allowMonsterSpawn = allow; return this; }
    public AreaBuilder allowAnimalSpawn(boolean allow) { this.allowAnimalSpawn = allow; return this; }
    public AreaBuilder allowDamageEntities(boolean allow) { this.allowDamageEntities = allow; return this; }
    public AreaBuilder allowBreeding(boolean allow) { this.allowBreeding = allow; return this; }
    public AreaBuilder allowMonsterTarget(boolean allow) { this.allowMonsterTarget = allow; return this; }
    public AreaBuilder allowLeashing(boolean allow) { this.allowLeashing = allow; return this; }
    public AreaBuilder allowItemDrop(boolean allow) { this.allowItemDrop = allow; return this; }
    public AreaBuilder allowItemPickup(boolean allow) { this.allowItemPickup = allow; return this; }
    public AreaBuilder allowXPDrop(boolean allow) { this.allowXPDrop = allow; return this; }
    public AreaBuilder allowXPPickup(boolean allow) { this.allowXPPickup = allow; return this; }
    public AreaBuilder allowTNT(boolean allow) { this.allowTNT = allow; return this; }
    public AreaBuilder allowCreeper(boolean allow) { this.allowCreeper = allow; return this; }
    public AreaBuilder allowBedExplosion(boolean allow) { this.allowBedExplosion = allow; return this; }
    public AreaBuilder allowCrystalExplosion(boolean allow) { this.allowCrystalExplosion = allow; return this; }
    public AreaBuilder allowVehiclePlace(boolean allow) { this.allowVehiclePlace = allow; return this; }
    public AreaBuilder allowVehicleBreak(boolean allow) { this.allowVehicleBreak = allow; return this; }
    public AreaBuilder allowVehicleEnter(boolean allow) { this.allowVehicleEnter = allow; return this; }
    public AreaBuilder allowVehicleCollide(boolean allow) { this.allowVehicleCollide = allow; return this; }
    public AreaBuilder allowFallDamage(boolean allow) { this.allowFallDamage = allow; return this; }
    public AreaBuilder allowHunger(boolean allow) { this.allowHunger = allow; return this; }
    public AreaBuilder allowFlight(boolean allow) { this.allowFlight = allow; return this; }
    public AreaBuilder allowEnderPearl(boolean allow) { this.allowEnderPearl = allow; return this; }
    public AreaBuilder allowChorusFruit(boolean allow) { this.allowChorusFruit = allow; return this; }

    // Bulk permission setters by category
    public AreaBuilder setBasicPermissions(boolean allow) {
        return this.allowBuild(allow)
                   .allowBreak(allow)
                   .allowInteract(allow)
                   .allowContainer(allow)
                   .allowItemFrame(allow)
                   .allowArmorStand(allow);
    }

    public AreaBuilder setRedstonePermissions(boolean allow) {
        return this.allowRedstone(allow)
                   .allowPistons(allow)
                   .allowHopper(allow)
                   .allowDispenser(allow);
    }

    public AreaBuilder setEnvironmentPermissions(boolean allow) {
        return this.allowFire(allow)
                   .allowLiquid(allow)
                   .allowBlockSpread(allow)
                   .allowLeafDecay(allow)
                   .allowIceForm(allow)
                   .allowSnowForm(allow);
    }

    public AreaBuilder setEntityPermissions(boolean allow) {
        return this.allowPvP(allow)
                   .allowMonsterSpawn(allow)
                   .allowAnimalSpawn(allow)
                   .allowDamageEntities(allow)
                   .allowBreeding(allow)
                   .allowMonsterTarget(allow)
                   .allowLeashing(allow);
    }

    public AreaBuilder setExplosionPermissions(boolean allow) {
        return this.allowTNT(allow)
                   .allowCreeper(allow)
                   .allowBedExplosion(allow)
                   .allowCrystalExplosion(allow);
    }

    public AreaBuilder setVehiclePermissions(boolean allow) {
        return this.allowVehiclePlace(allow)
                   .allowVehicleBreak(allow)
                   .allowVehicleEnter(allow)
                   .allowVehicleCollide(allow);
    }

    public AreaBuilder setPlayerEffectPermissions(boolean allow) {
        return this.allowFallDamage(allow)
                   .allowHunger(allow)
                   .allowFlight(allow)
                   .allowEnderPearl(allow)
                   .allowChorusFruit(allow);
    }

    public AreaBuilder applyTemplate(String templateName) {
        AdminAreaProtectionPlugin plugin = AdminAreaProtectionPlugin.getInstance();
        JSONObject template = plugin.getConfigManager().getPermissionTemplate(templateName);
        
        if (template != null) {
            // Apply template permissions
            for (String key : template.keySet()) {
                boolean value = template.getBoolean(key);
                switch (key) {
                    case "allowBlockBreak" -> allowBlockBreak(value);
                case "allowBlockPlace" -> allowBlockPlace(value);
                case "allowInteract" -> allowInteract(value);
                case "allowPvP" -> allowPvP(value);
                case "allowMobSpawn" -> allowMobSpawn(value);
                case "allowRedstone" -> allowRedstone(value);
                case "allowBuild" -> allowBuild(value);
                case "allowBreak" -> allowBreak(value);
                case "allowContainer" -> allowContainer(value);
                case "allowItemFrame" -> allowItemFrame(value);
                case "allowArmorStand" -> allowArmorStand(value);
                case "allowPistons" -> allowPistons(value);
                case "allowHopper" -> allowHopper(value);
                case "allowDispenser" -> allowDispenser(value);
                case "allowFire" -> allowFire(value);
                case "allowLiquid" -> allowLiquid(value);
                case "allowBlockSpread" -> allowBlockSpread(value);
                case "allowLeafDecay" -> allowLeafDecay(value);
                case "allowIceForm" -> allowIceForm(value);
                case "allowSnowForm" -> allowSnowForm(value);
                case "allowMonsterSpawn" -> allowMonsterSpawn(value);
                case "allowAnimalSpawn" -> allowAnimalSpawn(value);
                case "allowDamageEntities" -> allowDamageEntities(value);
                case "allowBreeding" -> allowBreeding(value);
                case "allowMonsterTarget" -> allowMonsterTarget(value);
                case "allowLeashing" -> allowLeashing(value);
                case "allowItemDrop" -> allowItemDrop(value);
                case "allowItemPickup" -> allowItemPickup(value);
                case "allowXPDrop" -> allowXPDrop(value);
                case "allowXPPickup" -> allowXPPickup(value);
                case "allowTNT" -> allowTNT(value);
                case "allowCreeper" -> allowCreeper(value);
                case "allowBedExplosion" -> allowBedExplosion(value);
                case "allowCrystalExplosion" -> allowCrystalExplosion(value);
                case "allowVehiclePlace" -> allowVehiclePlace(value);
                case "allowVehicleBreak" -> allowVehicleBreak(value);
                case "allowVehicleEnter" -> allowVehicleEnter(value);
                case "allowVehicleCollide" -> allowVehicleCollide(value);
                case "allowFallDamage" -> allowFallDamage(value);
                case "allowHunger" -> allowHunger(value);
                case "allowFlight" -> allowFlight(value);
                case "allowEnderPearl" -> allowEnderPearl(value);
                case "allowChorusFruit" -> allowChorusFruit(value);
                }
            }
        }
        return this;
    }

    public AreaBuilder applyDefaults() {
        AdminAreaProtectionPlugin plugin = AdminAreaProtectionPlugin.getInstance();
        JSONObject defaults = plugin.getConfigManager().getDefaultPermissions();
        
        // Apply default permissions
        for (String key : defaults.keySet()) {
            boolean value = defaults.getBoolean(key);
            switch (key) {
                case "allowBlockBreak" -> allowBlockBreak(value);
                case "allowBlockPlace" -> allowBlockPlace(value);
                case "allowInteract" -> allowInteract(value);
                case "allowPvP" -> allowPvP(value);
                case "allowMobSpawn" -> allowMobSpawn(value);
                case "allowRedstone" -> allowRedstone(value);
                case "allowBuild" -> allowBuild(value);
                case "allowBreak" -> allowBreak(value);
                case "allowContainer" -> allowContainer(value);
                case "allowItemFrame" -> allowItemFrame(value);
                case "allowArmorStand" -> allowArmorStand(value);
                case "allowPistons" -> allowPistons(value);
                case "allowHopper" -> allowHopper(value);
                case "allowDispenser" -> allowDispenser(value);
                case "allowFire" -> allowFire(value);
                case "allowLiquid" -> allowLiquid(value);
                case "allowBlockSpread" -> allowBlockSpread(value);
                case "allowLeafDecay" -> allowLeafDecay(value);
                case "allowIceForm" -> allowIceForm(value);
                case "allowSnowForm" -> allowSnowForm(value);
                case "allowMonsterSpawn" -> allowMonsterSpawn(value);
                case "allowAnimalSpawn" -> allowAnimalSpawn(value);
                case "allowDamageEntities" -> allowDamageEntities(value);
                case "allowBreeding" -> allowBreeding(value);
                case "allowMonsterTarget" -> allowMonsterTarget(value);
                case "allowLeashing" -> allowLeashing(value);
                case "allowItemDrop" -> allowItemDrop(value);
                case "allowItemPickup" -> allowItemPickup(value);
                case "allowXPDrop" -> allowXPDrop(value);
                case "allowXPPickup" -> allowXPPickup(value);
                case "allowTNT" -> allowTNT(value);
                case "allowCreeper" -> allowCreeper(value);
                case "allowBedExplosion" -> allowBedExplosion(value);
                case "allowCrystalExplosion" -> allowCrystalExplosion(value);
                case "allowVehiclePlace" -> allowVehiclePlace(value);
                case "allowVehicleBreak" -> allowVehicleBreak(value);
                case "allowVehicleEnter" -> allowVehicleEnter(value);
                case "allowVehicleCollide" -> allowVehicleCollide(value);
                case "allowFallDamage" -> allowFallDamage(value);
                case "allowHunger" -> allowHunger(value);
                case "allowFlight" -> allowFlight(value);
                case "allowEnderPearl" -> allowEnderPearl(value);
                case "allowChorusFruit" -> allowChorusFruit(value);
            }
        }
        return this;
    }

    public Area build() {
        if (name == null || world == null) {
            throw new IllegalStateException("Area name and world must be set");
        }
        
        // Create area with all attributes
        Area area = new Area(name, world, xMin, xMax, yMin, yMax, zMin, zMax, priority, showTitle,
                           allowBlockBreak, allowBlockPlace, allowInteract, allowPvP, 
                           allowMobSpawn, allowRedstone, allowBuild, allowBreak, allowContainer,
                           allowItemFrame, allowArmorStand, allowPistons, allowHopper, allowDispenser,
                           allowFire, allowLiquid, allowBlockSpread, allowLeafDecay, allowIceForm,
                           allowSnowForm, allowMonsterSpawn, allowAnimalSpawn, allowDamageEntities,
                           allowBreeding, allowMonsterTarget, allowLeashing, allowItemDrop,
                           allowItemPickup, allowXPDrop, allowXPPickup, allowTNT, allowCreeper,
                           allowBedExplosion, allowCrystalExplosion, allowVehiclePlace,
                           allowVehicleBreak, allowVehicleEnter, allowVehicleCollide,
                           allowFallDamage, allowHunger, allowFlight, allowEnderPearl,
                           allowChorusFruit);
        
        // Apply settings if they exist
        if (settings != null && !settings.isEmpty()) {
            area.setSettings(settings);
        }

        // Apply toggle states
        if (toggleStates != null && !toggleStates.isEmpty()) {
            for (String key : toggleStates.keySet()) {
                area.setToggleState(key, toggleStates.getBoolean(key));
            }
        }

        // Apply default toggle states
        if (defaultToggleStates != null && !defaultToggleStates.isEmpty()) {
            for (String key : defaultToggleStates.keySet()) {
                area.setDefaultToggleState(key, defaultToggleStates.getBoolean(key));
            }
        }

        // Apply inherited toggle states
        if (inheritedToggleStates != null && !inheritedToggleStates.isEmpty()) {
            for (String key : inheritedToggleStates.keySet()) {
                area.setInheritedToggleState(key, inheritedToggleStates.getBoolean(key));
            }
        }

        // Set messages if provided
        if (enterMessage != null) {
            area.setEnterMessage(enterMessage);
        }
        if (leaveMessage != null) {
            area.setLeaveMessage(leaveMessage);
        }
        
        return area;
    }
}
