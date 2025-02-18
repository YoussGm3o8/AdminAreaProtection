package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.mob.EntityMob;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.*;
import io.micrometer.core.instrument.Timer;

public class EntityListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final ProtectionListener protectionListener;

    public EntityListener(AdminAreaProtectionPlugin plugin, ProtectionListener protectionListener) {
        this.plugin = plugin;
        this.protectionListener = protectionListener;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Entity damager = event.getDamager();
            Entity victim = event.getEntity();
            
            if (damager instanceof Player player) {
                // PvP protection
                if (victim instanceof Player && protectionListener.handleProtection(victim, player, "pvp")) {
                    event.setCancelled(true);
                    protectionListener.sendProtectionMessage(player, "messages.pvp");
                    return;
                }
                
                // General entity protection
                if (protectionListener.handleProtection(victim, player, "damage")) {
                    event.setCancelled(true);
                    protectionListener.sendProtectionMessage(player, "messages.protectionDenied");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "entity_damage_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            event.setBlockList(event.getBlockList().stream()
                .filter(block -> !protectionListener.handleProtection(block, null, "explosions"))
                .toList());
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "explosion_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        Entity entity = Entity.createEntity(event.getEntityNetworkId(), event.getPosition());
        String permission = (entity != null && entity instanceof EntityMob) ? "mob_spawning" : "animal_spawn";
        if (protectionListener.handleProtection(event.getPosition(), null, permission)) {
            event.setCancelled(true);
        }
        if (entity != null) {
            entity.close();
        }
    }

}
