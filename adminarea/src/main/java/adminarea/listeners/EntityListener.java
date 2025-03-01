package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.entity.MonsterHandler;
import adminarea.event.MonsterTargetEvent;
import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.EntityHuman;
import cn.nukkit.entity.EntityLiving;
import cn.nukkit.entity.item.EntityItem;
import cn.nukkit.entity.mob.EntityMob;
import cn.nukkit.entity.passive.EntityAnimal;
import cn.nukkit.entity.passive.EntityWaterAnimal;
import cn.nukkit.entity.projectile.EntityProjectile;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.*;
import cn.nukkit.event.player.PlayerInteractEntityEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Position;
import io.micrometer.core.instrument.Timer;
import nukkitcoders.mobplugin.entities.monster.WalkingMonster;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.WeakHashMap;

public class EntityListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final ProtectionListener protectionListener;
    private final Class<?> mobPluginMonster; // Track MobPlugin's monster class
    // Replace ConcurrentHashMap with WeakHashMap for better memory management of entity classes
    private final Map<Class<?>, Boolean> isMonster = new WeakHashMap<>();
    
    // Map to track breeding foods for different animals
    private final Map<String, List<Integer>> breedingFoods;

    public EntityListener(AdminAreaProtectionPlugin plugin, ProtectionListener protectionListener) {
        this.plugin = plugin;
        this.protectionListener = protectionListener;
        
        // Initialize breeding foods map
        this.breedingFoods = new HashMap<>();
        initializeBreedingFoods();
        
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
    
    /**
     * Initialize the breeding foods map for different entity types
     */
    private void initializeBreedingFoods() {
        // Cows, Mooshrooms
        breedingFoods.put("EntityCow", List.of(Item.WHEAT));
        breedingFoods.put("EntityMooshroom", List.of(Item.WHEAT));
        
        // Sheep
        breedingFoods.put("EntitySheep", List.of(Item.WHEAT));
        
        // Pigs
        breedingFoods.put("EntityPig", List.of(Item.CARROT, Item.POTATO, Item.BEETROOT));
        
        // Chickens
        breedingFoods.put("EntityChicken", List.of(Item.SEEDS, Item.BEETROOT_SEEDS, Item.MELON_SEEDS, Item.PUMPKIN_SEEDS));
        
        // Rabbits
        breedingFoods.put("EntityRabbit", List.of(Item.CARROT, Item.GOLDEN_CARROT, Item.DANDELION));
        
        // Horses, Donkeys, Mules
        breedingFoods.put("EntityHorse", List.of(Item.GOLDEN_APPLE, Item.GOLDEN_CARROT));
        breedingFoods.put("EntityDonkey", List.of(Item.GOLDEN_APPLE, Item.GOLDEN_CARROT));
        breedingFoods.put("EntityMule", List.of(Item.GOLDEN_APPLE, Item.GOLDEN_CARROT));
        
        // Llamas
        breedingFoods.put("EntityLlama", List.of(Item.HAY_BALE));
        
        // Wolves/Dogs
        breedingFoods.put("EntityWolf", List.of(
            Item.RAW_BEEF, Item.STEAK, Item.RAW_CHICKEN, Item.COOKED_CHICKEN, 
            Item.ROTTEN_FLESH, Item.RAW_MUTTON, Item.COOKED_MUTTON, Item.RAW_RABBIT, Item.COOKED_RABBIT
        ));
        
        // Cats/Ocelots
        breedingFoods.put("EntityOcelot", List.of(Item.RAW_FISH, Item.RAW_SALMON));
        breedingFoods.put("EntityCat", List.of(Item.RAW_FISH, Item.RAW_SALMON));
        
        // Turtles
        breedingFoods.put("EntityTurtle", List.of(Item.SEAGRASS));
        
        // Pandas
        breedingFoods.put("EntityPanda", List.of(Item.BAMBOO));
        
        // Foxes
        breedingFoods.put("EntityFox", List.of(Item.SWEET_BERRIES));
    }

    // Optimized shouldCheckProtection with early bailouts and cleaner position extraction
    private boolean shouldCheckProtection(Entity entity, Player player, String permission) {
        if (entity == null) return false;
        
        // Skip if player is bypassing protection
        if (player != null && plugin.isBypassing(player.getName())) {
            return false;
        }
        
        Position pos = entity.getPosition();
        if (pos == null || pos.getLevel() == null) return false;
        
        // Skip unloaded chunks
        if (!pos.getLevel().isChunkLoaded(pos.getChunkX(), pos.getChunkZ())) {
            return false;
        }
        
        return protectionListener.shouldCancel(pos, player, permission);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Entity damager = event.getDamager();
            Entity victim = event.getEntity();
            
            // Handle fall damage if it's enabled/disabled in configuration
            if (event.getCause() == EntityDamageEvent.DamageCause.FALL && victim instanceof Player player) {
                Position pos = victim.getPosition();
                if (protectionListener.shouldCancel(pos, player, "cancelFallDamage")) {
                    event.setCancelled(true);
                    if (plugin.isDebugMode()) {
                        plugin.debug("Cancelled fall damage for player " + player.getName());
                    }
                    return;
                }
            }
            
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
                    return;
                }
            }
            
            // Player attacking monster or animal
            if (damager instanceof Player player) {
                if (victim instanceof EntityAnimal || victim instanceof EntityWaterAnimal) {
                    if (shouldCheckProtection(victim, player, "allowAnimalDamage")) {
                        event.setCancelled(true);
                        protectionListener.sendProtectionMessage(player, "messages.protection.animalDamage");
                        return;
                    }
                } else if (victim instanceof EntityMob || 
                          (mobPluginMonster != null && mobPluginMonster.isAssignableFrom(victim.getClass()))) {
                    if (shouldCheckProtection(victim, player, "allowMonsterDamage")) {
                        event.setCancelled(true);
                        protectionListener.sendProtectionMessage(player, "messages.protection.monsterDamage");
                        return;
                    }
                } else if (victim instanceof Player victimPlayer && !victimPlayer.equals(player)) {
                    // PvP check
                    if (shouldCheckProtection(victim, player, "allowPvP")) {
                        event.setCancelled(true);
                        protectionListener.sendProtectionMessage(player, "messages.protection.pvp");
                        return;
                    }
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

            // Get the entity type
            Entity entity = event.getEntity();
            String entityType = entity.getClass().getSimpleName();
            
            // For TNT entities, we should have already handled them at spawn time
            // This is just a backup in case some TNT entities were missed
            if (entityType.equals("EntityPrimedTNT")) {
                Position pos = event.getPosition();
                if (protectionListener.shouldCancel(pos, null, "allowTNT")) {
                    // Cancel the explosion completely
                    event.setCancelled(true);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Cancelled TNT explosion at " + 
                            pos.getFloorX() + ", " + pos.getFloorY() + ", " + pos.getFloorZ());
                    }
                    return;
                }
            }

            // Check the specific explosion type permission
            final String specificPermission = switch (entityType) {
                case "EntityCreeper" -> "allowCreeper";
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

                // Check for TNT entities and remove them immediately in protected areas
                String entityType = entity.getClass().getSimpleName();
                if (entityType.equals("EntityPrimedTNT")) {
                    if (protectionListener.shouldCancel(pos, null, "allowTNT")) {
                        // Remove the TNT entity immediately
                        entity.close();
                        
                        if (plugin.isDebugMode()) {
                            plugin.debug("Removed primed TNT entity at " + 
                                pos.getFloorX() + ", " + pos.getFloorY() + ", " + pos.getFloorZ());
                        }
                        return;
                    }
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
            
            if (!(entity instanceof EntityLiving)) {
                return;
            }
            
            // Enhanced breeding check logic
            if (entity instanceof EntityAnimal animal) {
                // Get breeding foods for this animal type
                String entityType = entity.getClass().getSimpleName();
                List<Integer> validBreedingFoods = breedingFoods.get(entityType);
                Item heldItem = player.getInventory().getItemInHand();
                
                // Check if player is holding a breeding item for this animal type
                if (validBreedingFoods != null && validBreedingFoods.contains(heldItem.getId())) {
                    // Check if animal can be bred (not baby and not recently bred)
                    boolean canBreed = true;
                    
                    // Check if animal is a baby (if method exists)
                    try {
                        if (animal.getClass().getMethod("isBaby").invoke(animal) == Boolean.TRUE) {
                            canBreed = false;
                        }
                    } catch (Exception ignored) {
                        // Method doesn't exist or can't be accessed
                    }
                    
                    // Check for "love mode" cooldown if that method exists
                    try {
                        if (animal.getClass().getMethod("inLoveMode").invoke(animal) == Boolean.TRUE) {
                            canBreed = false;
                        }
                    } catch (Exception ignored) {
                        // Method doesn't exist or can't be accessed
                    }
                    
                    if (canBreed && shouldCheckProtection(entity, player, "allowBreeding")) {
                        event.setCancelled(true);
                        protectionListener.sendProtectionMessage(player, "messages.protection.breeding");
                        return;
                    }
                }
            }

            // Check for taming attempts with improved detection
            if (entity instanceof EntityAnimal) {
                // Check for taming items specific to this animal type
                String entityType = entity.getClass().getSimpleName();
                Item heldItem = player.getInventory().getItemInHand();
                boolean isTamingItem = false;
                
                // Check for common taming scenarios
                switch (entityType) {
                    case "EntityWolf":
                        isTamingItem = heldItem.getId() == Item.BONE;
                        break;
                    case "EntityCat":
                    case "EntityOcelot":
                        isTamingItem = heldItem.getId() == Item.RAW_FISH || 
                                      heldItem.getId() == Item.RAW_SALMON;
                        break;
                    case "EntityParrot":
                        isTamingItem = heldItem.getId() == Item.SEEDS || 
                                      heldItem.getId() == Item.BEETROOT_SEEDS || 
                                      heldItem.getId() == Item.MELON_SEEDS || 
                                      heldItem.getId() == Item.PUMPKIN_SEEDS;
                        break;
                    case "EntityHorse":
                    case "EntityDonkey":
                    case "EntityMule":
                        // Horses don't require items for taming but have a different taming process
                        // We'll consider the interaction if the player is not holding a breeding food
                        List<Integer> breedingFood = breedingFoods.get(entityType);
                        if (breedingFood != null && !breedingFood.contains(heldItem.getId()) && 
                            heldItem.getId() != Item.SADDLE && 
                            heldItem.getId() != Item.IRON_HORSE_ARMOR && 
                            heldItem.getId() != Item.GOLD_HORSE_ARMOR && 
                            heldItem.getId() != Item.DIAMOND_HORSE_ARMOR) {
                            isTamingItem = true;
                        }
                        break;
                }
                
                if (isTamingItem && shouldCheckProtection(entity, player, "allowTaming")) {
                    event.setCancelled(true);
                    protectionListener.sendProtectionMessage(player, "messages.protection.taming");
                }
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
    
    /**
     * Handle bow shooting events specifically to prevent NullPointerException
     * This is needed because cancelling this event requires special handling
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBowShoot(EntityShootBowEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (!(event.getEntity() instanceof Player player)) {
                return;
            }
            
            // Check if the player is allowed to shoot projectiles in this area
            if (shouldCheckProtection(player, player, "allowShootProjectile")) {
                // Cancel the event to prevent the projectile from being launched
                event.setCancelled(true);
                
                // Send protection message to the player
                protectionListener.sendProtectionMessage(player, "messages.protection.projectile");
                
                // Return arrows to inventory - fix for "cannot setData(1) because arrow is null"
                // In EntityShootBowEvent, if cancelled before projectile is spawned, it will be null
                // So we need to manually restore arrows to player's inventory
                if (plugin.getConfig().getBoolean("returnCancelledArrows", true)) {
                    // Don't return arrows in creative mode
                    if (player.getGamemode() != 1) {
                        Item arrowItem = Item.get(Item.ARROW, 0, 1);
                        player.getInventory().addItem(arrowItem);
                        if (plugin.isDebugMode()) {
                            plugin.debug("Returned arrow to " + player.getName() + "'s inventory after preventing bow shot");
                        }
                    }
                }
                return;
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "bow_shoot_check");
        }
    }
    
    /**
     * Handle fall damage separately from the entity damage event for better control
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Position pos = player.getPosition();
            
            // Check both cancelFallDamage and allowFlying for fall damage cancellation
            if (protectionListener.shouldCancel(pos, player, "cancelFallDamage") || 
                protectionListener.shouldCancel(pos, player, "allowFlying")) {
                event.setCancelled(true);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Cancelled fall damage for player " + player.getName() +
                                " at " + pos.getFloorX() + ", " + pos.getFloorY() + ", " + pos.getFloorZ());
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "fall_damage_check");
        }
    }
}
