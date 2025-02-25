package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaDTO;
import adminarea.event.AreaDeletedEvent;
import adminarea.event.AreaPermissionUpdateEvent;
import adminarea.event.LuckPermsGroupChangeEvent;
import adminarea.event.PermissionChangeEvent;
import adminarea.exception.DatabaseException;
import adminarea.permissions.PermissionChecker;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.block.BlockID;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.*;
import cn.nukkit.event.entity.*;
import cn.nukkit.event.player.*;
import cn.nukkit.event.redstone.RedstoneUpdateEvent;
import cn.nukkit.inventory.ContainerInventory;
import cn.nukkit.item.Item;
import cn.nukkit.level.Position;
import cn.nukkit.utils.LogLevel;
import io.micrometer.core.instrument.Timer;
import cn.nukkit.event.inventory.InventoryOpenEvent;
import cn.nukkit.event.vehicle.EntityEnterVehicleEvent;
import cn.nukkit.event.vehicle.VehicleCreateEvent;
import cn.nukkit.event.vehicle.VehicleDamageEvent;
import cn.nukkit.event.inventory.InventoryPickupItemEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import adminarea.area.Area;
import adminarea.area.AreaDTO;
import adminarea.event.AreaDeletedEvent;
import adminarea.event.AreaPermissionUpdateEvent;
import adminarea.event.LuckPermsGroupChangeEvent;
import adminarea.event.PermissionChangeEvent;
import cn.nukkit.math.Vector3;
import cn.nukkit.entity.passive.EntityWolf;
import cn.nukkit.entity.passive.EntityCat;
import cn.nukkit.entity.passive.EntityParrot;
import cn.nukkit.entity.item.EntityVehicle;
import cn.nukkit.entity.item.EntityMinecartEmpty;
import cn.nukkit.entity.item.EntityBoat;

/**
 * Handles all protection-related events and permission checks.
 */
