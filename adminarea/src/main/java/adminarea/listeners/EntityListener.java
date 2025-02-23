package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.mob.EntityMob;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.*;
import cn.nukkit.event.player.PlayerInteractEntityEvent;
import io.micrometer.core.instrument.Timer;
import cn.nukkit.level.Position;

public class EntityListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final ProtectionListener protectionListener;

    public EntityListener(AdminAreaProtectionPlugin plugin, ProtectionListener protectionListener) {
        this.plugin = plugin;
        this.protectionListener = protectionListener;
    }

    private boolean shouldCheckProtection(Entity entity, Player player, String permission) {
        if (entity == null || entity.getLevel() == null) {
            return false;
        }

        // Skip if chunk is not loaded
        if (!entity.getLevel().isChunkLoaded(entity.getChunkX(), entity.getChunkZ())) {
            return false;
        }

        Position pos = entity.getPosition();
        
        // Use shouldCancel from ProtectionListener
        return protectionListener.shouldCancel(pos, player, permission);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Entity damager = event.getDamager();
            Entity victim = event.getEntity();
            
            if (damager instanceof Player player) {
                // PvP protection
                if (victim instanceof Player && shouldCheckProtection(victim, player, "allowPvP")) {
                    event.setCancelled(true);
                    protectionListener.sendProtectionMessage(player, "messages.protection.pvp");
                    return;
                }
                
                // General entity protection
                if (shouldCheckProtection(victim, player, "allowEntityDamage")) {
                    event.setCancelled(true);
                    protectionListener.sendProtectionMessage(player, "messages.protection.entityDamage");
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
                .filter(block -> {
                    Position pos = new Position(block.x, block.y, block.z, block.level);
                    return !protectionListener.shouldCancel(pos, null, "allowExplosions");
                })
                .toList());
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "explosion_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(EntitySpawnEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Position pos = event.getPosition();
            String permission = event.getEntity() instanceof EntityMob ? "allowMonsterSpawn" : "allowAnimalSpawn";
            
            if (protectionListener.shouldCancel(pos, null, permission)) {
                event.setCancelled(true);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "creature_spawn_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Entity entity = event.getEntity();
            Player player = event.getPlayer();
            
            // Check for breeding attempts
            if (entity.isAlive() && shouldCheckProtection(entity, player, "allowBreeding")) {
                event.setCancelled(true);
                protectionListener.sendProtectionMessage(player, "messages.protection.breeding");
                return;
            }

            // Check for taming attempts
            if (entity.isAlive() && shouldCheckProtection(entity, player, "allowTaming")) {
                event.setCancelled(true);
                protectionListener.sendProtectionMessage(player, "messages.protection.taming");
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "entity_interact_check");
        }
    }
}
