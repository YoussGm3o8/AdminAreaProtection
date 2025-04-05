package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaDTO;
import adminarea.constants.FormIds;
import adminarea.form.utils.FormUtils;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementSlider;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.form.window.FormWindowCustom;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom form for editing potion effects of an area.
 */
public class PotionEffectsHandler {
    private final AdminAreaProtectionPlugin plugin;

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
        this.plugin = plugin;
    }

    /**
     * Open the potion effects settings form for a player with no pre-selected area
     */
    public void open(Player player) {
        Area area = getEditingArea(player);
        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.noAreaSelected"));
            FormUtils.cleanup(player);
            plugin.getGuiManager().openMainMenu(player);
            return;
        }
        open(player, area);
    }

    /**
     * Open the potion effects settings form for a player and area
     */
    public void open(Player player, Area area) {
        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.noAreaSelected"));
            FormUtils.cleanup(player);
            plugin.getGuiManager().openMainMenu(player);
            return;
        }

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
            
            // Create the form
            FormWindowCustom form = new FormWindowCustom(plugin.getLanguageManager().get("gui.potionEffectsSettings.title", 
                Map.of("area", area.getName())));
            
            // Add header with clear instructions
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.potionEffectsSettings.header")));
            
            // Add section header for effects
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.potionEffectsSettings.sections.effects")));
            
            // Get area settings
            AreaDTO dto = area.toDTO();
            
            // Store the sliders and their indices for easier response handling
            Map<Integer, String> sliderIndices = new HashMap<>();
            int elementIndex = 2; // Start at 2 because index 0 and 1 are the header labels
            
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
                
                // Store the index for this slider
                sliderIndices.put(elementIndex, permissionNode);
                elementIndex++;
            }
            
            // Store reference to the current area for the response handler
            final Area finalArea = area;
            final Map<Integer, String> finalSliderIndices = sliderIndices;
            
            // Add response handler
            form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
                if (form.wasClosed()) {
                    handleCancel(player);
                    return;
                }
                
                try {
                    Map<String, Integer> effectStrengths = new HashMap<>();
                    int changedCount = 0;
                    
                    // Process all sliders
                    for (Map.Entry<Integer, String> entry : finalSliderIndices.entrySet()) {
                        int index = entry.getKey();
                        String effectKey = entry.getValue();
                        
                        // Get current value from area
                        int currentValue = finalArea.getPotionEffectStrength(effectKey);
                        
                        // Get and validate strength value from response
                        int newValue = 0;
                        float sliderValue = form.getResponse().getSliderResponse(index);
                        newValue = Math.round(sliderValue);
                        
                        // Ensure strength is within valid range
                        newValue = Math.max(0, Math.min(10, newValue));
                        
                        // Only track changes, don't apply yet
                        if (currentValue != newValue) {
                            effectStrengths.put(effectKey, newValue);
                            changedCount++;
                            
                            if (plugin.isDebugMode()) {
                                plugin.debug("Changed effect " + effectKey + " from " + currentValue + " to " + newValue);
                            }
                        }
                    }
                    
                    // Only update if something changed
                    if (changedCount > 0) {
                        // Update the potion effects in the area and notify the player
                        FormUtils.updatePotionEffectsAndNotifyPlayer(player, finalArea, effectStrengths, FormIds.EDIT_AREA);
                    } else {
                        // No changes made, return to edit form
                        player.sendMessage(plugin.getLanguageManager().get("messages.form.noChanges"));
                        FormUtils.cleanup(player);
                        plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, finalArea);
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Error processing potion effects form", e);
                    player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
                    FormUtils.cleanup(player);
                }
            }));
            
            // Add error handler as fallback
            form.addHandler(FormUtils.createErrorHandler(player));
            
            // Send the form to the player
            player.showFormWindow(form);
            
            // Update form tracking
            plugin.getFormIdMap().put(player.getName(), new adminarea.data.FormTrackingData(
                FormIds.POTION_EFFECTS_SETTINGS, System.currentTimeMillis()));
            
        } catch (Exception e) {
            plugin.getLogger().error("Error creating potion effects form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            FormUtils.cleanup(player);
        }
    }

    /**
     * Handle form cancellation
     */
    private void handleCancel(Player player) {
        // Get the area being edited
        Area area = getEditingArea(player);
        
        // Return to the edit area form
        FormUtils.cleanup(player);
        if (area != null) {
            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);
        } else {
            plugin.getGuiManager().openMainMenu(player);
        }
    }

    /**
     * Get the area being edited by a player
     */
    private Area getEditingArea(Player player) {
        var areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) {
            return null;
        }
        return plugin.getArea(areaData.getFormId());
    }
}