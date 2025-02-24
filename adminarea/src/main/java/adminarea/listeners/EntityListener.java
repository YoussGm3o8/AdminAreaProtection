package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.entity.MonsterHandler;
import adminarea.event.MonsterTargetEvent;
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
import cn.nukkit.entity.projectile.EntityProjectile;
import cn.nukkit.entity.passive.EntityAnimal;
import cn.nukkit.entity.passive.EntityWaterAnimal;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EntityListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final ProtectionListener protectionListener;
    private final Class<?> monster; // Add this field to track monster class
    private final Map<Class<?>, Boolean> isMonster = new ConcurrentHashMap<>(); // Cache for monster type checks

    public EntityListener(AdminAreaProtectionPlugin plugin, ProtectionListener protectionListener) {
        this.plugin = plugin;
        this.protectionListener = protectionListener;
        
        // Initialize monster class for instanceof checks
        Class<?> monsterClass = null;
        try {
            monsterClass = Class.forName("cn.nukkit.entity.monster.Monster");
        } catch (ClassNotFoundException ignored) {
            plugin.getLogger().warning("Could not find Monster class, using EntityMob for monster checks");
        }
        this.monster = monsterClass;
    }

    private boolean shouldCheckProtection(Entity entity, Player player, String permission) {
        if (entity == null || entity.getLevel() == null) {
            return false;
        }

        try {
            Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
            try {
                Position pos = entity.getPosition();
                if (pos == null || pos.getLevel() == null) {
                    return false;
                }

                // Skip unloaded chunks entirely
                if (!pos.getLevel().isChunkLoaded(pos.getChunkX(), pos.getChunkZ())) {
                    return false;
                }

                // Skip if world is being unloaded or player left
                if (!plugin.getServer().isLevelLoaded(pos.getLevel().getName())) {
                    return false;
                }

                // Master check - if this returns false, skip all protection checks
                if (!plugin.getAreaManager().shouldProcessEvent(pos, false)) {
                    return false;
                }

                // Quick bypass check
                if (player != null && plugin.isBypassing(player.getName())) {
                    return false;
                }

                // Get the global area first - more efficient
                Area globalArea = plugin.getAreaManager().getGlobalAreaForWorld(entity.getLevel().getName());
                if (globalArea != null) {
                    return !globalArea.getToggleState(permission);
                }

                // Only check local areas if we're near a player
                List<Area> areas = plugin.getAreaManager().getAreasAtLocation(
                    pos.getLevel().getName(),
                    pos.getX(),
                    pos.getY(),
                    pos.getZ()
                );

                // No protection if no areas
                if (areas.isEmpty()) {
                    return false;
                }

                // Check highest priority area first
                return !areas.get(0).getToggleState(permission);

            } finally {
                plugin.getPerformanceMonitor().stopTimer(sample, "entity_protection_check");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error checking protection", e);
            return false; // Fail safe
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Entity damager = event.getDamager();
            Entity victim = event.getEntity();
            
            // Only process if near players
            if (!plugin.getAreaManager().isNearAnyPlayer(victim.x, victim.z)) {
                return;
            }

            // Handle monster targeting through damage events
            if (damager instanceof EntityMob monster && victim instanceof Player player) {
                // Call MonsterTargetEvent
                MonsterTargetEvent targetEvent = new MonsterTargetEvent(monster, player);
                plugin.getServer().getPluginManager().callEvent(targetEvent);
                
                if (targetEvent.isCancelled() || shouldCheckProtection(damager, player, "allowMonsterTarget")) {
                    event.setCancelled(true);
                    // Reset monster's target and move it away
                    MonsterHandler.resetTarget(damager, player);
                    return;
                }
            }
            
            // Handle player damage to entities
            if (damager instanceof Player player) {
                // PvP protection
                if (victim instanceof Player && shouldCheckProtection(victim, player, "allowPvP")) {
                    event.setCancelled(true);
                    protectionListener.sendProtectionMessage(player, "messages.protection.pvp");
                    return;
                }
                
                // General entity protection - Changed permission string here
                if (shouldCheckProtection(victim, player, "allowDamageEntities")) {
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
            // Skip if not near any players
            if (!plugin.getAreaManager().isNearAnyPlayer(event.getPosition().x, event.getPosition().z)) {
                return;
            }

            // Skip if no active areas
            if (plugin.getAreaManager().getActiveAreas().isEmpty()) {
                return;
            }

            // Check the specific explosion type permission first
            String specificPermission = switch (event.getEntity().getClass().getSimpleName()) {
                case "EntityCreeper" -> "allowCreeper";
                case "EntityPrimedTNT" -> "allowTNT";
                case "EntityEndCrystal" -> "allowCrystalExplosion";
                default -> "allowExplosions";
            };

            event.setBlockList(event.getBlockList().stream()
                .filter(block -> {
                    Position pos = new Position(block.x, block.y, block.z, block.level);
                    // Check both specific and general explosion permissions
                    return !protectionListener.shouldCancel(pos, null, specificPermission) && 
                           !protectionListener.shouldCancel(pos, null, "allowExplosions");
                })
                .toList());
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "explosion_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (event == null || event.getEntity() == null) return;
        
        try {
            Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
            try {
                Entity entity = event.getEntity();
                Position pos = event.getPosition();

                // Extra null checks
                if (pos == null || pos.getLevel() == null) return;
                
                // Skip unloaded chunks
                if (!pos.getLevel().isChunkLoaded(pos.getChunkX(), pos.getChunkZ())) {
                    return;
                }

                // Early bailout using master check with try-catch
                try {
                    if (!plugin.getAreaManager().shouldProcessEvent(pos, false)) {
                        return;
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Error in shouldProcessEvent", e);
                    return;
                }

                // Handle mob spawns
                if (entity instanceof EntityMob ||
                    entity instanceof EntityAnimal ||
                    entity instanceof EntityWaterAnimal ||
                    (monster != null && isMonster.computeIfAbsent(entity.getClass(), 
                        cls -> monster.isAssignableFrom(cls)))) {
                    
                    // Determine permission based on mob type
                    String permission;
                    if (entity instanceof EntityMob || 
                        (monster != null && isMonster.get(entity.getClass()))) {
                        permission = "allowMonsterSpawn";
                    } else {
                        permission = "allowAnimalSpawn";
                    }

                    if (protectionListener.shouldCancel(pos, null, permission)) {
                        entity.close();
                        if (plugin.isDebugMode()) {
                            plugin.debug("Cancelled spawn of " + entity.getClass().getSimpleName() + 
                                       " at " + pos.toString());
                        }
                    }
                }
            } finally {
                plugin.getPerformanceMonitor().stopTimer(sample, "entity_spawn_check");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error handling entity spawn", e);
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            EntityProjectile projectile = event.getEntity();
            
            // Only check if shooter is a player
            if (projectile.shootingEntity instanceof Player player) {
                if (shouldCheckProtection(projectile, player, "allowShootProjectile")) {
                    event.setCancelled(true);
                    protectionListener.sendProtectionMessage(player, "messages.protection.projectile");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "projectile_launch_check");
        }
    }
}
