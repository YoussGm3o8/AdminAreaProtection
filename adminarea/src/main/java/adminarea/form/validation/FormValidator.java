package adminarea.form.validation;

import adminarea.constants.AdminAreaConstants;
import cn.nukkit.form.response.FormResponseCustom;

public class FormValidator {
    public static ValidationResult validate(FormResponseCustom response, String formType) {
        if (response == null) {
            return ValidationResult.error("No form response provided");
        }

        switch (formType) {
            case AdminAreaConstants.FORM_CREATE_AREA:
                return validateCreateAreaForm(response);
            case AdminAreaConstants.FORM_EDIT_AREA:
                return validateEditAreaForm(response);
            case AdminAreaConstants.FORM_OVERRIDE_EDIT:
                return validateOverrideForm(response);
            case AdminAreaConstants.FORM_PLAYER_MANAGEMENT:
                return validatePlayerManagementForm(response);
            default:
                return ValidationResult.success();
        }
    }

    private static ValidationResult validateCreateAreaForm(FormResponseCustom response) {
        try {
            // Validate name
            String name = response.getInputResponse(0);
            if (name == null || name.trim().isEmpty()) {
                return ValidationResult.error("Area name cannot be empty");
            }
            if (!name.matches("^[a-zA-Z0-9_-]{3,32}$")) {
                return ValidationResult.error("Area name must be 3-32 characters and contain only letters, numbers, underscores, and hyphens");
            }

            // Validate priority
            String priorityStr = response.getInputResponse(1);
            try {
                int priority = Integer.parseInt(priorityStr);
                if (priority < 0 || priority > 100) {
                    return ValidationResult.error("Priority must be between 0 and 100");
                }
            } catch (NumberFormatException e) {
                return ValidationResult.error("Priority must be a valid number");
            }

            // If not global, validate coordinates
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
            try {
                int priority = Integer.parseInt(priorityStr);
                if (priority < 0 || priority > 100) {
                    return ValidationResult.error("Priority must be between 0 and 100");
                }
            } catch (NumberFormatException e) {
                return ValidationResult.error("Priority must be a valid number");
            }

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
