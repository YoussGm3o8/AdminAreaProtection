package adminarea.form.adapter;

import adminarea.AdminAreaProtectionPlugin;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseData;
import cn.nukkit.utils.LogLevel;
import java.util.Map;

/**
 * Adapter class to handle form response differences between Nukkit and NukkitMOT.
 * Provides consistent methods to extract values from form responses.
 */
public class FormResponseAdapter {
    private final AdminAreaProtectionPlugin plugin;
    
    public FormResponseAdapter(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Safely gets a string input response from a custom form.
     * Handles differences between Nukkit and NukkitMOT APIs.
     * 
     * @param response The form response
     * @param index The index of the input element
     * @return The string value or empty string if null
     */
    public String getInputResponse(FormResponseCustom response, int index) {
        if (response == null) {
            return "";
        }
        
        try {
            // Get value at the specified index
            Object rawValue = getRawResponseValue(response, index);
            
            // Skip null values and labels
            if (rawValue == null || isLabelOrDescriptive(rawValue.toString())) {
                // If this looks like a label or descriptive content, skip it
                if (plugin.isDebugMode()) {
                    plugin.debug("Skipping likely label/descriptive content at index " + index);
                }
                return "";
            }
            
            // Return string value
            return rawValue.toString();
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error getting input response at index " + index + ": " + e.getMessage());
            }
            return "";
        }
    }
    
    /**
     * Safely gets a boolean toggle response from a custom form.
     * 
     * @param response The form response
     * @param index The index of the toggle element
     * @return The boolean value or false if error
     */
    public boolean getToggleResponse(FormResponseCustom response, int index) {
        if (response == null) {
            return false;
        }
        
        try {
            Object rawValue = getRawResponseValue(response, index);
            
            // Handle null value
            if (rawValue == null) {
                return false;
            }
            
            // Handle boolean value
            if (rawValue instanceof Boolean) {
                return (Boolean) rawValue;
            }
            
            // Try to parse string as boolean
            if (rawValue instanceof String) {
                String strValue = (String) rawValue;
                return "true".equalsIgnoreCase(strValue) || "yes".equalsIgnoreCase(strValue) || "1".equals(strValue);
            }
            
            // Try to parse number as boolean
            if (rawValue instanceof Number) {
                return ((Number) rawValue).intValue() != 0;
            }
            
            return false;
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error getting toggle response at index " + index + ": " + e.getMessage());
            }
            return false;
        }
    }
    
    /**
     * Safely gets a dropdown response from a custom form.
     * 
     * @param response The form response
     * @param index The index of the dropdown element
     * @return The selected index or -1 if error
     */
    public int getDropdownResponse(FormResponseCustom response, int index) {
        if (response == null) {
            return -1;
        }
        
        try {
            Object rawValue = getRawResponseValue(response, index);
            
            // Handle different value types
            if (rawValue instanceof Integer) {
                return (Integer) rawValue;
            } else if (rawValue instanceof FormResponseData) {
                return ((FormResponseData) rawValue).getElementID();
            } else if (rawValue instanceof Number) {
                return ((Number) rawValue).intValue();
            } else if (rawValue != null) {
                // Try to parse as integer
                try {
                    return Integer.parseInt(rawValue.toString());
                } catch (NumberFormatException e) {
                    if (plugin.isDebugMode()) {
                        plugin.debug("Could not parse dropdown value as integer: " + rawValue);
                    }
                }
            }
            
            return -1;
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error getting dropdown response at index " + index + ": " + e.getMessage());
            }
            return -1;
        }
    }
    
    /**
     * Safely gets a slider response from a custom form.
     * 
     * @param response The form response
     * @param index The index of the slider element
     * @return The slider value or 0 if error
     */
    public float getSliderResponse(FormResponseCustom response, int index) {
        if (response == null) {
            return 0;
        }
        
        try {
            Object rawValue = getRawResponseValue(response, index);
            
            // Handle different value types
            if (rawValue instanceof Float) {
                return (Float) rawValue;
            } else if (rawValue instanceof Number) {
                return ((Number) rawValue).floatValue();
            } else if (rawValue != null) {
                // Try to parse as float
                try {
                    return Float.parseFloat(rawValue.toString());
                } catch (NumberFormatException e) {
                    if (plugin.isDebugMode()) {
                        plugin.debug("Could not parse slider value as float: " + rawValue);
                    }
                }
            }
            
            return 0;
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error getting slider response at index " + index + ": " + e.getMessage());
            }
            return 0;
        }
    }
    
    /**
     * Gets the raw response value at the specified index.
     * This is a low-level method that accesses the form response data directly.
     * 
     * @param response The form response
     * @param index The index of the element
     * @return The raw response value object
     */
    public Object getRawResponseValue(FormResponseCustom response, int index) {
        if (response == null) {
            return null;
        }
        
        try {
            // First try the direct response mapping
            Object value = response.getResponses().get(index);
            
            // Filter out descriptive/label content
            if (value != null && isLabelOrDescriptive(value.toString())) {
                if (plugin.isDebugMode()) {
                    plugin.debug("Found likely label content at index " + index + ": " + value);
                }
            }
            
            // Debug the actual value
            if (plugin.isDebugMode()) {
                String typeName = value != null ? value.getClass().getName() : "null";
                String valueStr = value != null ? value.toString() : "null";
                plugin.debug("Raw form value at index " + index + ": " + valueStr + " (type: " + typeName + ")");
            }
            
            return value;
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error getting raw response at index " + index + ": " + e.getMessage());
            }
            return null;
        }
    }
    
    /**
     * Logs all form responses for debugging purposes.
     * 
     * @param response The form response
     */
    public void logAllResponses(FormResponseCustom response) {
        if (!plugin.isDebugMode() || response == null) {
            return;
        }
        
        plugin.getLogger().log(LogLevel.DEBUG, "Form response dump:");
        Map<Integer, Object> responses = response.getResponses();
        
        for (Map.Entry<Integer, Object> entry : responses.entrySet()) {
            int index = entry.getKey();
            Object value = entry.getValue();
            String type = value != null ? value.getClass().getName() : "null";
            
            plugin.debug(String.format("Index %d: %s (type: %s)", 
                index, 
                value != null ? value.toString() : "null", 
                type));
        }
        
        // Also try specialized getters for additional debug info
        if (responses.size() > 0) {
            plugin.debug("Testing specialized form response getters:");
            for (int i = 0; i < 20 && i < responses.size() + 5; i++) {
                try {
                    // Try different getters on each index for debugging
                    String inputResponse = "-";
                    try {
                        inputResponse = response.getInputResponse(i);
                    } catch (Exception e) {
                        inputResponse = "ERROR: " + e.getMessage();
                    }
                    
                    Boolean toggleResponse = null;
                    try {
                        toggleResponse = response.getToggleResponse(i);
                    } catch (Exception e) {
                        // Ignore
                    }
                    
                    plugin.debug(String.format("Index %d: inputResponse='%s', toggleResponse=%s", 
                        i, inputResponse, toggleResponse));
                } catch (Exception e) {
                    plugin.debug("Error testing getters at index " + i + ": " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Helper method to determine if a string value appears to be descriptive label content
     * rather than actual form input data
     */
    private boolean isLabelOrDescriptive(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        
        // Check for common patterns in label text
        if (value.contains("?2") || value.contains("?8") || // Colored text markers
            value.startsWith("Area ") || value.startsWith("Protection ") ||
            value.contains("Settings") || value.contains("Configure")) {
            return true;
        }
        
        return false;
    }
} 