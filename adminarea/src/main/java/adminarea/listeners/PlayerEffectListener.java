package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.*;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.level.Position;
import io.micrometer.core.instrument.Timer;

public class PlayerEffectListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final ProtectionListener protectionListener;

    public PlayerEffectListener(AdminAreaProtectionPlugin plugin, ProtectionListener protectionListener) {
        this.plugin = plugin;
        this.protectionListener = protectionListener;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPickupXP(PlayerExperienceChangeEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getPlayer();
            Position pos = player.getPosition();
            
            if (protectionListener.shouldCancel(pos, player, "allowXPPickup")) {
                event.setCancelled(true);
                protectionListener.sendProtectionMessage(player, "messages.protection.xpPickup");
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "xp_pickup_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (!(event.getEntity() instanceof Player player)) {
                return;
            }

            Position pos = player.getPosition();
            
            // Check fall damage
            if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
                if (protectionListener.shouldCancel(pos, player, "allowFallDamage")) {
                    event.setCancelled(true);
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "player_damage_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFoodLevelChange(PlayerFoodLevelChangeEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getPlayer();
            Position pos = player.getPosition();
            
            // Only check if food level is decreasing
            if (event.getFoodLevel() < player.getFoodData().getLevel()) {
                if (protectionListener.shouldCancel(pos, player, "allowHunger")) {
                    event.setCancelled(true);
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "hunger_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getPlayer();
            Position pos = player.getPosition();
            
            // Only check when enabling flight
            if (event.isFlying()) {
                if (protectionListener.shouldCancel(pos, player, "allowFlight")) {
                    event.setCancelled(true);
                    protectionListener.sendProtectionMessage(player, "messages.protection.flight");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "flight_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getPlayer();
            Position pos = player.getPosition();
            
            // Only check chorus fruit teleports now since ender pearls are handled separately
            if (event.getCause() == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) {
                if (protectionListener.shouldCancel(pos, player, "allowChorusFruit")) {
                    event.setCancelled(true);
                    protectionListener.sendProtectionMessage(player, "messages.protection.chorusFruit");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "teleport_check");
        }
    }
}