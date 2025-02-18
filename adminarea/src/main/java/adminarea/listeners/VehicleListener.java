package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.vehicle.EntityEnterVehicleEvent;
import cn.nukkit.event.vehicle.VehicleCreateEvent;
import cn.nukkit.event.vehicle.VehicleDamageEvent;
import io.micrometer.core.instrument.Timer;

public class VehicleListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final ProtectionListener protectionListener;

    public VehicleListener(AdminAreaProtectionPlugin plugin, ProtectionListener protectionListener) {
        this.plugin = plugin;
        this.protectionListener = protectionListener;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehiclePlace(VehicleCreateEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (protectionListener.handleProtection(event.getVehicle(), null, "vehicle_place")) {
                event.setCancelled(true);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "vehicle_place_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleEnter(EntityEnterVehicleEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (event.getEntity() instanceof Player player &&
                protectionListener.handleProtection(event.getVehicle(), player, "vehicle_enter")) {
                event.setCancelled(true);
                protectionListener.sendProtectionMessage(player, "messages.vehicleEnter");
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "vehicle_enter_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (event.getAttacker() instanceof Player player &&
                protectionListener.handleProtection(event.getVehicle(), player, "vehicle_destroy")) {
                event.setCancelled(true);
                protectionListener.sendProtectionMessage(player, "messages.vehicleDamage");
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "vehicle_damage_check");
        }
    }
}
