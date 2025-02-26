package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.area.AreaDTO;
import adminarea.constants.FormIds;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
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
            FormWindowCustom form = new FormWindowCustom(plugin.getLanguageManager().get("gui.potionEffectsSettings.title", 
                Map.of("area", area.getName())));
            
            // Add header with clear instructions
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.potionEffectsSettings.header")));
            
            // Add section header for effects
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.potionEffectsSettings.sections.effects")));
            
            // Get area settings
            AreaDTO dto = area.toDTO();
            JSONObject settings = dto.settings();

            // Add sliders for potion effects strength (no toggles)
            for (ToggleDefinition toggle : POTION_TOGGLES) {
                String permissionNode = normalizePermissionNode(toggle.permissionNode);
                String strengthNode = permissionNode + "Strength";
                
                String description = plugin.getLanguageManager().get(
                    "gui.permissions.toggles." + toggle.permissionNode, 
                    Map.of("effect", toggle.displayName));
                
                // If description is missing, use a default description
                if (description.startsWith("§c")) {
                    description = "Applies " + toggle.displayName + " effect to players in this area with configurable strength";
                }
                
                // Add slider for effect strength (0-255)
                int currentStrength = area.getPotionEffectStrength(strengthNode);
                form.addElement(new ElementSlider(
                    "§e" + toggle.displayName + "\n§8" + description,
                    0, 255, 1, currentStrength
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

            // Track changes
            int changedSettings = 0;

            // Debug log the response data
            if (plugin.isDebugMode()) {
                plugin.debug("Processing potion effects settings form response:");
                plugin.debug("Number of responses: " + response.getResponses().size());
                for (int i = 0; i < response.getResponses().size(); i++) {
                    plugin.debug("Response " + i + ": " + response.getResponse(i));
                }
            }

            // Skip header labels (index 0 and 1)
            // Process each slider starting at index 2 (one per effect)
            for (int i = 0; i < POTION_TOGGLES.size(); i++) {
                try {
                    ToggleDefinition toggle = POTION_TOGGLES.get(i);
                    String permissionNode = normalizePermissionNode(toggle.permissionNode);
                    String strengthNode = permissionNode + "Strength";
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Processing effect: " + toggle.displayName + 
                                    " with permission node: " + permissionNode);
                    }
                    
                    // Calculate index for slider (2 + index of effect)
                    int sliderIndex = 2 + i;
                    
                    // Get current strength value from area
                    int currentStrengthValue = area.getPotionEffectStrength(strengthNode);
                    
                    // Get strength response
                    Object rawStrengthResponse = response.getResponse(sliderIndex);
                    if (rawStrengthResponse == null) {
                        if (plugin.isDebugMode()) {
                            plugin.debug("Null raw response for strength " + strengthNode + " at index " + sliderIndex);
                        }
                        continue;
                    }
                    
                    // Convert strength response to integer
                    int strengthValue;
                    if (rawStrengthResponse instanceof Number) {
                        strengthValue = ((Number) rawStrengthResponse).intValue();
                    } else if (rawStrengthResponse instanceof String) {
                        try {
                            strengthValue = Integer.parseInt((String) rawStrengthResponse);
                        } catch (NumberFormatException e) {
                            if (plugin.isDebugMode()) {
                                plugin.debug("Invalid string format for strength " + strengthNode + 
                                    ": " + rawStrengthResponse);
                            }
                            continue;
                        }
                    } else {
                        if (plugin.isDebugMode()) {
                            plugin.debug("Invalid response type for strength " + strengthNode + 
                                ": " + rawStrengthResponse.getClass().getName());
                        }
                        continue;
                    }
                    
                    // Ensure strength is within valid range
                    strengthValue = Math.max(0, Math.min(255, strengthValue));
                    
                    // Only count as changed if strength is different
                    boolean strengthChanged = currentStrengthValue != strengthValue;
                    
                    // Add debug logging to track state changes
                    if (strengthChanged) {
                        changedSettings++;
                        
                        // Store the strength value using the setPotionEffectStrength method
                        area.setPotionEffectStrength(strengthNode, strengthValue);
                        
                        // If strength > 0, also ensure the toggle is enabled (for backwards compatibility)
                        if (strengthValue > 0) {
                            area.setToggleState(permissionNode, true);
                        } else {
                            area.setToggleState(permissionNode, false);
                        }
                        
                        if (plugin.isDebugMode()) {
                            plugin.debug("Updated strength: " + strengthNode + " = " + strengthValue);
                            plugin.debug("Updated toggle: " + permissionNode + " = " + (strengthValue > 0));
                            plugin.debug("Updated strength " + strengthNode + " from " + 
                                currentStrengthValue + " to " + strengthValue);
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Error processing effect " + POTION_TOGGLES.get(i).permissionNode + ": " + e.getMessage());
                    if (plugin.isDebugMode()) {
                        e.printStackTrace();
                    }
                }
            }

            // Save changes
            plugin.updateArea(area);
            
            player.sendMessage(plugin.getLanguageManager().get("messages.area.updated",
                Map.of(
                    "area", area.getName(),
                    "count", String.valueOf(changedSettings)
                )));

            // Return to edit menu
            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling potion effects settings", e);
            if (plugin.isDebugMode()) {
                e.printStackTrace();
            }
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            plugin.getGuiManager().openMainMenu(player);
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, getEditingArea(player));
    }

    private Area getEditingArea(Player player) {
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