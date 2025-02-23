package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.AdminAreaConstants;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowSimple;

import java.util.HashMap;
import java.util.Map;

public class EditAreaHandler extends BaseFormHandler {

    public EditAreaHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        validateFormId();
    }

    @Override
    public String getFormId() {
        return FormIds.EDIT_AREA;
    }

    @Override
    public FormWindow createForm(Player player, Area area) {
        try {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("area", area.getName());

            FormWindowSimple form = new FormWindowSimple(
                plugin.getLanguageManager().get("gui.editArea.title", placeholders),
                plugin.getLanguageManager().get("gui.editArea.content")
            );

            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.editArea.buttons.basicSettings")));
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.editArea.buttons.buildingSettings")));
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.editArea.buttons.environmentSettings")));
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.editArea.buttons.entitySettings")));
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.editArea.buttons.technicalSettings")));
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.editArea.buttons.specialSettings")));

            if (plugin.isLuckPermsEnabled()) {
                form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.editArea.buttons.groupPermissions")));
            }

            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.editArea.buttons.playerPermissions")));

            return form;
        } catch (Exception e) {
            plugin.getLogger().error("Error creating edit area form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            return null;
        }
    }

    @Override
    public FormWindow createForm(Player player) {
        FormTrackingData data = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (data == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.noAreaSelected"));
            return null;
        }

        Area area = plugin.getArea(data.getFormId());
        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.areaNotFound"));
            return null;
        }

        FormWindowSimple form = new FormWindowSimple(
            "Edit Area - " + area.getName(),
            "Choose a category to edit:"
        );

        form.addButton(new ElementButton("Basic Settings"));
        form.addButton(new ElementButton("Building Settings"));
        form.addButton(new ElementButton("Environment Settings"));
        form.addButton(new ElementButton("Entity Settings"));
        form.addButton(new ElementButton("Technical Settings"));
        form.addButton(new ElementButton("Special Settings"));
        
        if (plugin.getLuckPermsApi() != null) {
            form.addButton(new ElementButton("LuckPerms Settings"));
            form.addButton(new ElementButton("Player Settings"));
        }

        return form;
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        // Not used for simple form
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        if (response == null) {
            handleCancel(player);
            return;
        }

        FormTrackingData data = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (data == null) return;

        Area area = plugin.getArea(data.getFormId());
        if (area == null) return;

        String formId;
        switch (response.getClickedButtonId()) {
            case 0:
                formId = FormIds.BASIC_SETTINGS;
                break;
            case 1:
                formId = FormIds.BUILDING_SETTINGS;
                break;
            case 2:
                formId = FormIds.ENVIRONMENT_SETTINGS;
                break;
            case 3:
                formId = FormIds.ENTITY_SETTINGS;
                break;
            case 4:
                formId = FormIds.TECHNICAL_SETTINGS;
                break;
            case 5:
                formId = FormIds.SPECIAL_SETTINGS;
                break;
            case 6:
                if (plugin.getLuckPermsApi() != null) {
                    formId = FormIds.LUCKPERMS_SETTINGS;
                    break;
                }
                return;
            case 7:
                if (plugin.getLuckPermsApi() != null) {
                    formId = FormIds.PLAYER_SETTINGS;
                    break;
                }
                return;
            default:
                return;
        }

        plugin.getGuiManager().openFormById(player, formId, area);
    }

    private Area getEditingArea(Player player) {
        var areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) return null;
        return plugin.getArea(areaData.getFormId());
    }
}
