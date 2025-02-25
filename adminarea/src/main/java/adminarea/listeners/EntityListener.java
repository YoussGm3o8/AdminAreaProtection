package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.entity.MonsterHandler;
import adminarea.event.MonsterTargetEvent;
import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
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
import nukkitcoders.mobplugin.entities.monster.WalkingMonster;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public class EntityListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final ProtectionListener protectionListener;
    private final Class<?> mobPluginMonster; // Track MobPlugin's monster class
    // Replace ConcurrentHashMap with WeakHashMap for better memory management of entity classes
    private final Map<Class<?>, Boolean> isMonster = new WeakHashMap<>();

    public EntityListener(AdminAreaProtectionPlugin plugin, ProtectionListener protectionListener) {
        this.plugin = plugin;
        this.protectionListener = protectionListener;
        
        // Initialize MobPlugin's monster class for instanceof checks
        Class<?> monsterClass = null;
        try {
            monsterClass = Class.forName("nukkitcoders.mobplugin.entities.monster.Monster");
        } catch (ClassNotFoundException ignored) {
            plugin.getLogger().warning("Could not find MobPlugin Monster interface, using WalkingMonster for checks");
            try {
                monsterClass = Class.forName("nukkitcoders.mobplugin.entities.monster.WalkingMonster");
            } catch (ClassNotFoundException e) {
                plugin.getLogger().warning("Could not find WalkingMonster class, monster protection may not work correctly");
            }
        }
        this.mobPluginMonster = monsterClass;
    }

    // Optimized shouldCheckProtection with early bailouts and cleaner position extraction
    private boolean shouldCheckProtection(Entity entity, Player player, String permission) {
        if (entity == null || entity.getLevel() == null) {
            return false;
        }

        try {
            Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
            try {
                // Quick bypass check first - most efficient
                if (player != null && plugin.isBypassing(player.getName())) {
                    return false;
                }
                
                Position pos = entity.getPosition();
                if (pos == null || pos.getLevel() == null) {
                    return false;
                }

                // Skip unloaded chunks entirely
                if (!pos.getLevel().isChunkLoaded(pos.getChunkX(), pos.getChunkZ())) {
                    return false;
                }

                // Skip if world is being unloaded
                if (!plugin.getServer().isLevelLoaded(pos.getLevel().getName())) {
                    return false;
                }

                // Master check - if this returns false, skip all protection checks
                if (!plugin.getAreaManager().shouldProcessEvent(pos, false)) {
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
            
            // Only process if near players - early bailout
            if (!plugin.getAreaManager().isNearAnyPlayer(victim.x, victim.z)) {
                return;
            }

            // Handle monster targeting through damage events - use MobPlugin's WalkingMonster
            if (damager instanceof WalkingMonster && victim instanceof Player player) {
                // Call MonsterTargetEvent
                MonsterTargetEvent targetEvent = new MonsterTargetEvent((WalkingMonster)damager, player);
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
                // PvP protection - check this first as it's common
                if (victim instanceof Player) {
                    if (shouldCheckProtection(victim, player, "allowPvP")) {
                        event.setCancelled(true);
                        protectionListener.sendProtectionMessage(player, "messages.protection.pvp");
                        return;
                    }
                }
                
                // General entity protection
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
            // Skip if not near any players - early performance optimization
            if (!plugin.getAreaManager().isNearAnyPlayer(event.getPosition().x, event.getPosition().z)) {
                return;
            }

            // Skip if no active areas - another early bailout
            if (plugin.getAreaManager().getActiveAreas().isEmpty()) {
                return;
            }

            // Check the specific explosion type permission first
            // Use string interpolation for specificPermission instead of temporary strings
            String entityType = event.getEntity().getClass().getSimpleName();
            final String specificPermission = switch (entityType) {
                case "EntityCreeper" -> "allowCreeper";
                case "EntityPrimedTNT" -> "allowTNT";
                case "EntityEndCrystal" -> "allowCrystalExplosion";
                default -> "allowExplosions";
            };

            // Use a more efficient method that doesn't create new objects
            // We'll filter blocks directly without creating unnecessary collections
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

                // Optimized entity type checking with caching
                boolean isEntityAnimal = entity instanceof EntityAnimal;
                boolean isEntityWaterAnimal = entity instanceof EntityWaterAnimal;
                boolean isMobPluginMonster = entity instanceof WalkingMonster;
                boolean isMonsterClass = false;
                
                if (mobPluginMonster != null) {
                    // Use computeIfAbsent to avoid unnecessary class checks
                    isMonsterClass = isMonster.computeIfAbsent(entity.getClass(), 
                        cls -> mobPluginMonster.isAssignableFrom(cls));
                }
                
                // Only check mob spawns if the entity is a mob type
                if (isEntityAnimal || isEntityWaterAnimal || isMobPluginMonster || isMonsterClass) {
                    // Determine permission based on mob type - moved outside the condition for clarity
                    String permission;
                    if (isMobPluginMonster || isMonsterClass) {
                        permission = "allowMonsterSpawn";
                    } else {
                        permission = "allowAnimalSpawn";
                    }

                    // Check if spawn should be cancelled
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