public class ProtectionListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final Map<String, Set<String>> temporaryPermissions;
    private final Cache<String, Boolean> protectionCache;
    private final Cache<String, Object> explosionAreaCache;
    private final Map<String, Long> lastWarningTime;
    private static final long WARNING_COOLDOWN = 3000; // 3 seconds
    private static final int CACHE_SIZE = 2000; // Increased from 1000
    private static final long CACHE_EXPIRY = TimeUnit.MINUTES.toMillis(5);
    private final Cache<String, Boolean> permissionCache;
    private static final long PERMISSION_CACHE_DURATION = TimeUnit.MINUTES.toMillis(5);
    private final Map<String, Map<String, Boolean>> playerPermissionCache = new ConcurrentHashMap<>();
    private static final int BATCH_SIZE = 16; // Process explosion blocks in batches
    private static final int CHUNK_CHECK_RADIUS = 4;
    private final PermissionChecker permissionChecker;
    private final Map<String, String> playerAreaCache;
    private static final int CHECK_INTERVAL = 5; // Ticks between checks
    private final Map<String, Integer> lastCheckTimes;

    public ProtectionListener(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.temporaryPermissions = new ConcurrentHashMap<>();
        this.protectionCache = Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_EXPIRY, TimeUnit.MILLISECONDS)
            .build();
        this.explosionAreaCache = Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_EXPIRY, TimeUnit.MILLISECONDS)
            .build();
        this.lastWarningTime = new ConcurrentHashMap<>();
        this.permissionCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();
        this.permissionChecker = new PermissionChecker(plugin);
        this.playerAreaCache = new ConcurrentHashMap<>();
        this.lastCheckTimes = new ConcurrentHashMap<>();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteractBlock(PlayerInteractEvent event) {
        if (event.getAction() == PlayerInteractEvent.Action.LEFT_CLICK_BLOCK) {
            Block block = event.getBlock();
            Player player = event.getPlayer();

            // Check break permission early to prevent animation
            Position pos = new Position(block.x, block.y, block.z, block.level);
            if (handleProtection(pos, player, "break")) {
                event.setCancelled(true);
                // Send block update to prevent client-side break animation
                block.level.sendBlocks(new Player[]{player}, new Block[]{block});
                // Only show message if not on cooldown
                sendProtectionMessage(player, "messages.protection.blockBreak");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();

        Position pos = new Position(block.x, block.y, block.z, block.level);
        if (handleProtection(pos, player, "break")) {
            event.setCancelled(true);
            // Send block update to prevent client-side break animation
            block.level.sendBlocks(new Player[]{player}, new Block[]{block});
            sendProtectionMessage(player, "messages.protection.blockBreak");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();

        Position pos = new Position(block.x, block.y, block.z, block.level);
        if (handleProtection(pos, player, "build")) {
            event.setCancelled(true);
            // Send block update to prevent client desyncs
            block.level.sendBlocks(new Player[]{player}, new Block[]{block});
            sendProtectionMessage(player, "messages.protection.blockPlace");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();

        // Skip air interactions
        if (block.getId() == Block.AIR) {
            return;
        }

        Position pos = new Position(block.x, block.y, block.z, block.level);

        // Handle container interactions separately
        if (isContainer(block)) {
            if (handleProtection(pos, player, "container")) {
                event.setCancelled(true);
                sendProtectionMessage(player, "messages.protection.container");
            }
            return;
        }

        // Handle other interactions
        if (handleProtection(pos, player, "interact")) {
            event.setCancelled(true);
            sendProtectionMessage(player, "messages.protection.interact");
        }
    }

    /**
     * Handles redstone events. Note that BlockRedstoneEvent is not cancellable in Nukkit.
     * This handler logs the events but doesn't attempt to cancel them.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Block block = event.getBlock();
            Position pos = new Position(block.x, block.y, block.z, block.level);
            
            // Log the attempt if redstone is protected in this area
            if (handleProtection(pos, null, "gui.permissions.toggles.allowRedstone") && plugin.isDebugMode()) {
                plugin.debug("Redstone event detected at " + pos.x + ", " + pos.y + ", " + pos.z +
                             " (BlockRedstoneEvent not cancellable, old power: " + event.getOldPower() +
                             ", new power: " + event.getNewPower() + ")");
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "redstone_check");
        }
    }
    
    /**
     * Handles redstone update events. This event is cancellable and provides a more
     * effective way to prevent redstone activity in protected areas.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRedstoneUpdate(RedstoneUpdateEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Block block = event.getBlock();
            Position pos = new Position(block.x, block.y, block.z, block.level);
            
            if (handleProtection(pos, null, "gui.permissions.toggles.allowRedstone")) {
                event.setCancelled(true);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Cancelled redstone update at " + pos.x + ", " + pos.y + ", " + pos.z);
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "redstone_update_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Entity damager = event.getDamager();
            Entity victim = event.getEntity();
            
            if (damager instanceof Player player) {
                // Vehicle damage check
                if (isVehicle(victim)) {
                    if (handleProtection(victim, player, "allowVehicleBreak")) {
                        event.setCancelled(true);
                        sendProtectionMessage(player, "messages.protection.vehicleDamage");
                        return;
                    }
                }
                
                // PvP check
                if (victim instanceof Player) {
                    if (handleProtection(victim, player, "gui.permissions.toggles.allowPvP")) {
                        event.setCancelled(true);
                        sendProtectionMessage(player, "messages.protection.pvp");
                        return;
                    }
                }
                
                // General entity damage check
                if (handleProtection(victim, player, "gui.permissions.toggles.allowDamageEntities")) {
                    event.setCancelled(true);
                    sendProtectionMessage(player, "messages.protection.entityDamage");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "entity_damage_check");
        }
    }

    /**
     * Optimized explosion handler - significantly reduces processing overhead
     * by using spatial optimization techniques
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Quick check - if no blocks affected, nothing to do
            List<Block> affectedBlocks = event.getBlockList();
            if (affectedBlocks.isEmpty()) {
                return;
            }
            
            // Get explosion source info
            String worldName = event.getPosition().getLevel().getName();
            Entity explodingEntity = event.getEntity();
            
            // Check if the explosion type is protected globally
            String explosionPermission = getExplosionPermission(explodingEntity);
            
            // Calculate explosion bounding box to efficiently find affected areas
            ExplosionBounds bounds = calculateExplosionBounds(affectedBlocks);
            if (bounds == null) return;
            
            // Get all potentially affected areas (single spatial query instead of per-block)
            List<Area> potentialAreas = getAreasInExplosionRange(worldName, bounds);
            if (potentialAreas.isEmpty() && !plugin.isGlobalAreaProtection()) {
                // No areas affected and no global protection, allow all blocks to explode
                return;
            }
            
            // Performance optimization: Check if entire explosion should be cancelled
            if (shouldCancelEntireExplosion(potentialAreas, bounds, explosionPermission)) {
                // Cancel the entire explosion if any area disallows it
                // This is more efficient than processing each block individually
                event.getBlockList().clear();
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Explosion cancelled entirely due to protection rules");
                }
                return;
            }
            
            // Process the explosion on a block-by-block basis
            // This is more expensive but necessary for mixed permissions scenarios
            List<Block> blocksToRemove = processExplosionBlocks(
                worldName, 
                affectedBlocks, 
                potentialAreas, 
                explosionPermission
            );
            
            // Remove protected blocks from the explosion
            event.getBlockList().removeAll(blocksToRemove);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Processed explosion: " + affectedBlocks.size() + 
                             " total blocks, " + blocksToRemove.size() + " protected blocks removed");
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "explosion_check");
        }
    }
    
    /**
     * Determines the correct permission to check based on explosion type
     */
    private String getExplosionPermission(Entity entity) {
        if (entity == null) {
            return "gui.permissions.toggles.allowTNT"; // Default for unknown sources
        }
        
        // Use proper permission based on explosion source
        String explosionType = entity.getClass().getSimpleName();
        switch (explosionType) {
            case "EntityPrimedTNT":
                return "gui.permissions.toggles.allowTNT";
            case "EntityCreeper":
                return "gui.permissions.toggles.allowCreeper";
            case "EntityEnderCrystal":
                return "gui.permissions.toggles.allowCrystalExplosion";
            default:
                return "gui.permissions.toggles.allowTNT"; // Default fallback
        }
    }
    
    /**
     * Calculate bounds of an explosion for efficient area lookups
     */
    private ExplosionBounds calculateExplosionBounds(List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) return null;
        
        // Initialize with the first block's coordinates
        Block first = blocks.get(0);
        int minX = first.getFloorX();
        int minY = first.getFloorY();
        int minZ = first.getFloorZ();
        int maxX = minX;
        int maxY = minY;
        int maxZ = minZ;
        
        // Find the min/max bounds of all affected blocks
        for (Block block : blocks) {
            int x = block.getFloorX();
            int y = block.getFloorY();
            int z = block.getFloorZ();
            
            if (x < minX) minX = x;
            else if (x > maxX) maxX = x;
            
            if (y < minY) minY = y;
            else if (y > maxY) maxY = y;
            
            if (z < minZ) minZ = z;
            else if (z > maxZ) maxZ = z;
        }
        
        return new ExplosionBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }
    
    /**
     * Get all areas that intersect with the explosion bounds
     */
    private List<Area> getAreasInExplosionRange(String worldName, ExplosionBounds bounds) {
        // Fast cache key based on explosion bounds
        String cacheKey = worldName + ":" + bounds.toString() + ":explosion_areas";
        
        // Use an object cache specifically for area lists
        // This is separate from the boolean protectionCache
        Object cached = explosionAreaCache.getIfPresent(cacheKey);
        if (cached instanceof List) {
            @SuppressWarnings("unchecked")
            List<Area> result = (List<Area>)cached;
            return result;
        }
        
        // Get global area first (most efficient path)
        // Use a more compatible approach to find global areas
        List<Area> globalAreas = findGlobalAreasForWorld(worldName);
        if (!globalAreas.isEmpty()) {
            // Cache and return global areas
            explosionAreaCache.put(cacheKey, globalAreas);
            return globalAreas;
        }
        
        // Get all areas that might be affected by this explosion
        Set<Area> affectedAreas = new HashSet<>();
        
        // Check areas at the corners and center of the explosion bounds
        // This is much more efficient than checking every block
        checkAreaAtPoint(affectedAreas, worldName, bounds.minX, bounds.minY, bounds.minZ);
        checkAreaAtPoint(affectedAreas, worldName, bounds.maxX, bounds.minY, bounds.minZ);
        checkAreaAtPoint(affectedAreas, worldName, bounds.minX, bounds.maxY, bounds.minZ);
        checkAreaAtPoint(affectedAreas, worldName, bounds.maxX, bounds.maxY, bounds.minZ);
        checkAreaAtPoint(affectedAreas, worldName, bounds.minX, bounds.minY, bounds.maxZ);
        checkAreaAtPoint(affectedAreas, worldName, bounds.maxX, bounds.minY, bounds.maxZ);
        checkAreaAtPoint(affectedAreas, worldName, bounds.minX, bounds.maxY, bounds.maxZ);
        checkAreaAtPoint(affectedAreas, worldName, bounds.maxX, bounds.maxY, bounds.maxZ);
        
        // Also check center point
        int centerX = (bounds.minX + bounds.maxX) / 2;
        int centerY = (bounds.minY + bounds.maxY) / 2;
        int centerZ = (bounds.minZ + bounds.maxZ) / 2;
        checkAreaAtPoint(affectedAreas, worldName, centerX, centerY, centerZ);
        
        // Convert to list and sort by priority
        List<Area> result = new ArrayList<>(affectedAreas);
        result.sort((a1, a2) -> Integer.compare(a2.getPriority(), a1.getPriority()));
        
        // Cache the result
        explosionAreaCache.put(cacheKey, result);
        return result;
    }
    
    /**
     * Gets global areas for a world in a version-compatible way
     */
    private List<Area> findGlobalAreasForWorld(String worldName) {
        List<Area> result = new ArrayList<>();
        
        // Find areas that cover the entire world
        for (Area area : plugin.getAreas()) {
            // Check if this area is in the target world
            if (!area.getWorld().equals(worldName)) {
                continue;
            }
            
            // Check if area bounds are extremely large (likely global)
            AreaDTO.Bounds bounds = area.getBounds();
            if (bounds == null) continue;
            
            boolean isLikelyGlobal = (bounds.xMax() - bounds.xMin() > 10000) && 
                                    (bounds.zMax() - bounds.zMin() > 10000);
            
            if (isLikelyGlobal) {
                result.add(area);
            }
        }
        
        // Sort by priority (highest first)
        if (result.size() > 1) {
            result.sort((a1, a2) -> Integer.compare(a2.getPriority(), a1.getPriority()));
        }
        
        return result;
    }
    
    /**
     * Helper to check areas at a specific point and add to the set
     */
    private void checkAreaAtPoint(Set<Area> areas, String worldName, int x, int y, int z) {
        List<Area> pointAreas = plugin.getAreaManager().getAreasAtLocation(worldName, x, y, z);
        if (pointAreas != null && !pointAreas.isEmpty()) {
            areas.addAll(pointAreas);
        }
    }
    
    /**
     * Optimization to check if the entire explosion should be cancelled
     * This is much faster than checking every block individually
     */
    private boolean shouldCancelEntireExplosion(List<Area> areas, ExplosionBounds bounds, String explosionPermission) {
        // Fast path: if no areas, check global protection
        if (areas.isEmpty()) {
            return plugin.isGlobalAreaProtection();
        }
        
        // Cache key for this specific explosion protection check
        String cacheKey = bounds.toString() + ":" + explosionPermission + ":cancel_all";
        Boolean cachedResult = protectionCache.getIfPresent(cacheKey);
        if (cachedResult != null) {
            return cachedResult;
        }
        
        // Get highest priority area (already sorted)
        Area highestPriorityArea = areas.get(0);
        
        // Check if the highest priority area allows this explosion type
        boolean shouldCancel = false;
        
        try {
            // Get toggle state directly from area
            shouldCancel = !highestPriorityArea.getToggleState(explosionPermission);
        } catch (Exception e) {
            // Fallback to permission checker
            shouldCancel = !permissionChecker.isAllowed(null, highestPriorityArea, explosionPermission);
        }
        
        // Cache the result
        protectionCache.put(cacheKey, shouldCancel);
        return shouldCancel;
    }
    
    /**
     * Process explosion blocks in an efficient manner
     * Returns blocks that should be protected (removed from explosion)
     */
    private List<Block> processExplosionBlocks(String worldName, List<Block> blocks, List<Area> areas, String explosionPermission) {
        // If no areas to check, return empty list (all blocks explode)
        if (areas.isEmpty() && !plugin.isGlobalAreaProtection()) {
            return Collections.emptyList();
        }
        
        // For global protection with no areas
        if (areas.isEmpty()) {
            // If global protection is enabled, protect all blocks
            return new ArrayList<>(blocks);
        }
        
        // For single area case (most common), optimize processing
        if (areas.size() == 1) {
            Area area = areas.get(0);
            // Check permission once for the entire area
            if (!permissionChecker.isAllowed(null, area, explosionPermission)) {
                // If protected, all blocks within this area should be removed from explosion
                List<Block> protectedBlocks = new ArrayList<>();
                for (Block block : blocks) {
                    if (area.isInside(worldName, block.x, block.y, block.z)) {
                        protectedBlocks.add(block);
                    }
                }
                return protectedBlocks;
            } else {
                // If not protected, no blocks need to be removed
                return Collections.emptyList();
            }
        }
        
        // For multiple overlapping areas, check each block
        // Use a per-area permission cache to avoid redundant checks
        Map<String, Boolean> areaPermissionCache = new HashMap<>();
        
        // Process blocks in batches for better performance
        List<Block> protectedBlocks = new ArrayList<>();
        List<Block> currentBatch = new ArrayList<>();
        
        for (Block block : blocks) {
            currentBatch.add(block);
            
            if (currentBatch.size() >= BATCH_SIZE) {
                processBlockBatch(worldName, currentBatch, protectedBlocks, areas, areaPermissionCache, explosionPermission);
                currentBatch.clear();
            }
        }
        
        // Process any remaining blocks
        if (!currentBatch.isEmpty()) {
            processBlockBatch(worldName, currentBatch, protectedBlocks, areas, areaPermissionCache, explosionPermission);
        }
        
        return protectedBlocks;
    }
    
    /**
     * Process a batch of blocks efficiently
     */
    private void processBlockBatch(String worldName, List<Block> batch, List<Block> protectedBlocks, 
                                  List<Area> areas, Map<String, Boolean> areaPermissionCache, String explosionPermission) {
        for (Block block : batch) {
            Position pos = Position.fromObject(block, block.level);
            
            // Check if block is in any protected area
            boolean isProtected = false;
            
            for (Area area : areas) {
                if (!area.isInside(worldName, block.x, block.y, block.z)) {
                    continue;
                }
                
                // Check if we've already determined protection for this area
                String cacheKey = area.getName() + ":" + explosionPermission;
                Boolean areaProtected = areaPermissionCache.get(cacheKey);
                
                if (areaProtected == null) {
                    // Determine if this area protects against this explosion type
                    areaProtected = !permissionChecker.isAllowed(null, area, explosionPermission);
                    areaPermissionCache.put(cacheKey, areaProtected);
                }
                
                if (areaProtected) {
                    isProtected = true;
                    break;
                }
            }
            
            if (isProtected) {
                protectedBlocks.add(block);
            }
        }
    }
    
    /**
     * Simple class to represent explosion bounds for optimized processing
     */
    private static class ExplosionBounds {
        final int minX, minY, minZ, maxX, maxY, maxZ;
        
        ExplosionBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
        
        @Override
        public String toString() {
            return minX + "," + minY + "," + minZ + ":" + maxX + "," + maxY + "," + maxZ;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (handleProtection(event.getBlock(), null, "allowFireSpread")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLiquidFlow(LiquidFlowEvent event) {
        if (handleProtection(event.getBlock(), null, "allowLiquid")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getPlayer();
            String playerName = player.getName();

            // Only check every CHECK_INTERVAL ticks
            int currentTick = player.getServer().getTick();
            int lastCheck = lastCheckTimes.getOrDefault(playerName, 0);
            if (currentTick - lastCheck < CHECK_INTERVAL) {
                return;
            }
            lastCheckTimes.put(playerName, currentTick);

            // Skip if chunk not loaded
            if (!player.getLevel().isChunkLoaded(player.getChunkX(), player.getChunkZ())) {
                return;
            }

            Position to = event.getTo();

            // Get highest priority area at new position
            List<Area> areasAtNewPos = plugin.getAreaManager().getAreasAtLocation(
                to.getLevel().getName(),
                to.getX(),
                to.getY(),
                to.getZ()
            );

            String currentAreaName = null;
            if (!areasAtNewPos.isEmpty()) {
                Area highestPriorityArea = areasAtNewPos.get(0);
                currentAreaName = highestPriorityArea.getName();
            }

            String previousAreaName = playerAreaCache.get(playerName);

            // Handle area transitions
            if (currentAreaName == null && previousAreaName != null) {
                // Player left an area
                handleAreaLeave(player, previousAreaName);
                playerAreaCache.remove(playerName);
            } else if (currentAreaName != null && !currentAreaName.equals(previousAreaName)) {
                // Player entered a new area or switched between areas
                if (previousAreaName != null) {
                    handleAreaLeave(player, previousAreaName);
                }
                handleAreaEnter(player, currentAreaName);
                playerAreaCache.put(playerName, currentAreaName);
            }

        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "area_entry_check");
        }
    }

    private void handleAreaEnter(Player player, String areaName) {
        try {
            Area area = plugin.getArea(areaName);
            if (area == null || (area.toDTO() != null && !area.toDTO().showTitle())) return;

            // Default title values in case configuration is missing
            String title = "§aEntering " + areaName;
            String subtitle = "§7Welcome!";
            int fadeIn = 20;
            int stay = 40;
            int fadeOut = 20;

            try {
                // Try to get area-specific title config first
                Map<String, Object> titleConfig = null;
                if (plugin.getConfigManager().getSection("areaTitles." + areaName + ".enter") != null) {
                    titleConfig = plugin.getConfigManager().getSection("areaTitles." + areaName + ".enter").getAllMap();
                } else {
                    // Fall back to default title config
                    titleConfig = plugin.getConfigManager().getTitleConfig("enter");
                }

                // Extract values with null checks
                if (titleConfig != null) {
                    // Get main title with fallback
                    Object mainTitle = titleConfig.get("main");
                    if (mainTitle != null) {
                        title = mainTitle.toString().replace("{area}", areaName);
                    }
                    
                    // Get subtitle with fallback
                    Object subTitle = titleConfig.get("subtitle");
                    if (subTitle != null) {
                        subtitle = subTitle.toString().replace("{area}", areaName);
                    }
                    
                    // Get timing values with fallbacks
                    fadeIn = titleConfig.containsKey("fadeIn") ? Integer.parseInt(titleConfig.get("fadeIn").toString()) : fadeIn;
                    stay = titleConfig.containsKey("stay") ? Integer.parseInt(titleConfig.get("stay").toString()) : stay;
                    fadeOut = titleConfig.containsKey("fadeOut") ? Integer.parseInt(titleConfig.get("fadeOut").toString()) : fadeOut;
                }
            } catch (Exception e) {
                // Log the error, but continue with default values
                if (plugin.isDebugMode()) {
                    plugin.debug("Error loading title config for area " + areaName + ": " + e.getMessage());
                }
            }

            // Send the title with either configured or default values
            player.sendTitle(
                title,
                subtitle,
                fadeIn,
                stay,
                fadeOut
            );
        } catch (Exception e) {
            // Catch any remaining exceptions to prevent crashes
            if (plugin.isDebugMode()) {
                plugin.debug("Failed to show enter title for area " + areaName + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void handleAreaLeave(Player player, String areaName) {
        try {
            Area area = plugin.getArea(areaName);
            if (area == null || (area.toDTO() != null && !area.toDTO().showTitle())) return;

            // Default title values in case configuration is missing
            String title = "§eLeaving " + areaName;
            String subtitle = "§7Goodbye!";
            int fadeIn = 20;
            int stay = 40;
            int fadeOut = 20;

            try {
                // Try to get area-specific title config first
                Map<String, Object> titleConfig = null;
                if (plugin.getConfigManager().getSection("areaTitles." + areaName + ".leave") != null) {
                    titleConfig = plugin.getConfigManager().getSection("areaTitles." + areaName + ".leave").getAllMap();
                } else {
                    // Fall back to default title config
                    titleConfig = plugin.getConfigManager().getTitleConfig("leave");
                }

                // Extract values with null checks
                if (titleConfig != null) {
                    // Get main title with fallback
                    Object mainTitle = titleConfig.get("main");
                    if (mainTitle != null) {
                        title = mainTitle.toString().replace("{area}", areaName);
                    }
                    
                    // Get subtitle with fallback
                    Object subTitle = titleConfig.get("subtitle");
                    if (subTitle != null) {
                        subtitle = subTitle.toString().replace("{area}", areaName);
                    }
                    
                    // Get timing values with fallbacks
                    fadeIn = titleConfig.containsKey("fadeIn") ? Integer.parseInt(titleConfig.get("fadeIn").toString()) : fadeIn;
                    stay = titleConfig.containsKey("stay") ? Integer.parseInt(titleConfig.get("stay").toString()) : stay;
                    fadeOut = titleConfig.containsKey("fadeOut") ? Integer.parseInt(titleConfig.get("fadeOut").toString()) : fadeOut;
                }
            } catch (Exception e) {
                // Log the error, but continue with default values
                if (plugin.isDebugMode()) {
                    plugin.debug("Error loading title config for area " + areaName + ": " + e.getMessage());
                }
            }

            // Send the title with either configured or default values
            player.sendTitle(
                title,
                subtitle,
                fadeIn,
                stay,
                fadeOut
            );
        } catch (Exception e) {
            // Catch any remaining exceptions to prevent crashes
            if (plugin.isDebugMode()) {
                plugin.debug("Failed to show leave title for area " + areaName + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        // Always allow personal inventory access
        if (event.getInventory() == player.getInventory()) {
            return;
        }

        // Get container block if it exists
        Block containerBlock = null;
        if (event.getInventory().getHolder() instanceof Position position) {
            containerBlock = player.getLevel().getBlock(position);
        }

        // Skip if no container block (not a world container)
        if (containerBlock == null) {
            return;
        }

        // Check container permission
        Position pos = new Position(containerBlock.x, containerBlock.y, containerBlock.z, containerBlock.level);
        if (handleProtection(pos, player, "container")) {
            event.setCancelled(true);
            sendProtectionMessage(player, "messages.protection.container");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(ItemFrameDropItemEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (handleProtection(event.getItemFrame().getLocation(), null, "gui.permissions.toggles.allowHangingBreak")) {
                event.setCancelled(true);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "hanging_break_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Entity attacker = event.getAttacker();
            if (attacker instanceof Player player) {
                if (handleProtection(event.getVehicle().getPosition(), player, "allowVehicleDamage")) {
                    event.setCancelled(true);
                    sendProtectionMessage(player, "messages.protection.vehicleDamage", new HashMap<>());
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "vehicle_damage_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Additional fire protection
            if (handleProtection(event.getBlock(), null, "allowFireStart")) {
                event.setCancelled(true);
                // Fire messages only shown for player-caused ignition
                if (event.getEntity() instanceof Player player) {
                    sendProtectionMessage(player, "messages.protection.fire", new HashMap<>());
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "block_ignite_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Block spread is always natural, no player check needed
            if (handleProtection(event.getBlock(), null, "allowBlockSpread")) {
                event.setCancelled(true);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "block_spread_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonChangeEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Pistons can work without player involvement
            if (handleProtection(event.getBlock(), null, "allowPistons")) {
                event.setCancelled(true);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "piston_extend_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonChangeEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (handleProtection(event.getBlock(), null, "allowPistons")) {
                event.setCancelled(true);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "piston_retract_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSoilDry(BlockFadeEvent event) {
        if (event.getBlock().getId() == BlockID.FARMLAND && 
            handleProtection(event.getBlock(), null, "gui.permissions.toggles.allowBlockSpread")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCropGrow(BlockGrowEvent event) {
        if (handleProtection(event.getBlock(), null, "gui.permissions.toggles.allowBlockSpread")) {
            event.setCancelled(true);
        }
    }

    /**
     * Handles gravity blocks (sand, gravel, concrete powder) falling events
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFall(BlockFallEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Block block = event.getBlock();
            
            // Skip if not a gravity block
            if (!isGravityBlock(block)) {
                return;
            }
            
            if (handleProtection(block, null, "gui.permissions.toggles.allowBlockGravity")) {
                event.setCancelled(true);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Prevented gravity block from falling at " + 
                        block.getFloorX() + ", " + block.getFloorY() + ", " + block.getFloorZ());
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "block_fall_check");
        }
    }
    
    /**
     * Helper method to determine if a block is affected by gravity
     */
    private boolean isGravityBlock(Block block) {
        if (block == null) return false;
        
        switch (block.getId()) {
            case Block.SAND:
            case Block.GRAVEL:
            case Block.CONCRETE_POWDER:
            case Block.ANVIL:
            case Block.DRAGON_EGG:
            case Block.SCAFFOLDING:
                return true;
            default:
                return false;
        }
    }

    /**
     * More comprehensive check for taming attempts based on entity type and held item
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityTame(PlayerInteractEntityEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Entity entity = event.getEntity();
            Player player = event.getPlayer();
            
            // Check if this is a taming attempt by validating entity type and item
            if (isTamingAttempt(entity, player)) {
                if (plugin.isDebugMode()) {
                    plugin.debug("Detected taming attempt: " + 
                        player.getName() + " trying to tame " + 
                        entity.getClass().getSimpleName() + " with " + 
                        player.getInventory().getItemInHand().getName());
                }
                
                if (handleProtection(entity, player, "allowTaming")) {
                    event.setCancelled(true);
                    sendProtectionMessage(player, "messages.protection.taming");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "entity_tame_check");
        }
    }
    
    /**
     * Determines if a player interaction with an entity is a taming attempt
     * based on entity type and the item being used
     * 
     * @param entity The entity being interacted with
     * @param player The player attempting to tame
     * @return true if this is a valid taming attempt, false otherwise
     */
    private boolean isTamingAttempt(Entity entity, Player player) {
        if (entity == null || player == null) return false;
        
        // Get the item the player is holding
        Item heldItem = player.getInventory().getItemInHand();
        if (heldItem == null) return false;
        
        String entityType = entity.getClass().getSimpleName();
        int itemId = heldItem.getId();
        
        // Wolf - Bone
        if (entityType.equals("EntityWolf")) {
            return itemId == Item.BONE;
        }
        
        // Cat/Ocelot - Raw Cod or Raw Salmon
        if (entityType.equals("EntityCat") || entityType.equals("EntityOcelot")) {
            return itemId == Item.RAW_FISH || itemId == 460; // 460 = RAW_SALMON
        }
        
        // Parrot - Seeds (Beetroot, Melon, Pumpkin, Wheat)
        if (entityType.equals("EntityParrot")) {
            return itemId == Item.BEETROOT_SEEDS || 
                   itemId == Item.MELON_SEEDS ||
                   itemId == Item.PUMPKIN_SEEDS ||
                   itemId == Item.SEEDS;
        }
        
        // Horse, Donkey, Mule - Various food items
        if (entityType.equals("EntityHorse") || 
            entityType.equals("EntityDonkey") || 
            entityType.equals("EntityMule")) {
            return itemId == Item.SUGAR || 
                   itemId == Item.WHEAT ||
                   itemId == Item.APPLE ||
                   itemId == Item.GOLDEN_APPLE ||
                   itemId == Item.GOLDEN_CARROT;
        }
        
        // Llama - No specific item, but check if player doesn't have saddle
        if (entityType.equals("EntityLlama") || entityType.equals("EntityTraderLlama")) {
            return !player.getInventory().contains(new Item(Item.SADDLE));
        }
        
        // Camel - Cactus
        if (entityType.equals("EntityCamel")) {
            return itemId == Item.CACTUS;
        }
        
        // Fox - Sweet Berries or Glow Berries
        if (entityType.equals("EntityFox")) {
            return itemId == 477 || itemId == 476; // 477 = GLOW_BERRIES, 476 = SWEET_BERRIES
        }
        
        // Axolotl - Water Bucket or Bucket of Tropical Fish
        if (entityType.equals("EntityAxolotl")) {
            return itemId == Item.BUCKET || itemId == 325; // 325 = Water bucket
        }
        
        // Allay - Any item
        if (entityType.equals("EntityAllay")) {
            return true; // Any item can be used
        }
        
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        Position dropPos = player.getPosition();

        if (handleProtection(dropPos, player, "itemDrop")) {
            event.setCancelled(true);
            sendProtectionMessage(player, "messages.protection.itemDrop");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemPickup(InventoryPickupItemEvent event) {
        if (!(event.getInventory().getHolder() instanceof Player player)) {
            return;
        }

        Position itemPos = event.getItem().getPosition();
        if (handleProtection(itemPos, player, "itemPickup")) {
            event.setCancelled(true);
            sendProtectionMessage(player, "messages.protection.itemPickup");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehiclePlace(VehicleCreateEvent event) {
        if (handleProtection(event.getVehicle(), null, "gui.permissions.toggles.allowVehiclePlace")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleEnter(EntityEnterVehicleEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Entity entity = event.getEntity();
            // Only check player vehicle entry
            if (entity instanceof Player player) {
                Position vehiclePos = event.getVehicle().getPosition();
                if (handleProtection(vehiclePos, player, "allowVehicleEnter")) {
                    event.setCancelled(true);
                    sendProtectionMessage(player, "messages.protection.vehicleEnter");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "vehicle_enter_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalUse(PlayerTeleportEvent event) {
        if (handleProtection(event.getPlayer(), event.getPlayer(), "gui.permissions.toggles.allowEnderPearl")) {
            event.setCancelled(true);
            sendProtectionMessage(event.getPlayer(), "messages.protection.enderPearl");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPermissionChange(PermissionChangeEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Invalidate any cached permissions for this area/group
            String cacheKey = event.getArea().getName() + ":" + 
                            event.getGroup() + ":" + 
                            event.getPermission();
            permissionCache.invalidate(cacheKey);
            
            // Log permission change if debug is enabled
            if (plugin.isDebugMode()) {
                plugin.getLogger().debug(String.format(
                    "Permission changed in area %s for group %s: %s %s -> %s",
                    event.getArea().getName(),
                    event.getGroup(),
                    event.getPermission(),
                    event.getOldValue(),
                    event.getNewValue()
                ));
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "permission_change_handle");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAreaPermissionUpdate(AreaPermissionUpdateEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Area area = event.getArea();
            
            // Clear all permission types
            try {
                area.clearAllPermissions();
            } catch (DatabaseException e) {
                plugin.getLogger().error("Failed to clear permissions for area " + area.getName(), e);
                return;
            }
            
            // Invalidate all cached permissions for this area
            String areaPrefix = area.getName() + ":";
            permissionCache.asMap().keySet().removeIf(key -> key.startsWith(areaPrefix));
            
            // Clear player permission cache for this area
            playerPermissionCache.clear();
            
            // Save the area to persist the changes
            plugin.updateArea(area);
            
            // Send warning message to player if available
            if (event.getPlayer() != null) {
                event.getPlayer().sendMessage(plugin.getLanguageManager().get(
                    "messages.form.areaPermissionResetWarning",
                    Map.of("area", area.getName())
                ));
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug(String.format(
                    "Area permission update in area %s for category %s - cleared all permissions", 
                    area.getName(),
                    event.getCategory().name()
                ));
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "area_permission_update_handle");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) 
    public void onLuckPermsGroupChange(LuckPermsGroupChangeEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Invalidate cached permissions for this area/group
            String cachePrefix = event.getArea().getName() + ":" + event.getGroup();
            permissionCache.asMap().keySet().removeIf(key -> key.startsWith(cachePrefix));
            
            if (plugin.isDebugMode()) {
                plugin.getLogger().debug(String.format(
                    "LuckPerms group permissions changed in area %s for group %s",
                    event.getArea().getName(),
                    event.getGroup() 
                ));
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "luckperms_group_change_handle"); 
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAreaDeleted(AreaDeletedEvent event) {
        Area area = event.getArea();
        if (area == null) {
            return;
        }

        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            int cacheEntriesRemoved = 0;

            // Use AtomicInteger for thread-safe counter that can be modified in lambda
            AtomicInteger protectionCacheRemoved = new AtomicInteger(0);
            
            // Clear protection cache for the area's bounds
            protectionCache.asMap().entrySet().removeIf(entry -> {
                String[] parts = entry.getKey().split(":");
                if (parts.length < 4) return false;
                String world = parts[0];
                double x = Double.parseDouble(parts[1]);
                double y = Double.parseDouble(parts[2]);
                double z = Double.parseDouble(parts[3]);
                boolean shouldRemove = world.equals(area.toDTO().world()) &&
                    x >= area.toDTO().bounds().xMin() && x <= area.toDTO().bounds().xMax() &&
                    y >= area.toDTO().bounds().yMin() && y <= area.toDTO().bounds().yMax() &&
                    z >= area.toDTO().bounds().zMin() && z <= area.toDTO().bounds().zMax();
                if (shouldRemove) {
                    protectionCacheRemoved.incrementAndGet();
                }
                return shouldRemove;
            });

            // Clear permission cache for this area
            String areaPrefix = area.getName() + ":";
            AtomicInteger permCacheRemoved = new AtomicInteger(0);
            permissionCache.asMap().keySet().removeIf(key -> {
                boolean shouldRemove = key.startsWith(areaPrefix);
                if (shouldRemove) {
                    permCacheRemoved.incrementAndGet();
                }
                return shouldRemove;
            });

            // Clear temporary permissions for this area
            AtomicInteger tempPermsRemoved = new AtomicInteger(0);
            for (Set<String> permSet : temporaryPermissions.values()) {
                int sizeBefore = permSet.size();
                permSet.removeIf(perm -> perm.endsWith("." + area.getName()));
                tempPermsRemoved.addAndGet(sizeBefore - permSet.size());
            }

            // Remove from player area cache
            AtomicInteger areaNameRemoved = new AtomicInteger(0);
            playerAreaCache.entrySet().removeIf(entry -> {
                boolean shouldRemove = entry.getValue().equals(area.getName());
                if (shouldRemove) {
                    areaNameRemoved.incrementAndGet();
                }
                return shouldRemove;
            });

            if (plugin.isDebugMode()) {
                plugin.debug(String.format(
                    "Area deletion cache cleanup for '%s':\n" +
                    "- Protection cache entries removed: %d\n" +
                    "- Permission cache entries removed: %d\n" +
                    "- Temporary permissions removed: %d\n" +
                    "- Area name cache entries removed: %d",
                    area.getName(),
                    protectionCacheRemoved.get(),
                    permCacheRemoved.get(),
                    tempPermsRemoved.get(),
                    areaNameRemoved.get()
                ));
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "area_deletion_cleanup");
        }
    }

    /**
     * More specific breeding check that validates the entity and breeding food
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getReason() == CreatureSpawnEvent.SpawnReason.BREEDING) {
            Position pos = event.getPosition();
            
            if (plugin.isDebugMode()) {
                plugin.debug("Breeding event detected at " + 
                    pos.getFloorX() + ", " + pos.getFloorY() + ", " + pos.getFloorZ());
            }
            
            if (handleProtection(pos, null, "allowBreeding")) {
                event.setCancelled(true);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Breeding prevented by area protection");
                }
            }
        }
    }

    /**
     * Detect and prevent breeding attempts in protected areas
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreedingAttempt(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        Entity entity = event.getEntity();
        
        // Skip if no player or entity
        if (player == null || entity == null) return;
        
        // Check if this is a breeding attempt by validating entity and item
        if (isBreedingAttempt(entity, player)) {
            Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
            try {
                if (plugin.isDebugMode()) {
                    Item heldItem = player.getInventory().getItemInHand();
                    plugin.debug("Breeding attempt detected: " + 
                        player.getName() + " trying to breed " + 
                        entity.getClass().getSimpleName() + " with " + 
                        (heldItem != null ? heldItem.getName() : "unknown item"));
                }
                
                if (handleProtection(entity, player, "allowBreeding")) {
                    event.setCancelled(true);
                    sendProtectionMessage(player, "messages.protection.breeding");
                }
            } finally {
                plugin.getPerformanceMonitor().stopTimer(sample, "breeding_attempt_check");
            }
        }
    }

    /**
     * Determines if a player interaction with an entity is a breeding attempt
     * based on entity type and the item being used
     * 
     * @param entity The entity being interacted with
     * @param player The player attempting to breed
     * @return true if this is a valid breeding attempt, false otherwise
     */
    private boolean isBreedingAttempt(Entity entity, Player player) {
        if (entity == null || player == null) return false;
        
        // Get the item the player is holding
        Item heldItem = player.getInventory().getItemInHand();
        if (heldItem == null) return false;
        
        String entityType = entity.getClass().getSimpleName();
        int itemId = heldItem.getId();
        
        // Check breeding items by entity type
        switch (entityType) {
            case "EntityChicken":
                return itemId == Item.SEEDS || 
                       itemId == Item.BEETROOT_SEEDS || 
                       itemId == Item.MELON_SEEDS || 
                       itemId == Item.PUMPKIN_SEEDS;
                
            case "EntityCow":
            case "EntityMooshroom":
                return itemId == Item.WHEAT;
                
            case "EntityPig":
                return itemId == Item.CARROT || 
                       itemId == Item.POTATO || 
                       itemId == Item.BEETROOT;
                
            case "EntitySheep":
                return itemId == Item.WHEAT;
                
            case "EntityRabbit":
                return itemId == Item.CARROT || 
                       itemId == Item.GOLDEN_CARROT || 
                       itemId == 37; // 37 = DANDELION (yellow flower)
                
            case "EntityTurtle":
                return itemId == 361; // SEAGRASS
                
            case "EntityWolf":
                // Wolf must be tamed first, then fed certain meats
                return (itemId == Item.RAW_BEEF || 
                        itemId == Item.STEAK || 
                        itemId == Item.RAW_CHICKEN || 
                        itemId == Item.COOKED_CHICKEN || 
                        itemId == Item.ROTTEN_FLESH || 
                        itemId == 423 || // 423 = Raw Mutton
                        itemId == 424 || // 424 = Cooked Mutton
                        itemId == 411 || // 411 = Raw Rabbit
                        itemId == 412);  // 412 = Cooked Rabbit
                
            case "EntityCat":
            case "EntityOcelot":
                return itemId == Item.RAW_FISH;
                
            case "EntityPanda":
                return itemId == 352; // 352 = BAMBOO
                
            case "EntityFox":
                return itemId == 476; // Sweet berries
                
            case "EntityBee":
                // Flowers - different IDs in Nukkit
                return (itemId == 37 ||   // 37 = Dandelion (yellow flower)
                        itemId == 38 ||   // 38 = Poppy (red flower)
                        itemId >= 38 && itemId <= 42); // Other flowers
                
            case "EntityAxolotl":
                return itemId == Item.BUCKET; // Bucket of tropical fish
                
            case "EntityGoat":
                return itemId == Item.WHEAT;
                
            case "EntityLlama":
            case "EntityTraderLlama":
                return itemId == Item.HAY_BLOCK;
                
            case "EntityHorse":
            case "EntityDonkey":
            case "EntityMule":
                return itemId == Item.GOLDEN_APPLE || 
                       itemId == Item.GOLDEN_CARROT;
                
            case "EntityStrider":
                return itemId == 470; // Warped Fungus
                
            case "EntityHoglin":
                return itemId == 473; // Crimson Fungus
                
            case "EntityFrog":
                return itemId == 349; // Slimeball
                
            case "EntityCamel":
                return itemId == Item.CACTUS;
        }
        
        return false;
    }

    /**
     * Centralized method to check if an action should be allowed or denied based on area protection rules.
     * This is a performance-critical method as it's called for every protected action.
     *
     * @param pos The position where the action is taking place
     * @param player The player performing the action (can be null for environment actions)
     * @param permission The permission to check
     * @return true if the action should be denied, false if allowed
     */
    boolean handleProtection(Position pos, Player player, String permission) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Fast path: if player is bypassing, always allow
            if (player != null && plugin.isBypassing(player.getName())) {
                return false;
            }

            // Validate position to prevent crashes
            if (pos == null || pos.getLevel() == null) {
                return false;
            }

            // Skip processing if no players are nearby (optimization)
            if (!plugin.getAreaManager().shouldProcessEvent(pos, false)) {
                return false;
            }

            // Normalize permission once to avoid repeated string operations
            if (!permission.contains(".")) {
                // Simple permission names like "break", "build", etc. are special cases
                if (permission.equals("break")) {
                    permission = "gui.permissions.toggles.allowBlockBreak";
                } else if (permission.equals("build")) {
                    permission = "gui.permissions.toggles.allowBlockPlace";
                } else if (permission.equals("container")) {
                    permission = "gui.permissions.toggles.allowContainer";
                } else if (permission.equals("interact")) {
                    permission = "gui.permissions.toggles.allowInteract";
                } else if (permission.equals("itemDrop")) {
                    permission = "gui.permissions.toggles.allowItemDrop";
                } else if (permission.equals("itemPickup")) {
                    permission = "gui.permissions.toggles.allowItemPickup";
                } else {
                    // For other simple permissions, add the prefix
                    permission = "gui.permissions.toggles." + permission;
                }
            } else if (!permission.startsWith("gui.permissions.toggles.") && 
                       !permission.startsWith("adminarea.") && 
                       !permission.startsWith("group.")) {
                // Add prefix if it's missing and not a special permission type
                permission = "gui.permissions.toggles." + permission;
            }

            // Get all applicable areas for this position
            List<Area> areas = plugin.getAreaManager().getAreasAtLocation(
                pos.getLevel().getName(),
                pos.getX(),
                pos.getY(),
                pos.getZ()
            );

            // If no areas and global protection is disabled, allow action
            if (areas.isEmpty() && !plugin.isGlobalAreaProtection()) {
                return false;
            }

            // If no areas but global protection is enabled, check for global bypass
            if (areas.isEmpty()) {
                return !hasGlobalBypass(player);
            }

            // Get the highest priority area
            Area highestPriorityArea = areas.get(0);
            
            // Check if the action is allowed in this area
            return !permissionChecker.isAllowed(player, highestPriorityArea, permission);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "protection_check");
        }
    }

    private String buildCacheKey(Position pos, Player player, String permission) {
        StringBuilder key = new StringBuilder()
            .append(pos.getLevel().getName())
            .append(':')
            .append(pos.getFloorX())
            .append(':')
            .append(pos.getFloorY())
            .append(':')
            .append(pos.getFloorZ())
            .append(':')
            .append(permission);
        
        if (player != null) {
            key.append(':').append(player.getName());
        }
        
        return key.toString();
    }

    private boolean hasTemporaryPermission(String player, String permission, String area) {
        Set<String> perms = temporaryPermissions.get(player);
        return perms != null && perms.contains(permission + "." + area);
    }

    private Map<String, Boolean> getPlayerPermissionCache(String playerName) {
        return playerPermissionCache.computeIfAbsent(playerName, k -> new ConcurrentHashMap<>());
    }

    private String getPermissionCacheKey(String playerName, String permission, String areaName) {
        return playerName + ":" + permission + ":" + areaName;
    }

    private boolean isContainer(Block block) {
        return switch (block.getId()) {
            case Block.CHEST, 
                 Block.TRAPPED_CHEST,
                 Block.ENDER_CHEST,
                 Block.FURNACE,
                 Block.BURNING_FURNACE,
                 Block.BLAST_FURNACE,
                 Block.SMOKER,
                 Block.BARREL,
                 Block.DISPENSER,
                 Block.DROPPER,
                 Block.HOPPER_BLOCK,
                 Block.BREWING_STAND_BLOCK,
                 Block.SHULKER_BOX -> true;
            default -> false;
        };
    }

    void sendProtectionMessage(Player player, String message) {
        if (!plugin.isEnableMessages()) return;
        
        long now = System.currentTimeMillis();
        long lastWarning = lastWarningTime.getOrDefault(player.getName(), 0L);
        
        if (now - lastWarning >= WARNING_COOLDOWN) {
            // Get the area at player's location
            Area area = plugin.getHighestPriorityArea(
                player.getLevel().getName(),
                player.getX(),
                player.getY(),
                player.getZ()
            );
            
            // Add area name to placeholders if available
            HashMap<String, String> placeholders = new HashMap<>();
            if (area != null) {
                placeholders.put("area", area.getName());
            }
            
            // Use default message if none provided
            if (message == null) {
                message = plugin.getLanguageManager().get("messages.protectionDenied", placeholders);
            } else {
                // Replace placeholders in the provided message
                message = plugin.getLanguageManager().get(message, placeholders);
            }
            
            player.sendMessage(message);
            lastWarningTime.put(player.getName(), now);
        }
    }

    // Add this new overload for sendProtectionMessage
    private void sendProtectionMessage(Player player, String messageKey, Map<String, String> placeholders) {
        if (!plugin.isEnableMessages()) return;
        
        long now = System.currentTimeMillis();
        long lastWarning = lastWarningTime.getOrDefault(player.getName(), 0L);
        
        if (now - lastWarning >= WARNING_COOLDOWN) {
            // Get the area at player's location if not provided in placeholders
            if (!placeholders.containsKey("area")) {
                Area area = plugin.getHighestPriorityArea(
                    player.getLevel().getName(),
                    player.getX(),
                    player.getY(),
                    player.getZ()
                );
                
                if (area != null) {
                    placeholders.put("area", area.getName());
                } else {
                    // If no area is found, use a default value
                    placeholders.put("area", "this area");
                }
            }

            String message = plugin.getLanguageManager().get(messageKey, placeholders);
            
            if (message != null && !message.isEmpty()) {
                player.sendMessage(message);
                lastWarningTime.put(player.getName(), now);
                
                if (plugin.isDebugMode()) {
                    plugin.getLogger().debug("Sent protection message: " + message);
                }
            } else if (plugin.isDebugMode()) {
                plugin.getLogger().debug("Empty or null message for key: " + messageKey);
            }
        }
    }

    public void addTemporaryPermission(String player, String permission, String area, long duration) {
        String fullPermission = permission + "." + area;
        temporaryPermissions.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(fullPermission);
        
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> {
            Set<String> perms = temporaryPermissions.get(player);
            if (perms != null) {
                perms.remove(fullPermission);
                if (perms.isEmpty()) {
                    temporaryPermissions.remove(player);
                }
            }
        }, (int) (duration / 50)); // Convert milliseconds to ticks
    }

    public void cleanup() {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            long now = System.currentTimeMillis();
            
            // Cleanup temporary permissions
            Iterator<Map.Entry<String, Set<String>>> it = temporaryPermissions.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Set<String>> entry = it.next();
                entry.getValue().removeIf(perm -> {
                    String[] parts = perm.split(":");
                    return parts.length > 2 && Long.parseLong(parts[2]) < now;
                });
                if (entry.getValue().isEmpty()) {
                    it.remove();
                }
            }
            
            // Cleanup caches
            protectionCache.invalidateAll();
            permissionCache.invalidateAll();
            
            // Cleanup player permission cache
            playerPermissionCache.clear();
            
            // Cleanup warning times older than 1 hour
            Iterator<Map.Entry<String, Long>> warningIt = lastWarningTime.entrySet().iterator();
            while (warningIt.hasNext()) {
                Map.Entry<String, Long> entry = warningIt.next();
                if (now - entry.getValue() > TimeUnit.HOURS.toMillis(1)) {
                    warningIt.remove();
                }
            }
            
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "protection_cleanup");
        }
        playerAreaCache.clear();
        lastCheckTimes.clear();
    }

    public void cleanup(Player player) {
        playerAreaCache.remove(player.getName());
        lastCheckTimes.remove(player.getName());
    }

    private boolean hasGlobalBypass(Player player) {
        if (player == null) {
            return false;
        }
        return player.hasPermission("adminarea.bypass.global");
    }

    /**
     * Checks if an event should be cancelled based on area protection rules.
     * Only checks within CHUNK_CHECK_RADIUS chunks of any player.
     */
    protected boolean shouldCancel(Position pos, Player player, String permission) {
        for (Area area : plugin.getAreaManager().getNearbyAreas(pos)) {

            if (!area.getToggleState(permission)) {
                return true;
            }
            // If position is null or in a different world, don't cancel
            if (pos == null || pos.getLevel() == null) return false;

            // If player has bypass permission, don't cancel
            if (player != null && plugin.isBypassing(player.getName())) return false;

            // Check if position is within range of any player
            if (!isNearAnyPlayer(pos)) return false;

            // Get applicable areas and check permissions
            List<Area> areas = plugin.getAreaManager().getAreasAtLocation(
                pos.getLevel().getName(),
                pos.getX(),
                pos.getY(),
                pos.getZ()
            );

            // No areas at location, don't cancel
            if (areas.isEmpty()) return false;

            // Check permissions in highest priority area first
            Area highestPriorityArea = areas.get(0);
            return !highestPriorityArea.getToggleState(permission);
        }
        return false;
    }

    /**
     * Checks if a position is within CHUNK_CHECK_RADIUS chunks of any player
     */
    protected boolean isNearAnyPlayer(Position pos) {
        int posChunkX = pos.getChunkX();
        int posChunkZ = pos.getChunkZ();

        for (Player player : plugin.getServer().getOnlinePlayers().values()) {
            // Skip players in different worlds
            if (!player.getLevel().getName().equals(pos.getLevel().getName())) {
                continue;
            }

            int playerChunkX = player.getChunkX();
            int playerChunkZ = player.getChunkZ();

            // Calculate chunk distance
            int chunkDistance = Math.max(
                Math.abs(playerChunkX - posChunkX),
                Math.abs(playerChunkZ - posChunkZ)
            );

            // If within check radius of any player, return true
            if (chunkDistance <= CHUNK_CHECK_RADIUS) {
                return true;
            }
        }

        return false;
    }

    /**
     * Gets the area name to use in messages, handling null cases
     */
    protected String getAreaName(Area area) {
        return area != null ? area.getName() : "protected area";
    }

    /**
     * Sends a protection denied message to a player
     */
    protected void sendProtectionMessage(Player player, String action, Area area) {
        if (player != null && plugin.isEnableMessages()) {
            player.sendMessage(plugin.getLanguageManager().get(
                "messages.protection." + action,
                Map.of("area", getAreaName(area))
            ));
        }
    }

    private boolean isTameableEntity(Entity entity) {
        return entity instanceof EntityWolf || 
               entity instanceof EntityCat || 
               entity instanceof EntityParrot;
    }

    private boolean isVehicle(Entity entity) {
        return entity instanceof EntityVehicle;
    }

    /**
     * Handles player interaction with redstone components as a more effective 
     * way to protect redstone in areas where it's disabled.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRedstoneInteraction(PlayerInteractEvent event) {
        if (event.getAction() != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        
        Block block = event.getBlock();
        if (block == null) return;
        
        // Check if this is a redstone component
        boolean isRedstoneComponent = false;
        switch (block.getId()) {
            case Block.LEVER:
            case Block.STONE_BUTTON:
            case Block.WOODEN_BUTTON:
            case Block.WOODEN_PRESSURE_PLATE:
            case Block.STONE_PRESSURE_PLATE:
            case Block.REDSTONE_TORCH:
            case Block.DAYLIGHT_DETECTOR:
                isRedstoneComponent = true;
                break;
        }
        
        // If this is a redstone component, check protection
        if (isRedstoneComponent) {
            Position pos = new Position(block.x, block.y, block.z, block.level);
            if (handleProtection(pos, event.getPlayer(), "gui.permissions.toggles.allowRedstone")) {
                event.setCancelled(true);
                sendProtectionMessage(event.getPlayer(), "messages.protection.redstone");
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Blocked redstone interaction at " + pos.x + ", " + pos.y + ", " + pos.z);
                }
            }
        }
    }

    /**
     * Gets the global area for a world (if available)
     * This handles potential API differences across versions
     */
    private Area getGlobalAreaForWorld(String worldName) {
        try {
            // Try to use dedicated method if available
            java.lang.reflect.Method method = plugin.getAreaManager().getClass()
                .getMethod("getGlobalAreaForWorld", String.class);
            return (Area) method.invoke(plugin.getAreaManager(), worldName);
        } catch (Exception e) {
            // Fallback: check if any area in the world is global by checking bounds
            for (Area area : plugin.getAreas()) {
                if (!area.getWorld().equals(worldName)) {
                    continue;
                }
                
                // Check if bounds are extremely large (indicative of a global area)
                AreaDTO.Bounds bounds = area.getBounds();
                if (bounds != null && 
                    bounds.xMin() <= -29000000 && bounds.xMax() >= 29000000 &&
                    bounds.zMin() <= -29000000 && bounds.zMax() >= 29000000) {
                    return area;
                }
            }
            return null;
        }
    }
}
