package adminarea.util;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.math.SimpleAxisAlignedBB;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * Utility class for area validation and configuration operations
 */
public class AreaValidationUtils {

    private final AdminAreaProtectionPlugin plugin;

    public AreaValidationUtils(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Validates area name for uniqueness and format
     * 
     * @param name The area name to validate
     * @param player Player to send error messages to (optional)
     * @param reopenForm Whether to reopen the form on validation failure
     * @param formId The form to reopen on failure
     * @return true if name is valid, false otherwise
     */
    public boolean validateAreaName(String name, Player player, boolean reopenForm, String formId) {
        try {
            // Use existing validation logic for format check
            ValidationUtils.validateAreaName(name);
            
            // Check if area already exists
            if (plugin.hasArea(name)) {
                if (player != null) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.area.exists", 
                        Map.of("area", name)));
                    if (reopenForm) {
                        plugin.getGuiManager().openFormById(player, formId, null);
                    }
                }
                return false;
            }
            
            return true;
        } catch (IllegalArgumentException e) {
            if (player != null) {
                player.sendMessage(plugin.getLanguageManager().get("validation.area.name.format"));
                if (reopenForm) {
                    plugin.getGuiManager().openFormById(player, formId, null);
                }
            }
            return false;
        }
    }
    
    /**
     * Validates and normalizes area coordinates
     * 
     * @param x1 First X coordinate
     * @param y1 First Y coordinate
     * @param z1 First Z coordinate
     * @param x2 Second X coordinate
     * @param y2 Second Y coordinate
     * @param z2 Second Z coordinate
     * @return Normalized coordinates array [x1, x2, y1, y2, z1, z2]
     */
    public int[] validateAndNormalizeCoordinates(int x1, int y1, int z1, int x2, int y2, int z2) {
        // Validate coordinate ranges
        validateCoordinateRange(x1, "X");
        validateCoordinateRange(x2, "X");
        validateCoordinateRange(y1, "Y");
        validateCoordinateRange(y2, "Y");
        validateCoordinateRange(z1, "Z");
        validateCoordinateRange(z2, "Z");
        
        // Normalize coordinates so that x1 <= x2, y1 <= y2, z1 <= z2
        if (x1 > x2) {
            int temp = x1;
            x1 = x2;
            x2 = temp;
        }
        
        if (y1 > y2) {
            int temp = y1;
            y1 = y2;
            y2 = temp;
        }
        
        if (z1 > z2) {
            int temp = z1;
            z1 = z2;
            z2 = temp;
        }
        
        return new int[]{x1, x2, y1, y2, z1, z2};
    }
    
    /**
     * Validates world-specific coordinate ranges
     */
    private void validateCoordinateRange(int value, String axis) {
        if (axis.equals("Y")) {
            if (value < -64 || value > 320) {
                throw new IllegalArgumentException("Y coordinate must be between -64 and 320");
            }
        } else {
            // X and Z have much larger bounds
            if (value < -30000000 || value > 30000000) {
                throw new IllegalArgumentException(axis + " coordinate must be between -30000000 and 30000000");
            }
        }
    }
    
    /**
     * Validates if a global area already exists for the given world
     * 
     * @param worldName World name to check
     * @param player Player to send message to (optional)
     * @param reopenForm Whether to reopen the form on validation failure
     * @param formId Form to reopen on failure 
     * @return true if no global area exists, false if one exists
     */
    public boolean validateGlobalAreaForWorld(String worldName, Player player, boolean reopenForm, String formId) {
        Area existingGlobalArea = plugin.getAreaManager().getGlobalAreaForWorld(worldName);
        
        if (existingGlobalArea != null) {
            if (player != null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.area.globalExists", 
                    Map.of("world", worldName, "area", existingGlobalArea.getName())));
                if (reopenForm) {
                    plugin.getGuiManager().openFormById(player, formId, null);
                }
            }
            return false;
        }
        
        return true;
    }
    
    /**
     * Configures title settings in areaTitles.yml for an area
     * 
     * @param areaName The area name
     * @param enterTitle The main title when entering the area
     * @param enterMessage The subtitle when entering the area
     * @param leaveTitle The main title when leaving the area
     * @param leaveMessage The subtitle when leaving the area
     */
    public void configureAreaTitles(String areaName, String enterTitle, String enterMessage, String leaveTitle, String leaveMessage) {
        // Set title values in areaTitles.yml
        plugin.getConfigManager().setAreaTitleText(areaName, "enter", "main", enterTitle.isEmpty() ? "§6Welcome to " + areaName : enterTitle);
        plugin.getConfigManager().setAreaTitleText(areaName, "enter", "subtitle", enterMessage.isEmpty() ? "§eEnjoy your stay!" : enterMessage);
        plugin.getConfigManager().setAreaTitleText(areaName, "enter", "fadeIn", "20");
        plugin.getConfigManager().setAreaTitleText(areaName, "enter", "stay", "40");
        plugin.getConfigManager().setAreaTitleText(areaName, "enter", "fadeOut", "20");
        
        plugin.getConfigManager().setAreaTitleText(areaName, "leave", "main", leaveTitle.isEmpty() ? "§6Leaving " + areaName : leaveTitle);
        plugin.getConfigManager().setAreaTitleText(areaName, "leave", "subtitle", leaveMessage.isEmpty() ? "§eThank you for visiting!" : leaveMessage);
        plugin.getConfigManager().setAreaTitleText(areaName, "leave", "fadeIn", "20");
        plugin.getConfigManager().setAreaTitleText(areaName, "leave", "stay", "40");
        plugin.getConfigManager().setAreaTitleText(areaName, "leave", "fadeOut", "20");
        
        // Save the config
        plugin.getConfigManager().saveAreaTitles();
        
        logDebug("Configured title settings for area " + areaName);
    }
    
    /**
     * Configures title settings in areaTitles.yml for an area
     * 
     * @param areaName The area name
     * @param enterMessage The subtitle when entering the area
     * @param leaveMessage The subtitle when leaving the area
     */
    public void configureAreaTitles(String areaName, String enterMessage, String leaveMessage) {
        // Create default titles
        String enterTitle = "§6Welcome to " + areaName;
        String leaveTitle = "§6Leaving " + areaName;
        
        // Forward to the full method
        configureAreaTitles(areaName, enterTitle, enterMessage, leaveTitle, leaveMessage);
    }
    
    /**
     * Removes area title configuration from areaTitles.yml
     * 
     * @param areaName The area name to remove
     */
    public void removeAreaTitles(String areaName) {
        plugin.getConfigManager().removeAreaTitleConfig(areaName);
        
        logDebug("Removed title configuration for area " + areaName);
    }
    
    /**
     * Get global coordinates for a world
     * 
     * @return int array of [x1, x2, y1, y2, z1, z2] for global area
     */
    public int[] getGlobalCoordinates() {
        return new int[] {
            -29000000, 29000000,  // x1, x2
            0, 255,               // y1, y2
            -29000000, 29000000   // z1, z2
        };
    }
    
    /**
     * Validates and extracts coordinates from form response
     * 
     * @param response Form response containing coordinates
     * @param startIndex First coordinate element index
     * @param isGlobal Whether this is a global area
     * @return Normalized coordinate array [x1, x2, y1, y2, z1, z2]
     */
    public int[] extractCoordinatesFromForm(FormResponseCustom response, int startIndex, boolean isGlobal) {
        if (isGlobal) {
            return getGlobalCoordinates();
        }
        
        try {
            int x1 = Integer.parseInt(response.getInputResponse(startIndex));
            int y1 = Integer.parseInt(response.getInputResponse(startIndex + 1));
            int z1 = Integer.parseInt(response.getInputResponse(startIndex + 2));
            int x2 = Integer.parseInt(response.getInputResponse(startIndex + 3));
            int y2 = Integer.parseInt(response.getInputResponse(startIndex + 4));
            int z2 = Integer.parseInt(response.getInputResponse(startIndex + 5));
            
            return validateAndNormalizeCoordinates(x1, y1, z1, x2, y2, z2);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid coordinate format. All coordinates must be valid numbers.");
        }
    }
    
    /**
     * Validates complete area creation or update parameters
     * 
     * @param name The area name
     * @param x1 First X coordinate
     * @param x2 Second X coordinate
     * @param y1 First Y coordinate
     * @param y2 Second Y coordinate
     * @param z1 First Z coordinate
     * @param z2 Second Z coordinate
     * @param world The world name
     * @param isGlobal Whether this is a global area
     * @param player Player to send error messages to (optional)
     * @param formId Form ID to reopen on validation failure
     * @param excludeAreaName Area to exclude from validation (for updates)
     * @return true if validation passes, false otherwise
     */
    public boolean validateAreaCreation(String name, int x1, int x2, int y1, int y2, int z1, int z2, 
                                       String world, boolean isGlobal, Player player, String formId, String excludeAreaName) {
        // Validate name
        if (!validateAreaName(name, player, true, formId)) {
            if (name.equals(excludeAreaName)) {
                // Skip name validation when updating the same area
                logDebug("Skipping name validation for same area: " + name);
            } else {
                return false;
            }
        }
        
        // Validate global area (only for creation, not updates)
        if (isGlobal && excludeAreaName == null && !validateGlobalAreaForWorld(world, player, true, formId)) {
            return false;
        }
        
        // Validate area size
        int sizeX = Math.abs(x2 - x1) + 1;
        int sizeY = Math.abs(y2 - y1) + 1;
        int sizeZ = Math.abs(z2 - z1) + 1;
        
        // Skip size validation for global areas
        if (!isGlobal) {
            int minSize = plugin.getConfigManager().getInt("area.minSize", 1);
            if (sizeX < minSize || sizeY < minSize || sizeZ < minSize) {
                if (player != null) {
                    player.sendMessage(plugin.getLanguageManager().get("validation.area.selection.tooSmall",
                        Map.of("min", String.valueOf(minSize))));
                    if (formId != null) {
                        plugin.getGuiManager().openFormById(player, formId, null);
                    }
                }
                return false;
            }
            
            int maxSize = plugin.getConfigManager().getInt("area.maxSize", 10000);
            if (sizeX > maxSize || sizeY > maxSize || sizeZ > maxSize) {
                if (player != null) {
                    player.sendMessage(plugin.getLanguageManager().get("validation.area.bounds.tooLarge",
                        Map.of("max", String.valueOf(maxSize))));
                    if (formId != null) {
                        plugin.getGuiManager().openFormById(player, formId, null);
                    }
                }
                return false;
            }
        }
        
        // Validate priority for both regular and global areas
        int priority = 0; // Default priority value if not provided
        if (player != null && player.hasPermission("adminarea.create")) {
            if (!validatePriority(priority, player, formId)) {
                return false;
            }
        }
        
        // Areas are now allowed to overlap, so no need to check for overlaps
        
        return true;
    }
    
    /**
     * Validates toggle settings from form data and creates a JSONObject
     * 
     * @param response The form response
     * @param toggleStartIndex The index of the first toggle 
     * @param changedSettings Reference to count number of changed settings
     * @return JSONObject with validated toggle settings
     */
    public JSONObject validateAndCreateToggleSettings(FormResponseCustom response, int toggleStartIndex, int[] changedSettings) {
        JSONObject settings = new JSONObject();
        int toggleIndex = toggleStartIndex;
        
        // Process toggle settings by category
        for (PermissionToggle.Category category : PermissionToggle.Category.values()) {
            // Skip category label (increment toggle index)
            toggleIndex++;
            
            List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(category);
            if (toggles != null) {
                for (PermissionToggle toggle : toggles) {
                    try {
                        String permissionNode = toggle.getPermissionNode();
                        // Get toggle response at current index
                        Object rawResponse = response.getResponse(toggleIndex);
                        if (rawResponse instanceof Boolean) {
                            boolean toggleValue = (Boolean) rawResponse;
                            settings.put(permissionNode, toggleValue);
                            changedSettings[0]++;
                            logDebug("Set toggle " + permissionNode + " to " + toggleValue);
                        } else {
                            // Use default value if response is invalid
                            settings.put(permissionNode, toggle.getDefaultValue());
                            logDebug("Using default value for " + permissionNode + ": " + toggle.getDefaultValue());
                        }
                        toggleIndex++; // Move to next toggle position
                    } catch (Exception e) {
                        plugin.getLogger().debug("Error processing toggle " + toggle.getPermissionNode() + ": " + e.getMessage());
                        settings.put(toggle.getPermissionNode(), toggle.getDefaultValue());
                        toggleIndex++; // Still need to move to next position even on error
                    }
                }
            }
        }
        
        // Validate toggle dependencies and conflicts
        try {
            ValidationUtils.validateToggleDependencies(settings);
            ValidationUtils.validateToggleConflicts(settings);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid toggle configuration: " + e.getMessage());
        }
        
        return settings;
    }

    /**
     * Validates that an area doesn't overlap with existing areas
     * 
     * @param x1 First X coordinate
     * @param x2 Second X coordinate
     * @param y1 First Y coordinate
     * @param y2 Second Y coordinate
     * @param z1 First Z coordinate
     * @param z2 Second Z coordinate
     * @param worldName World name
     * @param excludeAreaName Area to exclude from overlap check (for updates)
     * @return true if no overlaps, false if overlapping with another area
     */
    public boolean validateNoAreaOverlap(int x1, int x2, int y1, int y2, int z1, int z2, 
                                          String worldName, String excludeAreaName) {
        // Areas are now allowed to overlap, always return true
        return true;
    }
    
    /**
     * Validates area size requirements
     * 
     * @param x1 First X coordinate
     * @param x2 Second X coordinate
     * @param y1 First Y coordinate
     * @param y2 Second Y coordinate
     * @param z1 First Z coordinate
     * @param z2 Second Z coordinate
     * @param isGlobal Whether this is a global area
     * @param player Player to send error messages to (optional) 
     * @param formId Form ID to reopen on validation failure
     * @return true if size is valid, false otherwise
     */
    public boolean validateAreaSize(int x1, int x2, int y1, int y2, int z1, int z2, 
                                    boolean isGlobal, Player player, String formId) {
        // Skip size validation for global areas
        if (isGlobal) {
            return true;
        }
        
        // Calculate area size
        int sizeX = Math.abs(x2 - x1) + 1;
        int sizeY = Math.abs(y2 - y1) + 1;
        int sizeZ = Math.abs(z2 - z1) + 1;
        
        // Check minimum size
        int minSize = plugin.getConfigManager().getInt("area.minSize", 1);
        if (sizeX < minSize || sizeY < minSize || sizeZ < minSize) {
            if (player != null) {
                player.sendMessage(plugin.getLanguageManager().get("validation.area.selection.tooSmall",
                    Map.of("min", String.valueOf(minSize))));
                if (formId != null) {
                    plugin.getGuiManager().openFormById(player, formId, null);
                }
            }
            return false;
        }
        
        // Check maximum size
        int maxSize = plugin.getConfigManager().getInt("area.maxSize", 10000);
        if (sizeX > maxSize || sizeY > maxSize || sizeZ > maxSize) {
            if (player != null) {
                player.sendMessage(plugin.getLanguageManager().get("validation.area.bounds.tooLarge",
                    Map.of("max", String.valueOf(maxSize))));
                if (formId != null) {
                    plugin.getGuiManager().openFormById(player, formId, null);
                }
            }
            return false;
        }
        
        return true;
    }

    /**
     * Validate area priority value
     * 
     * @param priority The priority value to validate
     * @param player Player to send error messages to (optional)
     * @param formId Form ID to reopen on validation failure
     * @return true if priority is valid, false otherwise
     */
    public boolean validatePriority(int priority, Player player, String formId) {
        try {
            ValidationUtils.validatePriority(priority);
            return true;
        } catch (IllegalArgumentException e) {
            if (player != null) {
                player.sendMessage(plugin.getLanguageManager().get("validation.area.priority.invalid", 
                    Map.of("min", "0", "max", "100")));
                if (formId != null) {
                    plugin.getGuiManager().openFormById(player, formId, null);
                }
            }
            return false;
        }
    }
    
    /**
     * Validate area messages length
     * 
     * @param enterMessage The enter message to validate
     * @param leaveMessage The leave message to validate
     * @param player Player to send error messages to (optional)
     * @param formId Form ID to reopen on validation failure
     * @return true if messages are valid, false otherwise
     */
    public boolean validateMessages(String enterMessage, String leaveMessage, Player player, String formId) {
        int maxLength = plugin.getConfigManager().getInt("area.messages.maxLength", 100);
        
        if ((enterMessage != null && enterMessage.length() > maxLength) || 
            (leaveMessage != null && leaveMessage.length() > maxLength)) {
            if (player != null) {
                player.sendMessage(plugin.getLanguageManager().get("validation.messages.tooLong",
                    Map.of("max", String.valueOf(maxLength))));
                if (formId != null) {
                    plugin.getGuiManager().openFormById(player, formId, null);
                }
            }
            return false;
        }
        
        return true;
    }
    
    /**
     * Validate area title fields length
     * 
     * @param enterTitle The enter title to validate
     * @param leaveTitle The leave title to validate
     * @param player Player to send error messages to (optional)
     * @param formId Form ID to reopen on validation failure
     * @return true if titles are valid, false otherwise
     */
    public boolean validateTitles(String enterTitle, String leaveTitle, Player player, String formId) {
        int maxLength = plugin.getConfigManager().getInt("areaSettings.titles.maxLength", 100);
        
        if ((enterTitle != null && enterTitle.length() > maxLength) || 
            (leaveTitle != null && leaveTitle.length() > maxLength)) {
            if (player != null) {
                player.sendMessage(plugin.getLanguageManager().get("validation.titles.tooLong",
                    Map.of("max", String.valueOf(maxLength))));
                if (formId != null) {
                    plugin.getGuiManager().openFormById(player, formId, null);
                }
            }
            return false;
        }
        
        return true;
    }
    
    /**
     * Centralized debug logging for area validation
     * 
     * @param message The message to log
     */
    private void logDebug(String message) {
        if (plugin.isDebugMode()) {
            plugin.debug(message);
        }
    }
}