package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.vehicle.*;
import cn.nukkit.level.Position;
import io.micrometer.core.instrument.Timer;

public class VehicleListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final ProtectionListener protectionListener;

    public VehicleListener(AdminAreaProtectionPlugin plugin, ProtectionListener protectionListener) {
        this.plugin = plugin;
        this.protectionListener = protectionListener;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleCreate(VehicleCreateEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Entity vehicle = event.getVehicle();
            // Check if any player is nearby who might be placing the vehicle
            for (Entity entity : vehicle.getLevel().getNearbyEntities(vehicle.getBoundingBox().grow(1, 1, 1))) {
                if (entity instanceof Player player) {
                    Position pos = vehicle.getPosition();
                    if (protectionListener.shouldCancel(pos, player, "allowVehiclePlace")) {
                        event.setCancelled(true);
                        protectionListener.sendProtectionMessage(player, "messages.protection.vehiclePlace");
                        break;
                    }
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "vehicle_create_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Entity attacker = event.getAttacker();
            if (attacker instanceof Player player) {
                Position pos = event.getVehicle().getPosition();
                if (protectionListener.shouldCancel(pos, player, "allowVehicleBreak")) {
                    event.setCancelled(true);
                    protectionListener.sendProtectionMessage(player, "messages.protection.vehicleBreak");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "vehicle_damage_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleEnter(EntityEnterVehicleEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (event.getEntity() instanceof Player player) {
                Position pos = event.getVehicle().getPosition();
                if (protectionListener.shouldCancel(pos, player, "allowVehicleEnter")) {
                    event.setCancelled(true);
                    protectionListener.sendProtectionMessage(player, "messages.protection.vehicleEnter");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "vehicle_enter_check");
        }
    }
}
