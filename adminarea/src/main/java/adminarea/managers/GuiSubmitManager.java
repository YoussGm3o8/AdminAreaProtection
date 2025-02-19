package adminarea.managers;

import java.util.Map;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.form.validation.FormValidator;
import adminarea.form.validation.ValidationResult;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import io.micrometer.core.instrument.Timer;

public class GuiSubmitManager {

    private AdminAreaProtectionPlugin plugin;
    private GuiManager guiManager;

    public GuiSubmitManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }
    

    public void handleCreateAreaSubmit(Player player, FormResponseCustom response) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            ValidationResult validation = FormValidator.validateCreateAreaForm(response);
            if (!validation.isValid()) {
                player.sendMessage(validation.getMessage());
                guiManager.openMainMenu(player);
                return;
            }

            Map<String, Object> selection = plugin.getPlayerSelection(player);
            if (selection == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.wand.notBoth"));
                return;
            }

            Area area = new AreaBuilder()
                .name(response.getInputResponse(0))
                .world(player.getLevel().getName())
                .coordinates(
                    (int) selection.get("x1"), (int) selection.get("x2"),
                    (int) selection.get("y1"), (int) selection.get("y2"),
                    (int) selection.get("z1"), (int) selection.get("z2")
                )
                .priority(Integer.parseInt(response.getInputResponse(1)))
                .showTitle(response.getToggleResponse(2))
                .enterMessage(response.getInputResponse(3))
                .leaveMessage(response.getInputResponse(4))
                .build();

            plugin.saveArea(area);
            player.sendMessage(plugin.getLanguageManager().get("messages.areaCreated", 
                Map.of("area", area.getName())));
            guiManager.openAreaSettings(player, area);
        } catch (Exception e) {
            plugin.getLogger().error("Error creating area", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.saveChanges"));
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "create_area_form_submit");
        }
    }

}
