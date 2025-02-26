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
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (event.getAction() == PlayerInteractEvent.Action.LEFT_CLICK_BLOCK) {
                Position pos = event.getBlock().getLocation();
                Player player = event.getPlayer();
                
                if (handleProtection(pos, player, "allowBlockBreak")) {
                    event.setCancelled(true);
                    sendProtectionMessage(player, "messages.protection.blockBreak");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "interact_block_check");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Position pos = event.getBlock().getLocation();
            Player player = event.getPlayer();
            
            // Debug both the toggle state and the final decision for this specific event
            if (plugin.isDebugMode()) {
                Area area = plugin.getAreaManager().getHighestPriorityAreaAtPosition(pos);
                if (area != null) {
                    boolean toggleState = area.getToggleState("gui.permissions.toggles.allowBlockBreak");
                    plugin.debug("BlockBreak event: toggle state for allowBlockBreak in " + 
                               area.getName() + " is " + toggleState);
                }
            }
            
            // Check protection - true means should block
            if (handleProtection(pos, player, "allowBlockBreak")) {
                event.setCancelled(true);
                sendProtectionMessage(player, "messages.protection.blockBreak");
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "block_break_check");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        try {
            Player player = event.getPlayer();
            
            // Skip if bypassing
            if (plugin.isBypassing(player.getName())) {
                return;
            }
            
            Position pos = event.getBlock().getLocation();
            
            if (plugin.isDebugMode()) {
                plugin.debug("BlockPlace event: " + player.getName() + " placing " + 
                           event.getBlock().getName() + " at " + 
                           pos.getFloorX() + "," + pos.getFloorY() + "," + pos.getFloorZ());
                           
                // Get the area for debugging
                Area area = plugin.getAreaManager().getHighestPriorityArea(
                    pos.getLevel().getName(),
                    pos.getX(),
                    pos.getY(),
                    pos.getZ()
                );
                
                if (area != null) {
                    // Check the direct toggle state
                    boolean toggleState = area.getToggleState("allowBlockPlace");
                    plugin.debug("BlockPlace event: toggle state for allowBlockPlace in " + area.getName() + " is " + toggleState);
                    
                    // Force clear caches if we're in debug mode and testing this issue
                    if (plugin.getConfigManager().getBoolean("aggressive_cache_clearing", false)) {
                        plugin.debug("AGGRESSIVE CACHE CLEARING for block place event");
                        permissionChecker.invalidateCache(area.getName());
                        area.clearCaches();
                    }
                    
                    // Use diagnostic method if in debug mode 
                    boolean shouldBlock = diagnoseBrokenPermission(pos, player, "allowBlockPlace");
                    event.setCancelled(shouldBlock);
                    
                    if (shouldBlock) {
                        sendProtectionMessage(player, "blockPlace", area);
                    }
                    return;
                }
            }
            
            // Normal protection handling
            if (handleProtection(pos, player, "allowBlockPlace")) {
                if (plugin.isDebugMode()) {
                    plugin.debug("BlockPlace cancelled due to protection");
                }
                event.setCancelled(true);
                sendProtectionMessage(player, "messages.protection.blockPlace");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error handling block place", e);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (event.getAction() == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
                Block block = event.getBlock();
                Player player = event.getPlayer();
                
                // Check container access first (more specific)
                if (isContainer(block)) {
                    Position pos = new Position(block.x, block.y, block.z, block.level);
                    if (handleProtection(pos, player, "allowContainer")) {
                        event.setCancelled(true);
                        sendProtectionMessage(player, "messages.protection.container");
                        return;
                    }
                }
                
                // Then check general interaction
                Position pos = new Position(block.x, block.y, block.z, block.level);
                if (handleProtection(pos, player, "allowInteract")) {
                    event.setCancelled(true);
                    sendProtectionMessage(player, "messages.protection.interact");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "player_interact_check");
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
            
            if (handleProtection(pos, null, "allowRedstone") && plugin.isDebugMode()) {
                plugin.debug("Cancelled redstone event at " + pos.toString());
                // Note: BlockRedstoneEvent is not cancellable in Nukkit
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
            
            if (handleProtection(pos, null, "allowRedstone")) {
                event.setCancelled(true);
                if (plugin.isDebugMode()) {
                    plugin.debug("Cancelled redstone update at " + pos.toString());
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
            
            if (plugin.isDebugMode()) {
                plugin.debug("Explosion check for " + explosionPermission + 
                           " in area " + highestPriorityArea.getName() + 
                           " returned toggle state: " + !shouldCancel +
                           " (should cancel: " + shouldCancel + ")");
            }
        } catch (Exception e) {
            // Fallback to permission checker if direct toggle access fails
            boolean allowed = permissionChecker.isAllowed(null, highestPriorityArea, explosionPermission);
            shouldCancel = !allowed; // Invert: true=allowed → false=cancel, false=denied → true=cancel
            
            if (plugin.isDebugMode()) {
                plugin.debug("Fallback explosion check for " + explosionPermission + 
                           " in area " + highestPriorityArea.getName() + 
                           " using permissionChecker returned: " + allowed +
                           " (should cancel: " + shouldCancel + ")");
            }
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
            // FIXED: Correct permission check logic
            boolean isAllowed = permissionChecker.isAllowed(null, area, explosionPermission);
            if (!isAllowed) {
                // If the area doesn't allow explosions, protect all blocks
                return new ArrayList<>(blocks);
            }
            // If allowed, no blocks need protection
            return new ArrayList<>();
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
                    // FIXED: Correct permission check logic
                    boolean isAllowed = permissionChecker.isAllowed(null, area, explosionPermission);
                    areaProtected = !isAllowed; // Invert because true means "allowed" but we need true to mean "protected"
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

            // Check more frequently for better accuracy at boundaries 
            // but still optimize performance
            int currentTick = player.getServer().getTick();
            int lastCheck = lastCheckTimes.getOrDefault(playerName, 0);
            
            // Use a smaller interval for more precise detection
            if (currentTick - lastCheck < 2) { // Reduced from 5 to 2
                return;
            }
            lastCheckTimes.put(playerName, currentTick);

            // Skip if chunk not loaded
            if (!player.getLevel().isChunkLoaded(player.getChunkX(), player.getChunkZ())) {
                return;
            }

            Position to = event.getTo();
            Position from = event.getFrom();
            
            // Calculate moved distance for edge case detection
            double movedDistance = from.distance(to);
            boolean isNearBoundary = movedDistance < 0.5; // Likely near boundary if small move
            
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
            
            // Enhanced boundary detection
            if (isNearBoundary && 
                ((previousAreaName != null && !previousAreaName.equals(currentAreaName)) ||
                 (previousAreaName == null && currentAreaName != null)) && 
                plugin.isDebugMode()) {
                
                plugin.debug("Area boundary transition detected:");
                plugin.debug("  Player: " + playerName);
                plugin.debug("  From: " + (previousAreaName != null ? previousAreaName : "outside") + 
                           " to: " + (currentAreaName != null ? currentAreaName : "outside"));
                plugin.debug("  Position: " + to.getX() + ", " + to.getY() + ", " + to.getZ());
                plugin.debug("  Movement Distance: " + movedDistance);
                
                // More thorough nearby position checking with smaller offset
                plugin.debug("  Double-checking nearby positions:");
                for (double dx = -0.05; dx <= 0.05; dx += 0.05) { // Smaller increments
                    for (double dz = -0.05; dz <= 0.05; dz += 0.05) { // Smaller increments
                        Position testPos = new Position(to.getX() + dx, to.getY(), to.getZ() + dz, to.getLevel());
                        List<Area> testAreas = plugin.getAreaManager().getAreasAtLocation(
                            testPos.getLevel().getName(),
                            testPos.getX(),
                            testPos.getY(),
                            testPos.getZ()
                        );
                        String testAreaName = !testAreas.isEmpty() ? testAreas.get(0).getName() : "outside";
                        plugin.debug("    Offset (" + dx + ", " + dz + "): " + testAreaName);
                    }
                }
            }

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
                    // Create placeholder map with player name
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("area", areaName);
                    placeholders.put("player", player.getName());
                    
                    // Get main title with fallback
                    Object mainTitle = titleConfig.get("main");
                    if (mainTitle != null) {
                        title = plugin.getLanguageManager().get(mainTitle.toString(), placeholders);
                        if (plugin.isDebugMode()) {
                            plugin.debug("Processed enter title: " + title + " with placeholders: " + placeholders);
                        }
                    }
                    
                    // Get subtitle with fallback
                    Object subTitle = titleConfig.get("subtitle");
                    if (subTitle != null) {
                        subtitle = plugin.getLanguageManager().get(subTitle.toString(), placeholders);
                        if (plugin.isDebugMode()) {
                            plugin.debug("Processed enter subtitle: " + subtitle + " with placeholders: " + placeholders);
                        }
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
                    // Create placeholder map with player name
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("area", areaName);
                    placeholders.put("player", player.getName());
                    
                    // Get main title with fallback
                    Object mainTitle = titleConfig.get("main");
                    if (mainTitle != null) {
                        title = plugin.getLanguageManager().get(mainTitle.toString(), placeholders);
                        if (plugin.isDebugMode()) {
                            plugin.debug("Processed leave title: " + title + " with placeholders: " + placeholders);
                        }
                    }
                    
                    // Get subtitle with fallback
                    Object subTitle = titleConfig.get("subtitle");
                    if (subTitle != null) {
                        subtitle = plugin.getLanguageManager().get(subTitle.toString(), placeholders);
                        if (plugin.isDebugMode()) {
                            plugin.debug("Processed leave subtitle: " + subtitle + " with placeholders: " + placeholders);
                        }
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
            
            Position pos = new Position(block.x, block.y, block.z, block.level);
            
            // Use diagnostic method
            if (diagnoseBrokenPermission(pos, null, "allowBlockGravity")) {
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
            String normalizedPermission = normalizePermission(permission);
            
            // Build cache key - include player name for player-specific permissions
            String cacheKey = pos.getLevel().getName() + ":" + 
                          pos.getFloorX() + ":" + 
                          pos.getFloorY() + ":" + 
                          pos.getFloorZ() + ":" + 
                          normalizedPermission + 
                          (player != null ? ":" + player.getName() : "");
            
            // Check cache first with short timeout
            Boolean cachedResult = protectionCache.getIfPresent(cacheKey);
            if (cachedResult != null) {
                return cachedResult;
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
                protectionCache.put(cacheKey, false);
                return false;
            }

            // If no areas but global protection is enabled, check for global bypass
            if (areas.isEmpty()) {
                boolean result = !hasGlobalBypass(player);
                protectionCache.put(cacheKey, result);
                return result;
            }

            // Get the highest priority area
            Area highestPriorityArea = areas.get(0);
            
            // DEBUG: Add detailed logging to diagnose toggle state issues
            if (plugin.isDebugMode()) {
                boolean toggleState = highestPriorityArea.getToggleState(normalizedPermission);
                plugin.debug("Protection check for " + normalizedPermission + 
                             " in area " + highestPriorityArea.getName() + 
                             " returned toggle state: " + toggleState);
            }
            
            // Check if the action is allowed in this area
            // isAllowed() returns TRUE if the action is allowed, FALSE if it should be blocked
            boolean allowed = permissionChecker.isAllowed(player, highestPriorityArea, normalizedPermission);
            
            // For event cancellation, we need to return TRUE if the action should be BLOCKED
            boolean shouldBlock = !allowed;
            
            // Add more debug information to understand the result
            if (plugin.isDebugMode()) {
                plugin.debug("Protection decision: " + (shouldBlock ? "BLOCK" : "ALLOW") + 
                             " for " + normalizedPermission + 
                             " in area " + highestPriorityArea.getName() +
                             (player != null ? " for player " + player.getName() : ""));
            }
            
            // Cache the result with short expiry time to ensure updates are reflected quickly
            protectionCache.put(cacheKey, shouldBlock);
            
            return shouldBlock;
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "protection_check");
        }
    }
    
    /**
     * Normalizes permission names to ensure consistent format with prefix
     * 
     * @param permission Permission to normalize
     * @return Normalized permission with proper prefix
     */
    private String normalizePermission(String permission) {
        if (permission == null || permission.isEmpty()) {
            return "gui.permissions.toggles.default";
        }
        
        // Handle special case shortcuts
        if (!permission.contains(".")) {
            return switch (permission) {
                case "break" -> "gui.permissions.toggles.allowBlockBreak";
                case "build" -> "gui.permissions.toggles.allowBlockPlace";
                case "container" -> "gui.permissions.toggles.allowContainer";
                case "interact" -> "gui.permissions.toggles.allowInteract";
                case "itemDrop" -> "gui.permissions.toggles.allowItemDrop";
                case "itemPickup" -> "gui.permissions.toggles.allowItemPickup";
                default -> "gui.permissions.toggles." + permission;
            };
        }
        
        // Most efficient check - if already has prefix, return as is
        if (permission.startsWith("gui.permissions.toggles.")) {
            return permission;
        }
        
        // Skip special permission types that shouldn't be prefixed
        if (permission.contains("adminarea.") || permission.contains("group.")) {
            return permission;
        }
        
        // Add prefix for other permission names
        return "gui.permissions.toggles." + permission;
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
     * 
     * @param pos The position to check
     * @param player The player involved (can be null for environment actions)
     * @param permission The permission to check
     * @return true if the event should be cancelled (blocked), false if allowed
     */
    protected boolean shouldCancel(Position pos, Player player, String permission) {
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
        
        // Debug log the toggle state decision
        if (plugin.isDebugMode()) {
            boolean toggleState = highestPriorityArea.getToggleState(permission);
            plugin.debug("shouldCancel check for " + permission + 
                        " in area " + highestPriorityArea.getName() + 
                        " returned toggle state: " + toggleState + 
                        " (should cancel: " + !toggleState + ")");
        }
        
        // IMPORTANT: In our toggle system:
        // - A toggle state of TRUE means the action is ALLOWED (don't cancel)
        // - A toggle state of FALSE means the action is DENIED (should cancel)
        // So we need to INVERT the toggle state to determine if we should cancel
        boolean toggleState = highestPriorityArea.getToggleState(permission);
        return !toggleState; // Invert: if toggle is true (allowed), return false (don't cancel)
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
     * Handles item frame rotation events
     * Prevents players from rotating items in frames if not allowed
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemFrameRotate(PlayerInteractEntityEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Entity entity = event.getEntity();
            Player player = event.getPlayer();
            
            // Check if this is an item frame by entity name or class name
            if (entity != null && (
                    entity.getClass().getSimpleName().contains("ItemFrame") || 
                    (entity.getName() != null && entity.getName().toLowerCase().contains("item frame"))
                )) {
                // Check if the player is allowed to rotate items in frames
                if (handleProtection(entity, player, "allowItemRotation")) {
                    event.setCancelled(true);
                    sendProtectionMessage(player, "messages.protection.itemRotation");
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Prevented item frame rotation by " + player.getName() + 
                            " at " + entity.getX() + ", " + entity.getY() + ", " + entity.getZ());
                    }
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "item_frame_rotation_check");
        }
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

    /**
     * Special diagnostic method to trace the flow of a permission check
     * Used only when debugging specific problematic permissions
     */
    boolean diagnoseBrokenPermission(Position pos, Player player, String permission) {
        if (!plugin.isDebugMode()) {
            return handleProtection(pos, player, permission);
        }
        
        // Clear all caches to get a fresh result
        plugin.debug("Clearing permission caches for fresh diagnostic...");
        permissionCache.invalidateAll();
        protectionCache.invalidateAll();
        
        // Also clear permission checker cache to force a complete recalculation
        if (permissionChecker != null) {
            permissionChecker.invalidateCache();
            plugin.debug("Invalidated permission checker cache");
        }
        
        plugin.debug("DIAGNOSTIC MODE: Tracing protection check for " + permission);
        
        // Normalize permission for consistent checking
        String normalizedPermission = normalizePermission(permission);
        plugin.debug("  Normalized permission: " + normalizedPermission);
        
        // Get the area
        List<Area> areas = plugin.getAreaManager().getAreasAtLocation(
            pos.getLevel().getName(),
            pos.getX(),
            pos.getY(),
            pos.getZ()
        );
        
        plugin.debug("  Areas at location: " + (areas.isEmpty() ? "NONE" : areas.size()));
        
        if (areas.isEmpty()) {
            plugin.debug("  No areas found, allowing by default");
            return false; // No permission check needed as no areas exist
        }
        
        Area area = areas.get(0);
        plugin.debug("  Highest priority area: " + area.getName() + " (priority: " + area.getPriority() + ")");
        
        // Check the area toggle state directly
        boolean toggleState = area.getToggleState(normalizedPermission);
        plugin.debug("  Area toggle state for " + normalizedPermission + ": " + toggleState + " (true=allowed, false=denied)");

        // Check for player-specific permissions
        if (player != null) {
            Map<String, Map<String, Boolean>> playerPermissions = area.getPlayerPermissions();
            if (playerPermissions != null && playerPermissions.containsKey(player.getName())) {
                Map<String, Boolean> perms = playerPermissions.get(player.getName());
                if (perms != null && perms.containsKey(normalizedPermission)) {
                    boolean value = perms.get(normalizedPermission);
                    plugin.debug("  Player has specific permission: " + value);
                    plugin.debug("  This overrides the area toggle state.");
                } else {
                    plugin.debug("  Player doesn't have specific permission for " + normalizedPermission);
                }
            } else {
                plugin.debug("  No player-specific permissions found for " + player.getName());
            }
            
            // Check group permissions
            plugin.debug("  Checking group permissions for player: " + player.getName());
            Map<String, Map<String, Boolean>> groupPermissions = area.getGroupPermissions();
            boolean foundGroupPerm = false;
            
            for (Map.Entry<String, Map<String, Boolean>> entry : groupPermissions.entrySet()) {
                String groupName = entry.getKey();
                if (player.hasPermission("group." + groupName)) {
                    plugin.debug("  Player is in group: " + groupName);
                    Map<String, Boolean> groupPerms = entry.getValue();
                    if (groupPerms.containsKey(normalizedPermission)) {
                        boolean groupAllowed = groupPerms.get(normalizedPermission);
                        plugin.debug("  Group permission found: " + groupAllowed);
                        plugin.debug("  This would override the area toggle state.");
                        foundGroupPerm = true;
                    }
                }
            }
            
            if (!foundGroupPerm) {
                plugin.debug("  No relevant group permissions found for this permission");
            }
        }
        
        // Perform the actual permission check
        boolean allowed = !permissionChecker.isAllowed(player, area, permission);
        plugin.debug("  Permission checker result: " + (allowed ? "false" : "true") + " (true=allowed, false=denied)");
        
        if (toggleState != allowed && player != null) {
            plugin.debug("  Note: Area toggle state is " + toggleState + ", but final result is " + allowed + ".");
            
            // Check if we have player or group specific permissions that might explain this
            boolean hasSpecificPermissions = false;
            
            // Check player permissions
            Map<String, Map<String, Boolean>> playerPermissions = area.getPlayerPermissions();
            if (playerPermissions != null && playerPermissions.containsKey(player.getName())) {
                Map<String, Boolean> perms = playerPermissions.get(player.getName());
                if (perms != null && perms.containsKey(normalizedPermission)) {
                    hasSpecificPermissions = true;
                }
            }
            
            // Check group permissions if no player permissions found
            if (!hasSpecificPermissions) {
                Map<String, Map<String, Boolean>> groupPermissions = area.getGroupPermissions();
                for (Map.Entry<String, Map<String, Boolean>> entry : groupPermissions.entrySet()) {
                    String groupName = entry.getKey();
                    if (player.hasPermission("group." + groupName)) {
                        Map<String, Boolean> groupPerms = entry.getValue();
                        if (groupPerms.containsKey(normalizedPermission)) {
                            hasSpecificPermissions = true;
                            break;
                        }
                    }
                }
            }
            
            if (!hasSpecificPermissions) {
                plugin.debug("  This is UNEXPECTED as no specific permissions were found. Permission system may be buggy.");
                
                // Force invalidate all caches for this area
                if (permissionChecker != null) {
                    permissionChecker.invalidateCache(area.getName());
                    plugin.debug("  EMERGENCY CACHE INVALIDATION for area " + area.getName());
                }
                
                // Force invalidate toggle state cache
                area.clearCaches();
                plugin.debug("  EMERGENCY AREA CACHE INVALIDATION for area " + area.getName());
                
                // Try once more with all fresh values
                boolean retryCheck = !permissionChecker.isAllowed(player, area, permission);
                plugin.debug("  RETRY PERMISSION CHECK result: " + (retryCheck ? "false" : "true") + 
                           " (true=allowed, false=denied)");
                
                // Compare with direct toggle state
                boolean directToggleState = area.getToggleState(normalizedPermission);
                plugin.debug("  DIRECT toggle state check: " + directToggleState);
                
                // Update the result if we got a different answer
                if (allowed != retryCheck) {
                    plugin.debug("  UPDATING RESULT based on fresh check: " + (retryCheck ? "BLOCK" : "ALLOW"));
                    allowed = retryCheck;
                }
            }
        }
        
        plugin.debug("  FINAL PROTECTION DECISION: " + (allowed ? "BLOCK" : "ALLOW"));
        
        return allowed;
    }
}
