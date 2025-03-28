package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaDTO;
import adminarea.constants.FormIds;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementSlider;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class PotionEffectsHandler extends BaseFormHandler {

    // Define potion effect toggles
    private static final List<ToggleDefinition> POTION_TOGGLES = new ArrayList<>();
    
    static {
        // Initialize potion effect toggles
        POTION_TOGGLES.add(new ToggleDefinition("Speed", "allowPotionSpeed", true));
        POTION_TOGGLES.add(new ToggleDefinition("Slowness", "allowPotionSlowness", true));
        POTION_TOGGLES.add(new ToggleDefinition("Haste", "allowPotionHaste", true));
        POTION_TOGGLES.add(new ToggleDefinition("Mining Fatigue", "allowPotionMiningFatigue", true));
        POTION_TOGGLES.add(new ToggleDefinition("Strength", "allowPotionStrength", false));
        POTION_TOGGLES.add(new ToggleDefinition("Instant Health", "allowPotionInstantHealth", true));
        POTION_TOGGLES.add(new ToggleDefinition("Instant Damage", "allowPotionInstantDamage", false));
        POTION_TOGGLES.add(new ToggleDefinition("Jump Boost", "allowPotionJumpBoost", true));
        POTION_TOGGLES.add(new ToggleDefinition("Nausea", "allowPotionNausea", false));
        POTION_TOGGLES.add(new ToggleDefinition("Regeneration", "allowPotionRegeneration", true));
        POTION_TOGGLES.add(new ToggleDefinition("Resistance", "allowPotionResistance", true));
        POTION_TOGGLES.add(new ToggleDefinition("Fire Resistance", "allowPotionFireResistance", true));
        POTION_TOGGLES.add(new ToggleDefinition("Water Breathing", "allowPotionWaterBreathing", true));
        POTION_TOGGLES.add(new ToggleDefinition("Invisibility", "allowPotionInvisibility", false));
        POTION_TOGGLES.add(new ToggleDefinition("Blindness", "allowPotionBlindness", false));
        POTION_TOGGLES.add(new ToggleDefinition("Night Vision", "allowPotionNightVision", true));
        POTION_TOGGLES.add(new ToggleDefinition("Hunger", "allowPotionHunger", false));
        POTION_TOGGLES.add(new ToggleDefinition("Weakness", "allowPotionWeakness", false));
        POTION_TOGGLES.add(new ToggleDefinition("Poison", "allowPotionPoison", false));
        POTION_TOGGLES.add(new ToggleDefinition("Wither", "allowPotionWither", false));
        POTION_TOGGLES.add(new ToggleDefinition("Health Boost", "allowPotionHealthBoost", true));
        POTION_TOGGLES.add(new ToggleDefinition("Absorption", "allowPotionAbsorption", true));
        POTION_TOGGLES.add(new ToggleDefinition("Saturation", "allowPotionSaturation", true));
        POTION_TOGGLES.add(new ToggleDefinition("Levitation", "allowPotionLevitation", false));
    }

    // Helper class for toggle definitions
    private static class ToggleDefinition {
        final String displayName;
        final String permissionNode;
        final boolean defaultValue;

        ToggleDefinition(String displayName, String permissionNode, boolean defaultValue) {
            this.displayName = displayName;
            this.permissionNode = permissionNode;
            this.defaultValue = defaultValue;
        }
    }

    public PotionEffectsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        validateFormId();
    }

    @Override
    public String getFormId() {
        return FormIds.POTION_EFFECTS_SETTINGS;
    }

    @Override
    public FormWindow createForm(Player player) {
        return createForm(player, getEditingArea(player));
    }

    @Override
    public FormWindow createForm(Player player, Area area) {
        if (area == null) return null;

        try {
            // Force reload the area from the database to ensure we have the latest data
            try {
                // Get a fresh copy of the area from the database
                Area freshArea = plugin.getDatabaseManager().loadArea(area.getName());
                if (freshArea != null) {
                    // Use the fresh area instead
                    area = freshArea;
                    
                    if (plugin.isDebugMode()) {
                        Map<String, Integer> effects = area.getAllPotionEffects();
                        boolean hasAnyEffects = effects.values().stream().anyMatch(v -> v > 0);
                        
                        if (hasAnyEffects) {
                            plugin.debug("Area " + area.getName() + " has potion effects: " + effects);
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().error("Failed to reload area from database for potion effects form", e);
            }
            
            FormWindowCustom form = new FormWindowCustom(plugin.getLanguageManager().get("gui.potionEffectsSettings.title", 
                Map.of("area", area.getName())));
            
            // Add header with clear instructions
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.potionEffectsSettings.header")));
            
            // Add section header for effects
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.potionEffectsSettings.sections.effects")));
            
            // Get area settings
            AreaDTO dto = area.toDTO();
            JSONObject settings = dto.settings();
            
            // Skip loading potion effects log to reduce spam
            
            // Add sliders for potion effects strength (no toggles)
            for (ToggleDefinition toggle : POTION_TOGGLES) {
                String permissionNode = toggle.permissionNode;
                
                String description = plugin.getLanguageManager().get(
                    "gui.permissions.toggles." + toggle.permissionNode, 
                    Map.of("effect", toggle.displayName));
                
                // If description is missing, use a default description
                if (description.startsWith("§c")) {
                    description = "Applies " + toggle.displayName + " effect to players in this area with configurable strength";
                }
                
                // Add slider for effect strength (0-10)
                int currentStrength = area.getPotionEffectStrength(permissionNode);
                
                // Only log non-zero strength to reduce spam
                if (plugin.isDebugMode() && currentStrength > 0) {
                    plugin.debug("Effect " + toggle.displayName + " has strength: " + currentStrength);
                }
                
                form.addElement(new ElementSlider(
                    "§e" + toggle.displayName + "\n§8" + description,
                    0, 10, 1, currentStrength
                ));
            }
            
            return form;
        } catch (Exception e) {
            plugin.getLogger().error("Error creating potion effects settings form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.createError",
                Map.of("error", e.getMessage())));
            return null;
        }
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        try {
            Area area = getEditingArea(player);
            if (area == null) return;

            // Skip header labels (index 0 and 1)
            // Create a map to store all potion effect changes
            Map<String, Integer> effectStrengths = new HashMap<>();
            
            // Process each slider starting at index 2 (one per effect)
            for (int i = 0; i < POTION_TOGGLES.size(); i++) {
                try {
                    ToggleDefinition toggle = POTION_TOGGLES.get(i);
                    String permissionNode = toggle.permissionNode;
                    
                    // Calculate index for slider (2 + index of effect)
                    int sliderIndex = 2 + i;
                    
                    // Get current strength value from area
                    int currentStrengthValue = area.getPotionEffectStrength(permissionNode);
                    
                    // Get and validate strength value from response
                    int strengthValue = 0;
                    Object rawResponse = response.getResponse(sliderIndex);
                    
                    if (rawResponse != null) {
                        if (rawResponse instanceof Number) {
                            strengthValue = ((Number) rawResponse).intValue();
                        } else {
                            try {
                                strengthValue = Integer.parseInt(rawResponse.toString());
                            } catch (NumberFormatException e) {
                                if (plugin.isDebugMode()) {
                                    plugin.debug("Invalid strength value: " + rawResponse);
                                }
                                continue;
                            }
                        }
                    }
                    
                    // Ensure strength is within valid range
                    strengthValue = Math.max(0, Math.min(10, strengthValue));
                    
                    // Only track changes, don't apply yet
                    if (currentStrengthValue != strengthValue) {
                        // Add to the effects map
                        effectStrengths.put(permissionNode, strengthValue);
                        
                        if (plugin.isDebugMode()) {
                            plugin.debug("Will update strength " + permissionNode + " from " + 
                                currentStrengthValue + " to " + strengthValue);
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Error processing effect at index " + i, e);
                }
            }
            
            // Use the standardized method to update potion effects and notify the player
            updatePotionEffectsAndNotifyPlayer(player, area, effectStrengths, FormIds.EDIT_AREA);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling potion effects settings", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            plugin.getGuiManager().openMainMenu(player);
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, getEditingArea(player));
    }

    protected Area getEditingArea(Player player) {
        var areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.noAreaSelected"));
            return null;
        }
        return plugin.getArea(areaData.getFormId());
    }

    /**
     * Ensures permission nodes have the correct prefix
     */
    protected String normalizePermissionNode(String permission) {
        if (permission == null || permission.isEmpty()) {
            return "gui.permissions.toggles.default";
        }
        
        // If it already has the prefix, return as is
        if (permission.startsWith("gui.permissions.toggles.")) {
            return permission;
        }
        
        // Add prefix for simple permission names
        return "gui.permissions.toggles." + permission;
    }
}