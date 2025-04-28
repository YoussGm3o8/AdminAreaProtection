package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.event.MonsterTargetEvent;
import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.EntityLiving;
import cn.nukkit.entity.mob.EntityMob;
import cn.nukkit.entity.passive.EntityAnimal;
import cn.nukkit.entity.passive.EntityWaterAnimal;
import cn.nukkit.entity.projectile.EntityEnderPearl;
import cn.nukkit.entity.projectile.EntityProjectile;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.*;
import cn.nukkit.event.player.PlayerInteractEntityEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.item.Item;
import cn.nukkit.item.ItemSpawnEgg;
import cn.nukkit.level.Position;
import io.micrometer.core.instrument.Timer;
import cn.nukkit.block.Block;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.WeakHashMap;

public class EntityListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final ProtectionListener protectionListener;
    private final Class<?> mobPluginMonster; // Track MobPlugin's monster class
    private final Class<?> walkingMonsterClass; // Track MobPlugin's WalkingMonster class
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
        Class<?> walkingMonsterClass = null;
        try {
            monsterClass = Class.forName("nukkitcoders.mobplugin.entities.monster.Monster");
            plugin.getLogger().info("Found MobPlugin Monster interface for monster checks");
        } catch (ClassNotFoundException ignored) {
            plugin.getLogger().warning("Could not find MobPlugin Monster interface, using WalkingMonster for checks");
            try {
                monsterClass = Class.forName("nukkitcoders.mobplugin.entities.monster.WalkingMonster");
                plugin.getLogger().info("Found MobPlugin WalkingMonster class for monster checks");
            } catch (ClassNotFoundException e) {
                plugin.getLogger().warning("Could not find WalkingMonster class, monster protection will be limited to Nukkit's EntityMob");
                monsterClass = null;
            }
        }
        
        // Initialize WalkingMonster class separately
        try {
            walkingMonsterClass = Class.forName("nukkitcoders.mobplugin.entities.monster.WalkingMonster");
        } catch (ClassNotFoundException e) {
            walkingMonsterClass = null;
        }
        
        this.mobPluginMonster = monsterClass;
        this.walkingMonsterClass = walkingMonsterClass;
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
        breedingFoods.put("EntityTraderLlama", List.of(Item.HAY_BALE));
        
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
        
        // Additional entities from ProtectionListener
        // Bees
        breedingFoods.put("EntityBee", List.of(
            37,  // Dandelion
            38,  // Poppy
            39,  // Blue Orchid
            40,  // Allium
            41,  // Azure Bluet
            42   // Red/White Tulip and other flowers
        ));
        
        // Axolotl
        breedingFoods.put("EntityAxolotl", List.of(Item.BUCKET));
        
        // Goat
        breedingFoods.put("EntityGoat", List.of(Item.WHEAT));
        
        // Strider
        breedingFoods.put("EntityStrider", List.of(470)); // Warped Fungus
        
        // Hoglin
        breedingFoods.put("EntityHoglin", List.of(473)); // Crimson Fungus
        
        // Frog
        breedingFoods.put("EntityFrog", List.of(349)); // Slimeball
        
        // Camel
        breedingFoods.put("EntityCamel", List.of(Item.CACTUS));
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
        
        return protectionListener.handleProtection(pos, player, permission);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Entity damager = event.getDamager();
            Entity victim = event.getEntity();
            
            // Handle direct explosion damage from explosive entities
            if (victim instanceof Player player && 
                event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
                
                Position pos = victim.getPosition();
                // Skip if not in loaded chunk
                if (pos.getLevel() == null || !pos.getLevel().isChunkLoaded(pos.getChunkX(), pos.getChunkZ())) {
                    return;
                }
                
                // Get area at position
                Area area = plugin.getAreaManager().getHighestPriorityArea(
                    pos.getLevel().getName(), pos.getX(), pos.getY(), pos.getZ());
                
                if (area == null) {
                    return;
                }
                
                // Determine which explosion type it is based on the damager
                boolean shouldCancel = false;
                String explosionTypeName = "Unknown";
                
                String entityType = damager.getClass().getSimpleName();
                if (entityType.equals("EntityPrimedTNT")) {
                    shouldCancel = !area.getToggleState("allowTNT");
                    explosionTypeName = "TNT";
                } else if (entityType.equals("EntityCreeper")) {
                    shouldCancel = !area.getToggleState("allowCreeper");
                    explosionTypeName = "Creeper";
                } else if (entityType.equals("EntityEndCrystal")) {
                    shouldCancel = !area.getToggleState("allowCrystalExplosion");
                    explosionTypeName = "End Crystal";
                } else {
                    // Generic explosion protection for unidentified explosion entities
                    shouldCancel = !area.getToggleState("allowExplosions");
                    explosionTypeName = entityType;
                }
                
                if (shouldCancel) {
                    event.setCancelled(true);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Cancelled " + explosionTypeName + " explosion damage to player " + player.getName() +
                                   " at " + pos.getFloorX() + "," + pos.getFloorY() + "," + pos.getFloorZ() +
                                   " in area " + area.getName());
                    }
                    return;
                }
            }
            
            // Handle fall damage if it's enabled/disabled in configuration
            if (event.getCause() == EntityDamageEvent.DamageCause.FALL && victim instanceof Player player) {
                Position pos = victim.getPosition();
                // Check if fall damage is NOT allowed (inverted logic)
                if (!protectionListener.handleProtection(pos, player, "allowFallDamage")) {
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

            // Handle monster targeting through damage events - safely check for MobPlugin's WalkingMonster
            if (walkingMonsterClass != null && walkingMonsterClass.isInstance(damager) && victim instanceof Player player) {
                // Only call MonsterTargetEvent if the WalkingMonster class is available
                try {
                    // Call MonsterTargetEvent - use direct call with Entity instead of reflection
                    MonsterTargetEvent targetEvent = new MonsterTargetEvent(damager, player);
                    plugin.getServer().getPluginManager().callEvent(targetEvent);
                    
                    if (targetEvent.isCancelled() || shouldCheckProtection(damager, player, "allowMonsterTarget")) {
                        event.setCancelled(true);
                        // Send a message to the player that monster targeting is disabled
                        protectionListener.sendProtectionMessage(player, "messages.protection.monsterTarget");
                        return;
                    }
                } catch (Exception e) {
                    // Silently handle errors with MonsterTargetEvent
                    if (plugin.isDebugMode()) {
                        plugin.debug("Error handling MonsterTargetEvent: " + e.getMessage());
                    }
                    
                    // Still check protection even if event fails
                    if (shouldCheckProtection(damager, player, "allowMonsterTarget")) {
                        event.setCancelled(true);
                        protectionListener.sendProtectionMessage(player, "messages.protection.monsterTarget");
                        return;
                    }
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
                } else if (victim instanceof EntityMob || isMobPluginMonster(victim)) {
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Get the entity type
            Entity entity = event.getEntity();
            String entityType = entity.getClass().getSimpleName();
            
            // For TNT entities, we need to handle them immediately
            if (entityType.equals("EntityPrimedTNT")) {
                Position pos = event.getPosition();
                
                // Skip if not in loaded chunk
                if (pos.getLevel() == null || !pos.getLevel().isChunkLoaded(pos.getChunkX(), pos.getChunkZ())) {
                    return;
                }
                
                // Get area at position
                Area area = plugin.getAreaManager().getHighestPriorityArea(
                    pos.getLevel().getName(), pos.getX(), pos.getY(), pos.getZ());
                
                if (area != null && !area.getToggleState("allowTNT")) {
                    // Instead of trying to cancel the event (which isn't cancellable),
                    // just close the entity directly
                    // event.setCancelled(true);
                    
                    // Ensure the entity is immediately closed/removed
                    entity.close();
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Instantly despawned primed TNT entity at " + 
                            pos.getFloorX() + ", " + pos.getFloorY() + ", " + pos.getFloorZ() +
                            " in area " + area.getName());
                    }
                    return;
                }
            }

            // Skip costly checks if not near any players
            if (!plugin.getAreaManager().isNearAnyPlayer(event.getPosition().x, event.getPosition().z)) {
                return;
            }

            // Skip if no active areas
            if (plugin.getAreaManager().getActiveAreas().isEmpty()) {
                return;
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
                    return !protectionListener.handleProtection(pos, null, specificPermission) && 
                           !protectionListener.handleProtection(pos, null, "allowExplosions");
                })
                .toList());
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "explosion_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Entity entity = event.getEntity();
            
            // Fast path for TNT - check this first to instantly despawn it
            if (entity != null && entity.getClass().getSimpleName().equals("EntityPrimedTNT")) {
                // Get entity position
                Position pos = entity.getPosition();
                if (pos == null || pos.getLevel() == null) {
                    return;
                }
                
                // Check if spawning in an area with protection
                if (!pos.getLevel().isChunkLoaded(pos.getChunkX(), pos.getChunkZ())) {
                    return;
                }
                
                // Get the highest priority area at this location
                Area area = plugin.getAreaManager().getHighestPriorityArea(
                    pos.getLevel().getName(), pos.getX(), pos.getY(), pos.getZ());
                
                if (area != null && !area.getToggleState("allowTNT")) {
                    // Instead of trying to cancel the event (which isn't cancellable),
                    // just close the entity directly
                    // event.setCancelled(true);
                    
                    // Ensure the entity is immediately closed/removed
                    entity.close();
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Instantly despawned primed TNT entity at " + 
                            pos.getFloorX() + ", " + pos.getFloorY() + ", " + pos.getFloorZ() +
                            " in area " + area.getName());
                    }
                    return;
                } else if (plugin.isDebugMode() && area != null) {
                    plugin.debug("Allowed TNT to spawn at " + 
                        pos.getFloorX() + ", " + pos.getFloorY() + ", " + pos.getFloorZ() +
                        " in area " + area.getName());
                }
            }
            
            // Skip processing for non-living entities, arrows, etc.
            if (!(entity instanceof EntityLiving) || entity instanceof EntityProjectile) {
                return;
            }
            
            // Skip if not near any players - early performance optimization
            if (!plugin.getAreaManager().isNearAnyPlayer(entity.x, entity.z)) {
                return;
            }
            
            // Get entity position
            Position pos = entity.getPosition();
            if (pos == null || pos.getLevel() == null) {
                return;
            }
            
            // Check if spawning in an area with protection
            if (!pos.getLevel().isChunkLoaded(pos.getChunkX(), pos.getChunkZ())) {
                return;
            }
            
            // Get the highest priority area at this location
            Area area = plugin.getAreaManager().getHighestPriorityArea(
                pos.getLevel().getName(), pos.getX(), pos.getY(), pos.getZ());
            
            if (area != null) {
                // Optimized entity type checking with caching
                boolean isEntityAnimal = entity instanceof EntityAnimal;
                boolean isEntityWaterAnimal = entity instanceof EntityWaterAnimal;
                boolean isMobPluginMonster = isMobPluginMonster(entity);
                
                // Only check mob spawns if the entity is a mob type
                if (isEntityAnimal || isEntityWaterAnimal || isMobPluginMonster || entity instanceof EntityMob) {
                    // Determine permission based on mob type - moved outside the condition for clarity
                    String permission;
                    if (isMobPluginMonster || entity instanceof EntityMob) {
                        permission = "allowMonsterSpawn";
                    } else {
                        permission = "allowAnimalSpawn";
                    }

                    // Check if spawn should be cancelled
                    if (protectionListener.handleProtection(pos, null, permission)) {
                        entity.close();
                        if (plugin.isDebugMode()) {
                            plugin.debug("Cancelled spawn of " + entity.getClass().getSimpleName() + 
                                       " at " + pos.toString());
                        }
                    }
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "entity_spawn_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getPlayer();
            Entity entity = event.getEntity();
            
            // Skip if player is bypassing protection
            if (plugin.isBypassing(player.getName())) {
                return;
            }
            
            // Check for armor stand interaction
            if (entity.getClass().getSimpleName().equals("EntityArmorStand")) {
                // Check if player can interact with armor stands in this area
                if (shouldCheckProtection(entity, player, "allowArmorStand")) {
                    event.setCancelled(true);
                    protectionListener.sendProtectionMessage(player, "messages.protection.armorStand");
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Prevented armor stand interaction by " + player.getName() + 
                                   " at " + entity.getX() + "," + entity.getY() + "," + entity.getZ());
                    }
                    return;
                }
            }
            
            // Check breeding
            if (entity instanceof EntityAnimal animal) {
                // Check for taming attempt
                if (isTamingAttempt(entity, player)) {
                    // Check if animal is already tamed using MobPlugin's methods
                    boolean canTame = true;
                    try {
                        if (animal.getClass().getMethod("isTamed").invoke(animal) == Boolean.TRUE) {
                            canTame = false;
                        }
                    } catch (Exception ignored) {
                        // Method doesn't exist or can't be accessed
                    }
                    
                    if (canTame && shouldCheckProtection(entity, player, "allowTaming")) {
                        event.setCancelled(true);
                        protectionListener.sendProtectionMessage(player, "messages.protection.taming");
                        return;
                    }
                }
                
                // Check for breeding attempt
                if (isBreedingAttempt(entity, player)) {
                    // Check if animal can breed
                    boolean canBreed = true;
                    try {
                        // Use reflection to access isBaby method
                        if ((Boolean)animal.getClass().getMethod("isBaby").invoke(animal) ||
                            animal.getClass().getMethod("inLove").invoke(animal) == Boolean.TRUE) {
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
            } else if (entity instanceof EntityAnimal) {
                // Fallback for vanilla animals
                if (isBreedingAttempt(entity, player) && canAnimalBreed((EntityAnimal)entity)) {
                    if (shouldCheckProtection(entity, player, "allowBreeding")) {
                        event.setCancelled(true);
                        protectionListener.sendProtectionMessage(player, "messages.protection.breeding");
                        return;
                    }
                }
                
                // Check for taming attempts with vanilla animals
                if (isTamingAttempt(entity, player)) {
                    if (shouldCheckProtection(entity, player, "allowTaming")) {
                        event.setCancelled(true);
                        protectionListener.sendProtectionMessage(player, "messages.protection.taming");
                    }
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
        // Handle fall damage
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            if (!(event.getEntity() instanceof Player player)) {
                return;
            }
            
            Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
            try {
                Position pos = player.getPosition();
                
                // Check if fall damage is NOT allowed or if flying is allowed
                // Note: We need to invert allowFallDamage because:
                // - If allowFallDamage is true, we should NOT cancel the event (let fall damage occur)
                // - If allowFallDamage is false, we SHOULD cancel the event (prevent fall damage)
                if (!protectionListener.handleProtection(pos, player, "allowFallDamage") || 
                    protectionListener.handleProtection(pos, player, "allowFlying")) {
                    event.setCancelled(true);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Cancelled fall damage for player " + player.getName() +
                                    " at " + pos.getFloorX() + ", " + pos.getFloorY() + ", " + pos.getFloorZ());
                    }
                }
            } finally {
                plugin.getPerformanceMonitor().stopTimer(sample, "fall_damage_check");
            }
            return;
        }
        
        // Handle explosion damage to players
        if ((event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION ||
             event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) &&
            event.getEntity() instanceof Player player) {
            
            Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
            try {
                Position pos = player.getPosition();
                
                // Get the area at the player's position
                Area area = plugin.getAreaManager().getHighestPriorityArea(
                    pos.getLevel().getName(), pos.getX(), pos.getY(), pos.getZ());
                
                if (area == null) {
                    return;
                }
                
                // Check for entity explosion source if available
                Entity damager = null;
                if (event instanceof EntityDamageByEntityEvent damageByEntityEvent) {
                    damager = damageByEntityEvent.getDamager();
                }
                
                // Determine which explosion type toggle to check
                boolean shouldCancel = false;
                String explosionType = "unknown";
                
                if (damager != null) {
                    String entityType = damager.getClass().getSimpleName();
                    
                    // Check specific entity types
                    if (entityType.equals("EntityPrimedTNT")) {
                        shouldCancel = !area.getToggleState("allowTNT");
                        explosionType = "TNT";
                    } else if (entityType.equals("EntityCreeper")) {
                        shouldCancel = !area.getToggleState("allowCreeper");
                        explosionType = "Creeper";
                    } else if (entityType.equals("EntityEndCrystal")) {
                        shouldCancel = !area.getToggleState("allowCrystalExplosion");
                        explosionType = "End Crystal";
                    } else {
                        // If we can't identify the specific entity, check generic explosion toggle
                        shouldCancel = !area.getToggleState("allowExplosions");
                        explosionType = entityType;
                    }
                } else {
                    // If it's a block explosion (e.g., bed) or an unidentified entity explosion
                    if (event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
                        // For bed explosions in nether/end
                        shouldCancel = !area.getToggleState("allowBedExplosion");
                        explosionType = "Bed";
                    } else {
                        // Generic explosion protection as fallback
                        shouldCancel = !area.getToggleState("allowExplosions");
                        explosionType = "Unknown";
                    }
                }
                
                // Cancel the damage if the toggle is disabled
                if (shouldCancel) {
                    event.setCancelled(true);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Cancelled " + explosionType + " explosion damage to player " + player.getName() +
                                   " at " + pos.getFloorX() + "," + pos.getFloorY() + "," + pos.getFloorZ() +
                                   " in area " + area.getName());
                    }
                }
            } finally {
                plugin.getPerformanceMonitor().stopTimer(sample, "explosion_damage_check");
            }
        }
    }
    
    /**
     * Handle ender pearl landing - Prevents teleporting into protected areas
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnderPearlLand(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EntityEnderPearl pearl)) {
            return;
        }

        if (!(pearl.shootingEntity instanceof Player player)) {
            return;
        }

        // Check if ender pearls are allowed at landing position
        if (protectionListener.handleProtection(event.getEntity().getPosition(), player, "allowEnderPearl")) {
            // Cancel the upcoming teleport by sending player back
            player.teleport(player.getPosition());
            protectionListener.sendProtectionMessage(player, "messages.protection.enderPearl");
            
            // Return the ender pearl to the player's inventory if they're not in creative mode
            if (player.getGamemode() != 1) { // 1 = Creative Mode
                Item enderPearl = Item.get(Item.ENDER_PEARL, 0, 1);
                player.getInventory().addItem(enderPearl);
                if (plugin.isDebugMode()) {
                    plugin.debug("Returned ender pearl to " + player.getName() + "'s inventory after preventing teleport");
                }
            }
        }
    }

    /**
     * Check if a player is attempting to tame an animal
     */
    public boolean isTamingAttempt(Entity entity, Player player) {
        if (entity == null || player == null) return false;
        
        Item heldItem = player.getInventory().getItemInHand();
        if (heldItem == null) return false;
        
        String entityType = entity.getClass().getSimpleName();
        int itemId = heldItem.getId();
        
        return switch (entityType) {
            case "EntityWolf" -> itemId == Item.BONE;
            case "EntityCat", "EntityOcelot" -> itemId == Item.RAW_FISH;
            case "EntityParrot" -> itemId == Item.SEEDS;
            case "EntityHorse", "EntityDonkey", "EntityMule" -> entity.getPassengers().isEmpty() && (itemId == Item.SADDLE);
            case "EntityLlama", "EntityTraderLlama" -> entity.getPassengers().isEmpty() && (itemId == Item.CARPET);
            default -> false;
        };
    }

    /**
     * Public method to check if a player is attempting to breed an entity
     * This is used by ProtectionListener to avoid duplicating code
     * 
     * @param entity The entity being interacted with
     * @param player The player attempting the breeding
     * @return true if this is a valid breeding attempt, false otherwise
     */
    public boolean isBreedingAttempt(Entity entity, Player player) {
        if (entity == null || player == null) return false;
        
        Item heldItem = player.getInventory().getItemInHand();
        if (heldItem == null) return false;
        
        String entityType = entity.getClass().getSimpleName();
        List<Integer> validBreedingFoods = breedingFoods.get(entityType);
        
        return validBreedingFoods != null && validBreedingFoods.contains(heldItem.getId());
    }
    
    /**
     * Check if an animal is ready to breed (not a baby and not in love mode)
     * 
     * @param animal The animal to check
     * @return true if the animal can be bred, false otherwise
     */
    public boolean canAnimalBreed(EntityAnimal animal) {
        if (animal == null) return false;
        
        // Check if animal is a baby
        try {
            if (animal.getClass().getMethod("isBaby").invoke(animal) == Boolean.TRUE) {
                return false;
            }
        } catch (Exception ignored) {
            // Method doesn't exist or can't be accessed
        }
        
        // Check for "love mode" cooldown
        try {
            if (animal.getClass().getMethod("inLoveMode").invoke(animal) == Boolean.TRUE) {
                return false;
            }
        } catch (Exception ignored) {
            // Method doesn't exist or can't be accessed
        }
        
        return true;
    }

    /**
     * Handle spawn egg usage to ensure it respects the animal spawn permission
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnEggUse(PlayerInteractEvent event) {
        // Only handle right clicks on blocks
        if (event.getAction() != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        
        Player player = event.getPlayer();
        
        // Skip if player is bypassing protection
        if (plugin.isBypassing(player.getName())) {
            return;
        }
        
        // Check if the player is using a spawn egg
        if (player.getInventory().getItemInHand() instanceof ItemSpawnEgg) {
            // Get the position where the entity would be spawned
            Block block = event.getBlock();
            Position spawnPos = new Position(
                block.x + 0.5, 
                block.y + 1.0, 
                block.z + 0.5, 
                block.level
            );
            
            // Check if animal spawning is allowed at this position
            if (protectionListener.handleProtection(spawnPos, player, "allowAnimalSpawn")) {
                // Cancel the event if not allowed
                event.setCancelled(true);
                
                // Send message to player
                protectionListener.sendProtectionMessage(
                    player, 
                    "messages.protection.animalSpawn"
                );
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Prevented spawn egg usage by " + player.getName() + 
                        " at " + spawnPos.getFloorX() + "," + spawnPos.getFloorY() + "," + spawnPos.getFloorZ());
                }
            }
        }
    }

    /**
     * Safely check if an entity is a MobPlugin monster without risking ClassNotFoundException
     * @param entity The entity to check
     * @return true if the entity is a MobPlugin monster
     */
    private boolean isMobPluginMonster(Entity entity) {
        if (entity == null) return false;
        
        // Fast path: check if it's an EntityMob (Nukkit core class)
        if (entity instanceof EntityMob) return true;
        
        // Check for MobPlugin monsters if the class is available
        if (mobPluginMonster != null) {
            // Get from cache or compute
            return isMonster.computeIfAbsent(entity.getClass(), 
                cls -> mobPluginMonster.isAssignableFrom(cls));
        }
        
        // If MobPlugin is not available, just use EntityMob check
        return false;
    }
}


