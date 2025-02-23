package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.vehicle.EntityEnterVehicleEvent;
import cn.nukkit.event.vehicle.VehicleCreateEvent;
import cn.nukkit.event.vehicle.VehicleDamageEvent;
import cn.nukkit.level.Position;
import io.micrometer.core.instrument.Timer;

public class VehicleListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final ProtectionListener protectionListener;

    public VehicleListener(AdminAreaProtectionPlugin plugin, ProtectionListener protectionListener) {
        this.plugin = plugin;
        this.protectionListener = protectionListener;
    }

    private boolean shouldCheckProtection(Entity vehicle, Player player, String permission) {
        if (vehicle == null || vehicle.getLevel() == null) {
            return false;
        }

        // Skip if chunk is not loaded
        if (!vehicle.getLevel().isChunkLoaded(vehicle.getChunkX(), vehicle.getChunkZ())) {
            return false;
        }

        Position pos = vehicle.getPosition();
        
        // Use shouldCancel from ProtectionListener
        return protectionListener.shouldCancel(pos, player, permission);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehiclePlace(VehicleCreateEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (shouldCheckProtection(event.getVehicle(), null, "allowVehiclePlace")) {
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
                shouldCheckProtection(event.getVehicle(), player, "allowVehicleEnter")) {
                event.setCancelled(true);
                protectionListener.sendProtectionMessage(player, "messages.protection.vehicleEnter");
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "vehicle_enter_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Entity attacker = event.getAttacker();
            if (attacker instanceof Player player) {
                if (shouldCheckProtection(event.getVehicle(), player, "allowVehicleDamage")) {
                    event.setCancelled(true);
                    protectionListener.sendProtectionMessage(player, "messages.protection.vehicleDamage");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "vehicle_damage_check");
        }
    }
}
