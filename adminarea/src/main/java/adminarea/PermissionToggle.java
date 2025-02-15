package adminarea;

import cn.nukkit.form.element.ElementToggle;

public class PermissionToggle {
    private final String displayName;
    private final String permissionNode;
    private final boolean defaultValue;
    
    public PermissionToggle(String displayName, String permissionNode, boolean defaultValue) {
        this.displayName = displayName;
        this.permissionNode = permissionNode;
        this.defaultValue = defaultValue;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPermissionNode() {
        return permissionNode;
    }

    public boolean getDefaultValue() {
        return defaultValue;
    }

    // Converts this toggle into a form element.
    public ElementToggle toElementToggle() {
        return new ElementToggle(displayName, defaultValue);
    }
    
    // Returns the default set of permission toggles.
    public static PermissionToggle[] getDefaultToggles() {
        return new PermissionToggle[] {
            new PermissionToggle("Show Title on Enter/Exit", "show.title", true),
            new PermissionToggle("Allow Block Break", "block.break", true),
            new PermissionToggle("Allow Block Place", "block.place", true),
            new PermissionToggle("Allow Fall Damage", "fall.damage", true),
            new PermissionToggle("Allow PvP", "pvp", true),
            new PermissionToggle("Allow TNT", "tnt", true),
            new PermissionToggle("Allow Hunger", "hunger", true),
            new PermissionToggle("Allow Projectile", "projectile", true),
            new PermissionToggle("Allow Fire", "fire", true),
            new PermissionToggle("Allow Fire Spread", "fire.spread", true),
            new PermissionToggle("Allow Water Flow", "water.flow", true),
            new PermissionToggle("Allow Lava Flow", "lava.flow", true),
            new PermissionToggle("Allow Mob Spawning", "mob.spawn", true),
            new PermissionToggle("Allow Item Use", "item.use", true),
            // New toggle for object interaction (e.g., chests, doors, trapdoors)
            new PermissionToggle("Allow Object Interaction", "object.interact", true)
        };
    }
    
    // Returns the default player-relevant toggles for LuckPerms overrides.
    public static PermissionToggle[] getPlayerToggles() {
        return new PermissionToggle[] {
            new PermissionToggle("Allow PvP", "pvp", true),
            new PermissionToggle("Allow Block Break", "block.break", true),
            new PermissionToggle("Allow Block Place", "block.place", true),
            new PermissionToggle("Allow Object Interaction", "object.interact", true),
            new PermissionToggle("Allow Hunger", "hunger", true),
            new PermissionToggle("Allow Fall Damage", "fall.damage", true)
        };
    }
}
