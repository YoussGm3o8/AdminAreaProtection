package adminarea.form.validation;

import adminarea.constants.FormIds;
import adminarea.constants.AdminAreaConstants;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.Player;

public class FormValidator {
    public static ValidationResult validateAreaName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return ValidationResult.error("Area name cannot be empty");
        }
        if (!name.matches("^[a-zA-Z0-9_-]{3,32}$")) {
            return ValidationResult.error("Area name must be 3-32 alphanumeric characters (underscore and hyphen allowed)");
        }
        return ValidationResult.success();
    }

    public static ValidationResult validatePriority(String priorityStr) {
        try {
            int priority = Integer.parseInt(priorityStr);
            if (priority < 0 || priority > 100) {
                return ValidationResult.error("Priority must be between 0 and 100");
            }
        } catch (NumberFormatException e) {
            return ValidationResult.error("Priority must be a valid number");
        }
        return ValidationResult.success();
    }

    public static ValidationResult validate(FormResponseCustom response, String formType) {
        if (response == null) {
            return ValidationResult.error("No form response provided");
        }

        return switch (formType) {
            case FormIds.CREATE_AREA -> validateCreateAreaForm(response);
            case FormIds.EDIT_AREA -> validateEditAreaForm(response);
            case FormIds.BASIC_SETTINGS -> validateBasicSettingsForm(response);
            case FormIds.BUILDING_SETTINGS, 
                 FormIds.ENVIRONMENT_SETTINGS,
                 FormIds.ENTITY_SETTINGS,
                 FormIds.TECHNICAL_SETTINGS,
                 FormIds.SPECIAL_SETTINGS -> validateCategoryForm(response);
            case FormIds.GROUP_PERMISSIONS -> validateGroupPermissionsForm(response);
            default -> ValidationResult.success();
        };
    }

    public static ValidationResult validateTrackSelectionResponse(FormResponseSimple response) {
        if (response.getClickedButtonId() < 0) {
            return ValidationResult.error("Invalid track selection");
        }
        return ValidationResult.success();
    }

    public static ValidationResult validateGroupPermissionsResponse(FormResponseCustom response) {
        try {
            // Verify all responses are boolean toggles
            for (int i = 0; i < response.getResponses().size(); i++) {
                if (!(response.getResponse(i) instanceof Boolean)) {
                    return ValidationResult.error("Invalid permission toggle response");
                }
            }
            return ValidationResult.success();
        } catch (Exception e) {
            return ValidationResult.error("Invalid group permissions form data");
        }
    }

    public static ValidationResult validatePermissions(Player player, String formType) {
        String requiredPermission = getRequiredPermission(formType);
        if (!player.hasPermission(requiredPermission)) {
            return ValidationResult.error("No permission");
        }
        return ValidationResult.success();
    }

    private static String getRequiredPermission(String formType) {
        return switch(formType) {
            case FormIds.CREATE_AREA -> AdminAreaConstants.Permissions.AREA_CREATE;
            case FormIds.EDIT_AREA -> AdminAreaConstants.Permissions.AREA_EDIT;
            case FormIds.BASIC_SETTINGS, 
                FormIds.PROTECTION_SETTINGS,
                FormIds.BUILDING_SETTINGS,
                FormIds.ENVIRONMENT_SETTINGS, 
                FormIds.ENTITY_SETTINGS,
                FormIds.TECHNICAL_SETTINGS,
                FormIds.SPECIAL_SETTINGS -> AdminAreaConstants.Permissions.SETTINGS_MANAGE;
            case FormIds.GROUP_PERMISSIONS -> AdminAreaConstants.Permissions.LUCKPERMS_EDIT;
            default -> AdminAreaConstants.Permissions.MANAGE;
        };
    }


    public static ValidationResult validateCreateAreaForm(FormResponseCustom response) {
        try {
            // Name is at index 1, not 0
            String name = response.getInputResponse(1);
            ValidationResult nameResult = validateAreaName(name);
            if (!nameResult.isValid()) return nameResult;
            
            // Priority is at index 2, not 1
            String priorityStr = response.getInputResponse(2);
            ValidationResult priorityResult = validatePriority(priorityStr);
            if (!priorityResult.isValid()) return priorityResult;

            // World protection toggle is at index 4
            if (!response.getToggleResponse(4)) {
                // Coordinates start at index 6-11 in CreateAreaHandler
                for (int i = 6; i <= 11; i++) {
                    String coord = response.getInputResponse(i);
                    if (!coord.equals("~") && !coord.matches("^~?-?\\d+$")) {
                        return ValidationResult.error("Invalid coordinate format. Use numbers or ~ for relative coordinates");
                    }
                }
            }

            return ValidationResult.success();

        } catch (Exception e) {
            return ValidationResult.error("Invalid form data: " + e.getMessage());
        }
    }

    private static ValidationResult validateEditAreaForm(FormResponseCustom response) {
        try {
            // Validate coordinates
            for (int i = 2; i <= 7; i++) {
                String coord = response.getInputResponse(i);
                try {
                    Integer.parseInt(coord);
                } catch (NumberFormatException e) {
                    return ValidationResult.error("All coordinates must be valid numbers");
                }
            }

            // Validate priority
            String priorityStr = response.getInputResponse(1);
            ValidationResult priorityResult = validatePriority(priorityStr);
            if (!priorityResult.isValid()) return priorityResult;

            // Validate messages
            int lastIndex = response.getResponses().size() - 1;
            String enterMsg = response.getInputResponse(lastIndex - 1);
            String leaveMsg = response.getInputResponse(lastIndex);
            if (enterMsg.length() > 48 || leaveMsg.length() > 48) {
                return ValidationResult.error("Messages must not exceed 48 characters");
            }

            return ValidationResult.success();
        } catch (Exception e) {
            return ValidationResult.error("Invalid form data: " + e.getMessage());
        }
    }

    private static ValidationResult validateBasicSettingsForm(FormResponseCustom response) {
        try {
            // Label is element 0, name is element 1
            String name = response.getInputResponse(1);
            ValidationResult nameResult = validateAreaName(name);
            if (!nameResult.isValid()) return nameResult;

            String priority = response.getInputResponse(2);
            ValidationResult priorityResult = validatePriority(priority);
            if (!priorityResult.isValid()) return priorityResult;

            // Fix index for showTitle toggle (3 instead of 4)
            boolean showTitle = response.getToggleResponse(3);
            // Skip element 4 which is the Display Settings label
            String enterMsg = response.getInputResponse(5);
            String leaveMsg = response.getInputResponse(6);

            if (enterMsg != null && enterMsg.length() > 48) {
                return ValidationResult.error("Enter message too long (max 48 chars)");
            }
            if (leaveMsg != null && leaveMsg.length() > 48) {
                return ValidationResult.error("Leave message too long (max 48 chars)");
            }

            return ValidationResult.success();
        } catch (Exception e) {
            return ValidationResult.error("Invalid form data: " + e.getMessage());
        }
    }

    private static ValidationResult validateCategoryForm(FormResponseCustom response) {
        try {
            // Skip first element which is the header label
            for (int i = 1; i < response.getResponses().size(); i++) {
                Object resp = response.getResponse(i);
                if (!(resp instanceof Boolean)) {
                    return ValidationResult.error("Invalid toggle response type at position " + i);
                }
            }
            return ValidationResult.success();
        } catch (Exception e) {
            return ValidationResult.error("Invalid category settings form data");
        }
    }

    private static ValidationResult validateGroupPermissionsForm(FormResponseCustom response) {
        try {
            if (response.getResponses().size() % 4 != 0) {
                return ValidationResult.error("Invalid permission structure");
            }
            return ValidationResult.success();
        } catch (Exception e) {
            return ValidationResult.error("Invalid group permissions data");
        }
    }
}
