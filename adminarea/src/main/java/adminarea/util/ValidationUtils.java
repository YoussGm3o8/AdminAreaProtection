package adminarea.util;

import cn.nukkit.Player;
import cn.nukkit.level.Position;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Thread-safe utility class for input validation.
 */
public final class ValidationUtils {
    private static final int MAX_STRING_LENGTH = 512;
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,32}$");
    private static final Pattern PERMISSION_PATTERN = Pattern.compile("^[a-z]+(\\.[a-z]+)*$");
    private static final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();
    
    // Prevent instantiation
    private ValidationUtils() {
        throw new AssertionError("Utility class should not be instantiated");
    }

    public static void validateAreaName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Area name cannot be null or empty");
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Area name must be 3-32 characters and contain only letters, numbers, underscores, and hyphens");
        }
    }

    public static void validateCoordinates(int min, int max, String axis) {
        if (min > max) {
            throw new IllegalArgumentException(axis + " minimum cannot be greater than maximum");
        }
    }

    public static void validatePositions(Position[] positions) {
        if (positions == null || positions.length != 2) {
            throw new IllegalArgumentException("Both positions must be set");
        }
        if (positions[0] == null || positions[1] == null) {
            throw new IllegalArgumentException("Both positions must be set");
        }
        if (!positions[0].getLevel().getName().equals(positions[1].getLevel().getName())) {
            throw new IllegalArgumentException("Positions must be in the same world");
        }
    }

    public static void validatePriority(int priority) {
        if (priority < -100 || priority > 100) {
            throw new IllegalArgumentException("Priority must be between -100 and 100");
        }
    }

    /**
     * Validates and sanitizes a general string input.
     * @param input String to validate
     * @param minLength Minimum allowed length
     * @param maxLength Maximum allowed length
     * @param allowedPattern Regex pattern for allowed characters
     * @param fieldName Name of the field for error messages
     * @return Sanitized string
     */
    public static String validateString(String input, int minLength, int maxLength, 
                                     String allowedPattern, String fieldName) {
        if (input == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null");
        }

        String sanitized = input.trim();
        if (sanitized.length() < minLength) {
            throw new IllegalArgumentException(fieldName + 
                " must be at least " + minLength + " characters");
        }
        if (sanitized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + 
                " cannot exceed " + maxLength + " characters");
        }

        Pattern pattern = patternCache.computeIfAbsent(allowedPattern, Pattern::compile);
        if (!pattern.matcher(sanitized).matches()) {
            throw new IllegalArgumentException(fieldName + " contains invalid characters");
        }

        return sanitized;
    }

    /**
     * Validates numeric input within specified range.
     * @param value Value to validate
     * @param min Minimum allowed value
     * @param max Maximum allowed value
     * @param fieldName Name of the field for error messages
     */
    public static void validateNumericRange(int value, int min, int max, String fieldName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + 
                " must be between " + min + " and " + max);
        }
    }

    /**
     * Validates a date/time string against specified format.
     * @param dateStr Date string to validate
     * @param format Expected date format
     * @return Parsed LocalDateTime
     */
    public static LocalDateTime validateDateTime(String dateStr, String format) {
        try {
            return LocalDateTime.parse(dateStr, DateTimeFormatter.ofPattern(format));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date format. Expected: " + format);
        }
    }

    /**
     * Validates permission string format.
     * @param permission Permission string to validate
     */
    public static void validatePermission(String permission) {
        if (permission == null || !PERMISSION_PATTERN.matcher(permission).matches()) {
            throw new IllegalArgumentException(
                "Invalid permission format. Must be lowercase, dot-separated");
        }
    }

    /**
     * Validates area boundaries including world consistency.
     * @param pos1 First position
     * @param pos2 Second position
     * @param maxSize Maximum allowed area size
     */
    public static void validateAreaBoundaries(Position pos1, Position pos2, int maxSize) {
        validatePositions(new Position[]{pos1, pos2});
        
        int xSize = Math.abs(pos1.getFloorX() - pos2.getFloorX());
        int ySize = Math.abs(pos1.getFloorY() - pos2.getFloorY());
        int zSize = Math.abs(pos1.getFloorZ() - pos2.getFloorZ());
        
        if (xSize * ySize * zSize > maxSize) {
            throw new IllegalArgumentException(
                "Area exceeds maximum size of " + maxSize + " blocks");
        }
    }

    /**
     * Validates player state for area operations.
     * @param player Player to validate
     * @param requiredPermission Required permission node
     * @return True if player state is valid
     */
    public static boolean validatePlayerState(Player player, String permission) {
        if (player == null) {
            throw new IllegalArgumentException("Player cannot be null");
        }
        
        if (!player.isOnline()) {
            throw new IllegalStateException("Player must be online");
        }
        
        if (permission != null && !player.hasPermission(permission)) {
            throw new IllegalStateException("Missing required permission: " + permission);
        }
        
        return true;
    }

    /**
     * Sanitizes and validates form input.
     * @param input Raw form input
     * @param maxLength Maximum allowed length
     * @return Sanitized input
     */
    public static String sanitizeFormInput(String input, int maxLength) {
        if (input == null) {
            return "";
        }
        
        String sanitized = input.trim()
            .replaceAll("[\\p{Cntrl}]", "") // Remove control characters
            .replaceAll("<[^>]*>", ""); // Remove HTML tags
            
        return sanitized.length() > maxLength ? 
            sanitized.substring(0, maxLength) : sanitized;
    }

    /**
     * Validates cross-field dependencies.
     * @param fields Map of field names to values
     * @param rules Map of field names to validation rules
     */
    public static void validateCrossFields(Map<String, String> fields, 
                                         Map<String, String> rules) {
        for (Map.Entry<String, String> rule : rules.entrySet()) {
            String fieldName = rule.getKey();
            String ruleValue = rule.getValue();
            
            if (!fields.containsKey(fieldName)) {
                throw new IllegalArgumentException("Required field missing: " + fieldName);
            }
            
            // Process rule logic
            String[] ruleParts = ruleValue.split(":");
            switch (ruleParts[0]) {
                case "required_with":
                    validateRequiredWith(fields, fieldName, ruleParts[1]);
                    break;
                case "greater_than":
                    validateGreaterThan(fields, fieldName, ruleParts[1]);
                    break;
                // Add more cross-field validation rules as needed
            }
        }
    }

    private static void validateRequiredWith(Map<String, String> fields, 
                                          String field, String dependentField) {
        if (fields.containsKey(dependentField) && 
            !fields.containsKey(field)) {
            throw new IllegalArgumentException(
                field + " is required when " + dependentField + " is present");
        }
    }

    private static void validateGreaterThan(Map<String, String> fields, 
                                         String field, String compareField) {
        try {
            int value = Integer.parseInt(fields.get(field));
            int compareValue = Integer.parseInt(fields.get(compareField));
            if (value <= compareValue) {
                throw new IllegalArgumentException(
                    field + " must be greater than " + compareField);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid numeric values for comparison");
        }
    }
}
