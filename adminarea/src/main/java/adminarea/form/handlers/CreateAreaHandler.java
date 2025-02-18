package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.constants.FormIds;
import adminarea.form.validation.FormValidator;
import adminarea.form.validation.ValidationResult;
import adminarea.util.ValidationUtils;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import io.micrometer.core.instrument.Timer;

import java.util.Map;

public class CreateAreaHandler extends BaseFormHandler {

    public CreateAreaHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getFormId() {
        return FormIds.CREATE_AREA;
    }

    @Override
    public FormWindow createForm(Player player) {
        if (!plugin.hasValidSelection(player)) {
            player.sendMessage(plugin.getLanguageManager().get("messages.wand.notBoth"));
            return null;
        }
        return plugin.getFormFactory().createAreaCreationForm();
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Validate form input, e.g., name and priority:
            ValidationResult validation = FormValidator.validateCreateAreaForm(response);
            if (!validation.isValid()) {
                player.sendMessage(validation.getMessage());
                plugin.getGuiManager().openMainMenu(player);
                return;
            }

            // Get selection
            Map<String, Object> selection = plugin.getPlayerSelection(player);
            if (selection == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.wand.notBoth"));
                return;
            }

            // Create area
            Area area = new AreaBuilder()
                .name(response.getInputResponse(0))
                .world(player.getLevel().getName())
                .coordinates(
                    (int)selection.get("x1"), (int)selection.get("x2"),
                    (int)selection.get("y1"), (int)selection.get("y2"),
                    (int)selection.get("z1"), (int)selection.get("z2")
                )
                .priority(Integer.parseInt(response.getInputResponse(1)))
                .showTitle(response.getToggleResponse(2))
                .enterMessage(response.getInputResponse(3))
                .leaveMessage(response.getInputResponse(4))
                .build();

            // Save area
            plugin.saveArea(area);
            player.sendMessage(plugin.getLanguageManager().get("messages.areaCreated", 
                Map.of("area", area.getName())));

            // Open area settings after creation
            plugin.getGuiManager().openAreaSettings(player, area);

        } catch (Exception e) {
            plugin.getLogger().error("Error creating area", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.saveChanges"));
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "create_area_handler");
        }
    }

    @Override 
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        throw new UnsupportedOperationException("Create area form only uses custom form");
    }
}
