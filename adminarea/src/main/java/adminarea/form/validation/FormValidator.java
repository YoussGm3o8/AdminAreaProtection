package adminarea.form.validation;

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
            case AdminAreaConstants.FORM_CREATE_AREA -> validateCreateAreaForm(response);
            case AdminAreaConstants.FORM_EDIT_AREA -> validateEditAreaForm(response);
            case AdminAreaConstants.FORM_BASIC_SETTINGS -> {
                String name = response.getInputResponse(1);
                ValidationResult nameResult = validateAreaName(name);
                yield !nameResult.isValid() ? nameResult : validatePriority(response.getInputResponse(2));
            }
            case AdminAreaConstants.FORM_PROTECTION_SETTINGS -> validateProtectionSettingsForm(response);
            case AdminAreaConstants.FORM_BUILDING_SETTINGS -> validateCategoryForm(response);
            case AdminAreaConstants.FORM_ENVIRONMENT_SETTINGS -> validateCategoryForm(response);
            case AdminAreaConstants.FORM_ENTITY_SETTINGS -> validateCategoryForm(response);
            case AdminAreaConstants.FORM_TECHNICAL_SETTINGS -> validateCategoryForm(response);
            case AdminAreaConstants.FORM_SPECIAL_SETTINGS -> validateCategoryForm(response);
            case AdminAreaConstants.FORM_GROUP_PERMISSIONS -> validateGroupPermissionsForm(response);
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
            case AdminAreaConstants.FORM_CREATE_AREA -> AdminAreaConstants.Permissions.AREA_CREATE;
            case AdminAreaConstants.FORM_EDIT_AREA -> AdminAreaConstants.Permissions.AREA_EDIT;
            case AdminAreaConstants.FORM_GROUP_PERMISSIONS -> AdminAreaConstants.Permissions.LUCKPERMS_EDIT;
            case AdminAreaConstants.FORM_AREA_SETTINGS -> AdminAreaConstants.Permissions.SETTINGS_MANAGE;
            default -> AdminAreaConstants.Permissions.MANAGE;
        };
    }

    private static ValidationResult validateProtectionSettingsForm(FormResponseCustom response) {
        try {
            // Verify all toggle responses are boolean
            for (Object resp : response.getResponses().values()) {
                if (resp != null && !(resp instanceof Boolean)) {
                    return ValidationResult.error("Invalid toggle response type");
                }
            }
            return ValidationResult.success();
        } catch (Exception e) {
            return ValidationResult.error("Invalid protection settings form data");
        }
    }

    public static ValidationResult validateCreateAreaForm(FormResponseCustom response) {
        try {
            String name = response.getInputResponse(0);
            ValidationResult nameResult = validateAreaName(name);
            if (!nameResult.isValid()) return nameResult;
            String priorityStr = response.getInputResponse(1);
            ValidationResult priorityResult = validatePriority(priorityStr);
            if (!priorityResult.isValid()) return priorityResult;

            // Additional validations (coordinates etc.) are merged here.
            if (!response.getToggleResponse(2)) {
                for (int i = 3; i <= 8; i++) {
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

            boolean showTitle = response.getToggleResponse(4);
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
            // Verify all toggle responses are boolean
            for (Object resp : response.getResponses().values()) {
                if (resp != null && !(resp instanceof Boolean)) {
                    return ValidationResult.error("Invalid toggle response type");
                }
            }
            return ValidationResult.success();
        } catch (Exception e) {
            return ValidationResult.error("Invalid category form data");
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

    private static ValidationResult validateOverrideForm(FormResponseCustom response) {
        try {
            // Validate that we have the correct number of responses
            if (response.getResponses().size() % 4 != 0) {
                return ValidationResult.error("Invalid form response structure");
            }
            return ValidationResult.success();
        } catch (Exception e) {
            return ValidationResult.error("Invalid override form data: " + e.getMessage());
        }
    }

    private static ValidationResult validatePlayerManagementForm(FormResponseCustom response) {
        try {
            // Validate player name
            String playerName = response.getInputResponse(0);
            if (playerName == null || playerName.trim().isEmpty()) {
                return ValidationResult.error("Player name cannot be empty");
            }
            if (!playerName.matches("^[a-zA-Z0-9_]{2,16}$")) {
                return ValidationResult.error("Invalid player name format");
            }
            return ValidationResult.success();
        } catch (Exception e) {
            return ValidationResult.error("Invalid player management form data: " + e.getMessage());
        }
    }
}
