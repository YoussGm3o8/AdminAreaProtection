package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.constants.FormIds;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import io.micrometer.core.instrument.Timer;

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
        return plugin.getFormFactory().createAreaForm();
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            plugin.getGuiSubmitManager().handleCreateAreaSubmit(player, response);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "create_area_handler");
        }
    }

    @Override 
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        throw new UnsupportedOperationException("Create area form only uses custom form");
    }
}
