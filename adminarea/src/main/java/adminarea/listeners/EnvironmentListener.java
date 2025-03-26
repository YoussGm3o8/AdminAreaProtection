package adminarea.listeners;

import java.util.List;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.*;
import cn.nukkit.block.Block;
import cn.nukkit.block.BlockID;
import cn.nukkit.level.Position;
import io.micrometer.core.instrument.Timer;
import cn.nukkit.Player;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.entity.EntityInteractEvent;
import cn.nukkit.event.inventory.InventoryMoveItemEvent;
import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.blockentity.BlockEntityHopper;
import cn.nukkit.blockentity.BlockEntityDispenser;
import cn.nukkit.blockentity.BlockEntityDropper;
import cn.nukkit.inventory.Inventory;
import cn.nukkit.inventory.InventoryHolder;

public class EnvironmentListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final ProtectionListener protectionListener;
    private static final int CHUNK_CHECK_RADIUS = 4;
    
    // Add local cache for environment checks to reduce database lookups
    private final Cache<String, Boolean> protectionCache;
    private static final int CACHE_SIZE = 1000;
    private static final long CACHE_EXPIRY = TimeUnit.SECONDS.toMillis(30); // Short TTL for environment events

    public EnvironmentListener(AdminAreaProtectionPlugin plugin, ProtectionListener protectionListener) {
        this.plugin = plugin;
        this.protectionListener = protectionListener;
        
        this.protectionCache = Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_EXPIRY, TimeUnit.MILLISECONDS)
            .build();
    }

    private boolean shouldCheckProtection(Block block, String permission) {
        if (block == null || block.getLevel() == null) {
            return false;
        }

        try {
            Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
            try {
                Position pos = new Position(block.x, block.y, block.z, block.level);
                
                // Generate cache key
                String cacheKey = block.getLevel().getName() + ":" + 
                                 block.getChunkX() + ":" + 
                                 block.getChunkZ() + ":" +
                                 permission;
                
                // Check cache first
                Boolean cached = protectionCache.getIfPresent(cacheKey);
                if (cached != null) {
                    return cached;
                }
                
                // Skip unloaded chunks entirely
                if (!block.getLevel().isChunkLoaded(block.getChunkX(), block.getChunkZ())) {
                    protectionCache.put(cacheKey, false);
                    return false;
                }

                // Skip if world is being unloaded
                if (!plugin.getServer().isLevelLoaded(block.getLevel().getName())) {
                    protectionCache.put(cacheKey, false);
                    return false;
                }

                // Optimize global area check - most efficient path
                Area globalArea = plugin.getAreaManager().getGlobalAreaForWorld(block.getLevel().getName());
                if (globalArea != null) {
                    // For environment events in global areas, only process if there are players in the world
                    if (block.getLevel().getPlayers().isEmpty()) {
                        // Skip if no players in the world - optimization for empty worlds
                        protectionCache.put(cacheKey, false);
                        return false;
                    }
                    
                    // Check global area permission directly
                    boolean result = !globalArea.getToggleState(permission);
                    protectionCache.put(cacheKey, result);
                    return result;
                }

                // Master check with try-catch
                try {
                    if (!plugin.getAreaManager().shouldProcessEvent(pos, false)) {
                        protectionCache.put(cacheKey, false);
                        return false;
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Error in shouldProcessEvent", e);
                    return false;
                }

                // Only check local areas if we're near a player - skip expensive lookups if not
                if (!isNearAnyPlayer(block)) {
                    protectionCache.put(cacheKey, false);
                    return false;
                }

                // Get areas at location
                List<Area> areas = plugin.getAreaManager().getAreasAtLocation(
                    pos.getLevel().getName(),
                    pos.getX(),
                    pos.getY(),
                    pos.getZ()
                );

                // No protection if no areas
                if (areas.isEmpty()) {
                    protectionCache.put(cacheKey, false);
                    return false;
                }

                // Check highest priority area first
                boolean result = !areas.get(0).getToggleState(permission);
                protectionCache.put(cacheKey, result);
                return result;

            } finally {
                plugin.getPerformanceMonitor().stopTimer(sample, "environment_protection_check");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error checking protection", e);
            return false; // Fail safe
        }
    }

    // Optimized isNearAnyPlayer with bbox check
    private boolean isNearAnyPlayer(Block block) {
        int blockChunkX = block.getChunkX();
        int blockChunkZ = block.getChunkZ();
        String worldName = block.getLevel().getName();
        
        // Calculate chunk bounds for more efficient player checks
        int minChunkX = blockChunkX - CHUNK_CHECK_RADIUS;
        int maxChunkX = blockChunkX + CHUNK_CHECK_RADIUS;
        int minChunkZ = blockChunkZ - CHUNK_CHECK_RADIUS;
        int maxChunkZ = blockChunkZ + CHUNK_CHECK_RADIUS;

        for (Player player : plugin.getServer().getOnlinePlayers().values()) {
            // Skip players in different worlds
            if (!player.getLevel().getName().equals(worldName)) {
                continue;
            }

            int playerChunkX = player.getChunkX();
            int playerChunkZ = player.getChunkZ();

            // Check if player is within chunk radius using bbox check (more efficient)
            if (playerChunkX >= minChunkX && playerChunkX <= maxChunkX &&
                playerChunkZ >= minChunkZ && playerChunkZ <= maxChunkZ) {
                return true;
            }
        }

        return false;
    }

    // Event handlers with optimized protection checks
    
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (shouldCheckProtection(event.getBlock(), "allowFireSpread")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLiquidFlow(LiquidFlowEvent event) {
        if (shouldCheckProtection(event.getBlock(), "allowLiquid")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        if (event == null || event.getBlock() == null) return;
        
        try {
            Block block = event.getBlock();
            
            // Skip unloaded chunks
            if (!block.getLevel().isChunkLoaded(block.getChunkX(), block.getChunkZ())) {
                return;
            }

            if (shouldCheckProtection(block, "allowBlockSpread")) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error handling block spread", e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (shouldCheckProtection(event.getBlock(), "allowFireStart")) {
                event.setCancelled(true);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "block_ignite_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSoilDry(BlockFadeEvent event) {
        if (event.getBlock().getId() == BlockID.FARMLAND && 
            shouldCheckProtection(event.getBlock(), "allowBlockSpread")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCropGrow(BlockGrowEvent event) {
        Block block = event.getBlock();
        
        // Check if this is crop growth or another type of block spread
        if (isCrop(block)) {
            // Use the specific plant growth permission
            if (shouldCheckProtection(block, "allowPlantGrowth")) {
                event.setCancelled(true);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Prevented plant growth at " + 
                        block.x + "," + block.y + "," + block.z);
                }
            }
        } else {
            // Use the general block spread permission for non-crops
            if (shouldCheckProtection(block, "allowBlockSpread")) {
                event.setCancelled(true);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Prevented block spread at " + 
                        block.x + "," + block.y + "," + block.z);
                }
            }
        }
    }

    /**
     * Handles farmland trampling when players step on farmland
     * This is the proper event for farmland trampling in Nukkit
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTrampleFarmland(PlayerInteractEvent event) {
        // Only handle PHYSICAL interactions (stepping on, jumping on)
        if (event.getAction() != PlayerInteractEvent.Action.PHYSICAL) {
            return;
        }
        
        Block block = event.getBlock();
        // Check if the block is farmland
        if (block != null && block.getId() == BlockID.FARMLAND) {
            Player player = event.getPlayer();
            
            if (plugin.isDebugMode()) {
                plugin.debug("Detected player " + player.getName() + " interacting with farmland at " + 
                    block.x + "," + block.y + "," + block.z + " (potential trampling)");
            }
            
            // Check if farmland trampling is protected in this area
            if (shouldCheckProtection(block, "allowFarmlandTrampling")) {
                event.setCancelled(true);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Prevented farmland trampling by " + player.getName() + " at " + 
                        block.x + "," + block.y + "," + block.z);
                }
            }
        }
    }
    
    // Keep the existing method as it might catch some edge cases
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFarmlandTrample(BlockFadeEvent event) {
        Block block = event.getBlock();
        
        // Check if this is farmland being trampled (changing to dirt)
        if (block.getId() == BlockID.FARMLAND) {
            // If the block is being changed due to entity movement (trampling)
            // Note: This event will trigger for both trampling and drying out
            // Use the farmland trampling permission
            if (shouldCheckProtection(block, "allowFarmlandTrampling")) {
                event.setCancelled(true);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Prevented farmland trampling at " + 
                        block.x + "," + block.y + "," + block.z);
                }
            }
        }
    }

    /**
     * Helps determine if a block is a crop-type block
     */
    private boolean isCrop(Block block) {
        // Check common crop block IDs
        return block.getId() == BlockID.WHEAT_BLOCK ||
               block.getId() == BlockID.BEETROOT_BLOCK ||
               block.getId() == BlockID.POTATO_BLOCK ||
               block.getId() == BlockID.CARROT_BLOCK ||
               block.getId() == BlockID.PUMPKIN_STEM ||
               block.getId() == BlockID.MELON_STEM ||
               block.getId() == BlockID.SWEET_BERRY_BUSH ||
               block.getId() == BlockID.BAMBOO_SAPLING;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeafDecay(LeavesDecayEvent event) {
        if (shouldCheckProtection(event.getBlock(), "allowLeafDecay")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        String permission = event.getBlock().getId() == BlockID.ICE ? "allowIceForm" : "allowSnowForm";
        if (shouldCheckProtection(event.getBlock(), permission)) {
            event.setCancelled(true);
        }
    }

    /**
     * Handles gravity-affected blocks like sand and gravel falling
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFall(BlockFallEvent event) {
        if (shouldCheckProtection(event.getBlock(), "allowBlockGravity")) {
            event.setCancelled(true);
            if (plugin.isDebugMode()) {
                plugin.debug("Prevented block falling at " + 
                    event.getBlock().x + "," + event.getBlock().y + "," + event.getBlock().z);
            }
        }
    }

    /**
     * Alternative handler for gravity blocks if BlockFallEvent isn't working
     * This catches any block updates, including when a block might start falling
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockUpdate(BlockUpdateEvent event) {
        Block block = event.getBlock();
        // Check if this is a gravity block (sand, gravel, concrete powder, etc.)
        if (isGravityBlock(block) && shouldCheckProtection(block, "allowBlockGravity")) {
            event.setCancelled(true);
            if (plugin.isDebugMode()) {
                plugin.debug("Prevented gravity block physics at " + 
                    block.x + "," + block.y + "," + block.z);
            }
        }
    }
    
    /**
     * Checks if a block is affected by gravity
     */
    private boolean isGravityBlock(Block block) {
        return block.getId() == BlockID.SAND || 
               block.getId() == BlockID.GRAVEL || 
               block.getId() == BlockID.CONCRETE_POWDER;
    }

    /**
     * Handles farmland trampling by non-player entities (animals, etc.)
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityTrampleFarmland(EntityInteractEvent event) {
        Block block = event.getBlock();
        
        // Check if the block is farmland
        if (block != null && block.getId() == BlockID.FARMLAND) {
            if (plugin.isDebugMode()) {
                plugin.debug("Detected entity interacting with farmland at " + 
                    block.x + "," + block.y + "," + block.z + " (potential trampling)");
            }
            
            // Check if farmland trampling is protected in this area
            if (shouldCheckProtection(block, "allowFarmlandTrampling")) {
                event.setCancelled(true);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Prevented farmland trampling by entity at " + 
                        block.x + "," + block.y + "," + block.z);
                }
            }
        }
    }

    /**
     * Prevents the use of bonemeal on grass and saplings if plant growth is protected
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBonemealUse(PlayerInteractEvent event) {
        if (event.getAction() != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK || event.getItem() == null) {
            return;
        }
        
        // Check if the item is bonemeal (ID 351, damage 15 for bonemeal in Nukkit)
        if (event.getItem().getId() == 351 && event.getItem().getDamage() == 15) {
            Block targetBlock = event.getBlock();
            if (targetBlock == null) return;
            
            // Check if target is grass or sapling
            boolean isGrassOrSapling = targetBlock.getId() == BlockID.GRASS || 
                                     targetBlock.getId() == BlockID.TALL_GRASS ||
                                     (targetBlock.getId() >= BlockID.SAPLING && 
                                      targetBlock.getId() <= BlockID.SAPLING + 5);
            
            if (isGrassOrSapling) {
                if (shouldCheckProtection(targetBlock, "allowPlantGrowth")) {
                    event.setCancelled(true);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Prevented bonemeal use on " + 
                            targetBlock.getName() + " at " + 
                            targetBlock.x + "," + targetBlock.y + "," + targetBlock.z);
                    }
                }
            }
        }
    }

    /**
     * Handle inventory movement events (hoppers, droppers, etc.)
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Get source and destination inventories
            Inventory sourceInv = event.getInventory();
            Inventory targetInv = event.getTargetInventory();
            
            // Skip if not container inventories
            if (sourceInv == null || targetInv == null) {
                return;
            }
            
            // Check if this involves a hopper
            InventoryHolder sourceHolder = sourceInv.getHolder();
            InventoryHolder targetHolder = targetInv.getHolder();
            
            // Skip if inventory holders aren't block entities
            if (!(sourceHolder instanceof BlockEntity) && !(targetHolder instanceof BlockEntity)) {
                return;
            }
            
            // Check for hoppers
            boolean sourceIsHopper = sourceHolder instanceof BlockEntityHopper;
            boolean targetIsHopper = targetHolder instanceof BlockEntityHopper;
            
            if (sourceIsHopper || targetIsHopper) {
                // Get the hopper's position
                BlockEntity hopperEntity = sourceIsHopper ? 
                    (BlockEntity)sourceHolder : (BlockEntity)targetHolder;
                
                Position hopperPos = new Position(
                    hopperEntity.getX(), 
                    hopperEntity.getY(), 
                    hopperEntity.getZ(), 
                    hopperEntity.getLevel()
                );
                
                // Check if hopper operation is allowed
                if (shouldCheckProtection(hopperPos.getLevelBlock(), "allowHopper")) {
                    event.setCancelled(true);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Prevented hopper transfer at " + 
                            hopperPos.getFloorX() + "," + hopperPos.getFloorY() + "," + hopperPos.getFloorZ());
                    }
                    return;
                }
            }
            
            // Check for dispensers/droppers
            boolean sourceIsDispenser = sourceHolder instanceof BlockEntityDispenser || sourceHolder instanceof BlockEntityDropper;
            boolean targetIsDispenser = targetHolder instanceof BlockEntityDispenser || targetHolder instanceof BlockEntityDropper;
            
            if (sourceIsDispenser || targetIsDispenser) {
                // Get the dispenser's position
                BlockEntity dispenserEntity = sourceIsDispenser ? 
                    (BlockEntity)sourceHolder : (BlockEntity)targetHolder;
                
                Position dispenserPos = new Position(
                    dispenserEntity.getX(), 
                    dispenserEntity.getY(), 
                    dispenserEntity.getZ(), 
                    dispenserEntity.getLevel()
                );
                
                // Check if dispenser operation is allowed
                if (shouldCheckProtection(dispenserPos.getLevelBlock(), "allowDispenser")) {
                    event.setCancelled(true);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Prevented dispenser transfer at " + 
                            dispenserPos.getFloorX() + "," + dispenserPos.getFloorY() + "," + dispenserPos.getFloorZ());
                    }
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "inventory_move_check");
        }
    }

    // Add method to invalidate the cache - call this when areas or permissions change
    public void invalidateCache() {
        protectionCache.invalidateAll();
    }
}
