package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaDTO;
import adminarea.permissions.PermissionChecker;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.*;
import cn.nukkit.event.entity.*;
import cn.nukkit.event.player.*;
import cn.nukkit.event.redstone.RedstoneUpdateEvent;
import cn.nukkit.level.Position;
import io.micrometer.core.instrument.Timer;
import cn.nukkit.blockentity.BlockEntityChest;
import cn.nukkit.blockentity.BlockEntityFurnace;
import cn.nukkit.blockentity.BlockEntityHopper;
import cn.nukkit.blockentity.BlockEntityDispenser;
import cn.nukkit.blockentity.BlockEntityDropper;
import cn.nukkit.blockentity.BlockEntityShulkerBox;
import cn.nukkit.entity.item.EntityMinecartChest;
import cn.nukkit.entity.item.EntityMinecartHopper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

public class ProtectionListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final Map<String, Set<String>> temporaryPermissions;
    private final Cache<String, Boolean> protectionCache;
    private final Cache<String, Object> explosionAreaCache;
    private final Map<String, Long> lastWarningTime;
    private static final long WARNING_COOLDOWN = 2000; // 2 seconds
    private static final int CACHE_SIZE = 2000;
    private static final long CACHE_EXPIRY = TimeUnit.MINUTES.toMillis(5);
    private final Cache<String, Boolean> permissionCache;
    private final Map<String, Map<String, Boolean>> playerPermissionCache = new ConcurrentHashMap<>();
    private static final int BATCH_SIZE = 16; // Process explosion blocks in batches
    private static final int CHUNK_CHECK_RADIUS = 4;
    private final PermissionChecker permissionChecker;
    private final Map<String, String> playerAreaCache;
    private final Map<String, Integer> lastCheckTimes;
    private final Cache<String, Boolean> itemActionCache;
    private static final int ITEM_CACHE_SIZE = 100;
    private static final long ITEM_CACHE_EXPIRY = TimeUnit.SECONDS.toMillis(10);
    
    // Reference to EntityListener for breeding checks
    private EntityListener entityListener;
    private static final String GUI_PERMISSIONS_PREFIX = "gui.permissions.toggles.";

    public ProtectionListener(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.temporaryPermissions = new ConcurrentHashMap<>();
        this.protectionCache = CacheBuilder.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_EXPIRY, TimeUnit.MILLISECONDS)
            .build();
        this.explosionAreaCache = CacheBuilder.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_EXPIRY, TimeUnit.MILLISECONDS)
            .build();
        this.lastWarningTime = new ConcurrentHashMap<>();
        this.permissionCache = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();
        this.permissionChecker = plugin.getPermissionOverrideManager().getPermissionChecker();
        this.playerAreaCache = new ConcurrentHashMap<>();
        this.lastCheckTimes = new ConcurrentHashMap<>();
        this.itemActionCache = CacheBuilder.newBuilder()
            .maximumSize(ITEM_CACHE_SIZE)
            .expireAfterWrite(ITEM_CACHE_EXPIRY, TimeUnit.MILLISECONDS)
            .build();
    }

    /**
     * Set the entity listener reference for breeding checks
     * This is called from the main plugin after both listeners are initialized
     * 
     * @param entityListener The EntityListener instance
     */
    public void setEntityListener(EntityListener entityListener) {
        this.entityListener = entityListener;
    }

    protected boolean handleProtection(Position pos, Player player, String permission) {
        if (pos == null || pos.getLevel() == null) {
            return false;
        }

        Area area = plugin.getAreaManager().getHighestPriorityArea(
            pos.getLevel().getName(),
            pos.getX(),
            pos.getY(),
            pos.getZ()
        );
        
        if (area == null) {
            return false;
        }
        
        // Use the PermissionChecker to do a comprehensive permission check
        // This will check player, group, track, and area permissions in order
        boolean allowed = permissionChecker.isAllowed(player, area, permission);
        
        // If not allowed, send a message to the player
        if (!allowed) {
            String formattedMsg = plugin.getConfig().getString("messages.protection");
            formattedMsg = formattedMsg.replace("%area%", area.getName());
            formattedMsg = formattedMsg.replace("%permission%", permission);
            sendProtectionMessage(player, formattedMsg);
        }
        
        return !allowed; // Return true if protection should be applied (action canceled)
    }

    /**
     * Sends a protection message to a player with rate limiting
     * 
     * @param player The player to send the message to
     * @param message The message to send
     */
    void sendProtectionMessage(Player player, String message) {
        if (player == null) {
            return;
        }
        
        // Rate limit messages to avoid spam
        long now = System.currentTimeMillis();
        String playerName = player.getName();
        
        if (lastWarningTime.containsKey(playerName)) {
            long lastTime = lastWarningTime.get(playerName);
            if (now - lastTime < WARNING_COOLDOWN) {
                return; // Don't spam messages
            }
        }
        
        // Send the message and update the last warning time
        player.sendMessage(message);
        lastWarningTime.put(playerName, now);
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
            Block block = event.getBlock();
            
            // Check for multi-block structures (doors and beds)
            if (isDoor(block) || isBed(block)) {
                // For doors, check above block
                if (isDoor(block)) {
                    Position abovePos = new Position(pos.x, pos.y + 1, pos.z, pos.level);
                    if (handleProtection(abovePos, player, "allowBlockPlace")) {
                        event.setCancelled(true);
                        sendProtectionMessage(player, "messages.protection.blockPlace");
                        return;
                    }
                }
                
                // For beds, check adjacent block based on block data
                if (isBed(block)) {
                    int facing = block.getDamage() & 0x03; // Get facing direction from block data
                    Position adjacentPos = getAdjacentBedPosition(pos, facing);
                    if (adjacentPos != null && handleProtection(adjacentPos, player, "allowBlockPlace")) {
                        event.setCancelled(true);
                        sendProtectionMessage(player, "messages.protection.blockPlace");
                        return;
                    }
                }
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("Block place at " + pos.getX() + "," + pos.getY() + "," + pos.getZ());
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

    /**
     * Handle bucket empty events (placing water/lava)
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerBucketEmpty(PlayerBucketEmptyEvent event) {
        try {
            Player player = event.getPlayer();
            
            // Skip if bypassing
            if (plugin.isBypassing(player.getName())) {
                return;
            }
            
            Position pos = event.getBlockClicked().getLocation();
            
            // Check if player can place blocks here
            if (handleProtection(pos, player, "allowBlockPlace")) {
                event.setCancelled(true);
                sendProtectionMessage(player, "messages.protection.bucket");
                
                // Force a block update to clear any client-side liquid predictions
                Block block = event.getBlockClicked();
                block.getLevel().sendBlocks(new Player[]{player}, new Block[]{block});
                
                // Schedule a delayed update to ensure client sync
                plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> {
                    block.getLevel().sendBlocks(new Player[]{player}, new Block[]{block});
                }, 1); // 1 tick delay
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error handling bucket empty", e);
        }
    }
    
    /**
     * Handle bucket fill events (taking water/lava)
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerBucketFill(PlayerBucketFillEvent event) {
        try {
            Player player = event.getPlayer();
            
            // Skip if bypassing
            if (plugin.isBypassing(player.getName())) {
                return;
            }
            
            Position pos = event.getBlockClicked().getLocation();
            
            // Check if player can break blocks here
            if (handleProtection(pos, player, "allowBlockBreak")) {
                event.setCancelled(true);
                sendProtectionMessage(player, "messages.protection.bucket");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error handling bucket fill", e);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (event.getAction() == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
                Block block = event.getBlock();
                Player player = event.getPlayer();
                
                // Check bucket interaction first
                if (player.getInventory().getItemInHand().getId() == 325 || // Empty bucket
                    player.getInventory().getItemInHand().getId() == 326 || // Water bucket
                    player.getInventory().getItemInHand().getId() == 327) { // Lava bucket
                    Position pos = new Position(block.x, block.y, block.z, block.level);
                    if (handleProtection(pos, player, "allowBlockPlace")) {
                        event.setCancelled(true);
                        sendProtectionMessage(player, "messages.protection.blockPlace");
                        return;
                    }
                }
                
                // Check door interaction first (new check)
                if (isDoor(block)) {
                    Position pos = new Position(block.x, block.y, block.z, block.level);
                    if (handleProtection(pos, player, "allowDoors")) {
                        event.setCancelled(true);
                        sendProtectionMessage(player, "messages.protection.door");
                        return;
                    }
                }
                
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
            Position from = event.getFrom();
            Position to = event.getTo();
            
            // Skip if no actual movement
            if (from.equals(to)) {
                return;
            }
            
            // Check for flight in protected areas
            if (player.getGamemode() == 1 || player.getGamemode() == 3 || player.getAdventureSettings().get(cn.nukkit.AdventureSettings.Type.ALLOW_FLIGHT)) {
                if (handleProtection(to, player, "allowFlying")) {
                    // Disable flight and teleport back to ground
                    player.setGamemode(0); // Set to survival mode
                    player.getAdventureSettings().set(cn.nukkit.AdventureSettings.Type.ALLOW_FLIGHT, false);
                    player.getAdventureSettings().update();
                    
                    // Find safe ground position
                    Position safePos = findSafeGround(from);
                    if (safePos != null) {
                        event.setTo(safePos.getLocation());
                    } else {
                        event.setTo(from.getLocation());
                    }
                    
                    sendProtectionMessage(player, "messages.protection.noFlight");
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Prevented flight for " + player.getName() + " at " + 
                            to.getFloorX() + ", " + to.getFloorY() + ", " + to.getFloorZ());
                    }
                    return;
                }
            }
            
            // Continue with existing area enter/leave checks
            Area fromArea = plugin.getAreaManager().getHighestPriorityAreaAtPosition(from);
            Area toArea = plugin.getAreaManager().getHighestPriorityAreaAtPosition(to);
            
            String fromAreaName = fromArea != null ? fromArea.getName() : null;
            String toAreaName = toArea != null ? toArea.getName() : null;
            
            if (!Objects.equals(fromAreaName, toAreaName)) {
                if (fromAreaName != null) {
                    showLeaveTitle(player, fromAreaName);
                }
                if (toAreaName != null) {
                    showEnterTitle(player, toAreaName);
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "player_move_check");
        }
    }

    /**
     * Show the title when a player enters an area
     */
    private void showEnterTitle(Player player, String areaName) {
        Area area = plugin.getArea(areaName);
        if (area == null || !area.toDTO().showTitle()) return;

        // Get title configuration
        Map<String, Object> titleConfig = plugin.getConfigManager().getSection("areaTitles." + areaName + ".enter") != null ?
            plugin.getConfigManager().getSection("areaTitles." + areaName + ".enter").getAllMap() :
            plugin.getConfigManager().getTitleConfig("enter");

        // Default values
        String title = "§aEntering " + areaName;
        String subtitle = "§7Welcome!";
        int fadeIn = 20;
        int stay = 40;
        int fadeOut = 20;

        if (titleConfig != null) {
            // Get title text
            if (titleConfig.containsKey("main")) {
                title = plugin.getLanguageManager().getAreaTransitionMessage(
                    titleConfig.get("main").toString(), areaName, player.getName());
            }
            
            // Get subtitle text
            if (titleConfig.containsKey("subtitle")) {
                subtitle = plugin.getLanguageManager().getAreaTransitionMessage(
                    titleConfig.get("subtitle").toString(), areaName, player.getName());
            }
            
            // Get timing values
            fadeIn = titleConfig.containsKey("fadeIn") ? Integer.parseInt(titleConfig.get("fadeIn").toString()) : fadeIn;
            stay = titleConfig.containsKey("stay") ? Integer.parseInt(titleConfig.get("stay").toString()) : stay;
            fadeOut = titleConfig.containsKey("fadeOut") ? Integer.parseInt(titleConfig.get("fadeOut").toString()) : fadeOut;
        }

        // Send the title
        player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
    }

    /**
     * Show the title when a player leaves an area
     */
    private void showLeaveTitle(Player player, String areaName) {
        Area area = plugin.getArea(areaName);
        if (area == null || !area.toDTO().showTitle()) return;

        // Get title configuration
        Map<String, Object> titleConfig = plugin.getConfigManager().getSection("areaTitles." + areaName + ".leave") != null ?
            plugin.getConfigManager().getSection("areaTitles." + areaName + ".leave").getAllMap() :
            plugin.getConfigManager().getTitleConfig("leave");

        // Default values
        String title = "§eLeaving " + areaName;
        String subtitle = "§7Goodbye!";
        int fadeIn = 20;
        int stay = 40;
        int fadeOut = 20;

        if (titleConfig != null) {
            // Get title text
            if (titleConfig.containsKey("main")) {
                title = plugin.getLanguageManager().getAreaTransitionMessage(
                    titleConfig.get("main").toString(), areaName, player.getName());
            }
            
            // Get subtitle text
            if (titleConfig.containsKey("subtitle")) {
                subtitle = plugin.getLanguageManager().getAreaTransitionMessage(
                    titleConfig.get("subtitle").toString(), areaName, player.getName());
            }
            
            // Get timing values
            fadeIn = titleConfig.containsKey("fadeIn") ? Integer.parseInt(titleConfig.get("fadeIn").toString()) : fadeIn;
            stay = titleConfig.containsKey("stay") ? Integer.parseInt(titleConfig.get("stay").toString()) : stay;
            fadeOut = titleConfig.containsKey("fadeOut") ? Integer.parseInt(titleConfig.get("fadeOut").toString()) : fadeOut;
        }

        // Send the title
        player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
    }

    /**
     * Find a safe ground position below the given position
     * @param pos The starting position
     * @return A safe position on the ground, or null if none found
     */
    private Position findSafeGround(Position pos) {
        if (pos.getLevel() == null) return null;
        
        // Start from current position and search downward
        int maxSearchDistance = 20; // Don't search too far down
        int startY = pos.getFloorY();
        int minY = Math.max(0, startY - maxSearchDistance);
        
        for (int y = startY; y >= minY; y--) {
            Position checkPos = new Position(pos.x, y, pos.z, pos.level);
            Position abovePos = new Position(pos.x, y + 1, pos.z, pos.level);
            Position belowPos = new Position(pos.x, y - 1, pos.z, pos.level);
            
            // Check if this is a safe landing spot (solid ground below, air above)
            if (!checkPos.getLevelBlock().canPassThrough() && // Current block is solid
                abovePos.getLevelBlock().canPassThrough() && // Block above is passable
                !belowPos.getLevelBlock().canPassThrough()) { // Block below is solid
                
                // Return the position above the solid block
                return new Position(pos.x, y + 1, pos.z, pos.level);
            }
        }
        
        return null;
    }

    /**
     * Handle gamemode changes to prevent creative mode flight in protected areas
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getPlayer();
            
            // Check if changing to creative or spectator mode
            if (event.getNewGamemode() == 1 || event.getNewGamemode() == 3) {
                if (handleProtection(player.getPosition(), player, "allowFlying")) {
                    // Cancel the gamemode change
                    event.setCancelled(true);
                    sendProtectionMessage(player, "messages.protection.noCreativeFlight");
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Prevented gamemode change to flight-enabled mode for " + 
                            player.getName() + " at " + player.getPosition().toString());
                    }
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "gamemode_change_check");
        }
    }

    /**
     * Handle toggling of flight mode
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlightToggle(PlayerToggleFlightEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getPlayer();
            
            if (event.isFlying() && handleProtection(player.getPosition(), player, "allowFlying")) {
                event.setCancelled(true);
                player.setAllowFlight(false);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Prevented flight toggle for " + player.getName() + 
                        " at " + player.getPosition().toString());
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "flight_toggle_check");
        }
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
            
            // Skip if bypassing
            if (plugin.isBypassing(player.getName())) {
                return;
            }
            
            // Check if this is an item frame
            if (entity != null && (
                    entity.getClass().getSimpleName().equals("EntityItemFrame") || 
                    entity.getClass().getSimpleName().equals("EntityGlowItemFrame")
                )) {
                // Check if the player is allowed to interact with item frames
                if (handleProtection(entity.getPosition(), player, "allowItemRotation")) {
                    event.setCancelled(true);
                    sendProtectionMessage(player, "messages.protection.itemFrame");
                    
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

    /**
     * Gets the highest priority area at a player's position.
     * 
     * @param player The player to check location for
     * @return The highest priority area, or null if none found
     */
    private Area getAreaAtPlayer(Player player) {
        if (player == null || player.getLevel() == null) return null;
        
        return plugin.getHighestPriorityArea(
            player.getLevel().getName(),
            player.getX(),
            player.getY(),
            player.getZ()
        );
    }

    /**
     * Checks if a block is a door, trapdoor or fence gate
     * 
     * @param block The block to check
     * @return true if the block is a door/trapdoor/gate
     */
    private boolean isDoor(Block block) {
        return switch (block.getId()) {
            // Regular doors
            case Block.WOODEN_DOOR_BLOCK,
                 Block.SPRUCE_DOOR_BLOCK,
                 Block.BIRCH_DOOR_BLOCK,
                 Block.JUNGLE_DOOR_BLOCK,
                 Block.ACACIA_DOOR_BLOCK,
                 Block.DARK_OAK_DOOR_BLOCK,
                 Block.CRIMSON_DOOR_BLOCK,
                 Block.WARPED_DOOR_BLOCK,
                 Block.IRON_DOOR_BLOCK -> true;
            // Trapdoors
            case Block.TRAPDOOR,
                 Block.IRON_TRAPDOOR,
                 Block.SPRUCE_TRAPDOOR, 
                 Block.BIRCH_TRAPDOOR,
                 Block.JUNGLE_TRAPDOOR,
                 Block.ACACIA_TRAPDOOR,
                 Block.DARK_OAK_TRAPDOOR,
                 Block.CRIMSON_TRAPDOOR,
                 Block.WARPED_TRAPDOOR -> true;
            // Gates
            case Block.FENCE_GATE,
                 Block.CRIMSON_FENCE_GATE,
                 Block.WARPED_FENCE_GATE -> true;
            default -> false;
        };
    }

    // Method to clear the item cache when needed
    public void clearItemCache() {
        if (itemActionCache != null) {
            itemActionCache.invalidateAll();
            if (plugin.isDebugMode()) {
                plugin.debug("Item action cache cleared");
            }
        }
    }

    /**
     * Checks if a block is a bed
     * @param block The block to check
     * @return true if the block is any type of bed
     */
    private boolean isBed(Block block) {
        return block.getId() == Block.BED_BLOCK;
    }

    /**
     * Gets the position of the second part of a bed based on facing direction
     * @param pos The position of the head part
     * @param facing The facing direction (0=south, 1=west, 2=north, 3=east)
     * @return Position of the foot part of the bed
     */
    private Position getAdjacentBedPosition(Position pos, int facing) {
        switch(facing) {
            case 0: // South
                return new Position(pos.x, pos.y, pos.z + 1, pos.level);
            case 1: // West
                return new Position(pos.x - 1, pos.y, pos.z, pos.level);
            case 2: // North
                return new Position(pos.x, pos.y, pos.z - 1, pos.level);
            case 3: // East
                return new Position(pos.x + 1, pos.y, pos.z, pos.level);
            default:
                return null;
        }
    }

    /**
     * Handles XP orb spawning
     * Prevents XP orbs from spawning in protected areas
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onXPSpawn(EntitySpawnEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Entity entity = event.getEntity();
            
            // Check if this is an XP orb
            if (entity != null && entity.getClass().getSimpleName().equals("EntityXPOrb")) {
                // Check if in protected area that disables XP drops
                Position pos = entity.getPosition();
                Area area = plugin.getAreaManager().getHighestPriorityAreaAtPosition(pos);
                
                if (area != null && !area.getToggleState("allowXPDrop")) {
                    // Remove the XP orb if in a protected area that disables XP drops
                    entity.close();
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Removed XP orb at " + 
                            entity.getX() + ", " + entity.getY() + ", " + entity.getZ() +
                            " in area " + area.getName() + " (XP drops disabled)");
                    }
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "xp_spawn_check");
        }
    }

    // Map to store player XP before death
    private final Map<String, Integer> playerXPBeforeDeath = new ConcurrentHashMap<>();

    /**
     * Handles player death to preserve XP
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getEntity();
            Position pos = player.getPosition();
            Area area = plugin.getAreaManager().getHighestPriorityAreaAtPosition(pos);
            
            // Clear any previous stored XP to prevent stale data
            playerXPBeforeDeath.remove(player.getName());
            
            // Only store and preserve XP if in an area that disables XP drops
            if (area != null && !area.getToggleState("allowXPDrop")) {
                // Store the player's XP level
                int currentLevel = player.getExperienceLevel();
                playerXPBeforeDeath.put(player.getName(), currentLevel);
                
                // Prevent XP from dropping
                event.setExperience(0);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("XP preserved: Stored level " + currentLevel + 
                                " for player " + player.getName() + 
                                " in area " + area.getName() + " (XP drops disabled)");
                }
            } else if (plugin.isDebugMode()) {
                if (area == null) {
                    plugin.debug("XP not preserved: Player " + player.getName() + 
                                " died outside of any protected area");
                } else {
                    plugin.debug("XP not preserved: Player " + player.getName() + 
                                " died in area " + area.getName() + " (XP drops enabled)");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "player_death_xp_handler");
        }
    }

    /**
     * Restores player XP after respawn
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getPlayer();
            String playerName = player.getName();
            
            // Check if we have stored XP for this player
            Integer storedLevel = playerXPBeforeDeath.remove(playerName);
            if (storedLevel != null) {
                // Schedule XP restoration for next tick to ensure it works properly
                plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                    // Set the experience level directly
                    player.setExperience(0, storedLevel);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("XP restored: Set level to " + storedLevel + 
                                    " for player " + playerName);
                    }
                }, true);
            } else if (plugin.isDebugMode()) {
                plugin.debug("No stored XP found for player " + playerName + 
                            " on respawn (normal behavior for areas with XP drops enabled)");
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "player_respawn_xp_handler");
        }
    }

    protected boolean isContainer(Block block) {
        if (block == null) return false;
        
        var blockEntity = block.getLevel().getBlockEntity(block);
        return blockEntity instanceof BlockEntityChest ||
               blockEntity instanceof BlockEntityFurnace ||
               blockEntity instanceof BlockEntityHopper ||
               blockEntity instanceof BlockEntityDispenser ||
               blockEntity instanceof BlockEntityDropper ||
               blockEntity instanceof BlockEntityShulkerBox;
    }

    protected boolean isVehicle(Entity entity) {
        if (entity == null) return false;
        
        return entity instanceof EntityMinecartChest ||
               entity instanceof EntityMinecartHopper;
    }

    protected String normalizePermission(String permission) {
        if (permission == null || permission.isEmpty()) {
            return "gui.permissions.toggles.default";
        }
        
        // Already has prefix
        if (permission.startsWith("gui.permissions.toggles.")) {
            return permission;
        }
        
        // Simple permission without dots
        if (!permission.contains(".")) {
            return "gui.permissions.toggles." + permission;
        }
        
        // Return as is for special permission types
        return permission;
    }

    /**
     * Cleanup resources for a specific player
     */
    public void cleanup(Player player) {
        if (player != null) {
            String playerName = player.getName();
            lastWarningTime.remove(playerName);
            permissionCache.invalidate(playerName);
            playerPermissionCache.remove(playerName);
        }
    }

    /**
     * General cleanup of all resources
     */
    public void cleanup() {
        lastWarningTime.clear();
        permissionCache.invalidateAll();
        playerPermissionCache.clear();
        itemActionCache.invalidateAll();
    }

    /**
     * Prevents food level changes in protected areas where hunger is disabled
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFoodLevelChange(PlayerFoodLevelChangeEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getPlayer();
            
            // Skip if player is bypassing protection
            if (plugin.isBypassing(player.getName())) {
                return;
            }
            
            // Only prevent hunger loss, not gain
            if (event.getFoodLevel() >= player.getFoodData().getLevel()) {
                return;
            }
            
            Position pos = player.getPosition();
            Area area = plugin.getAreaManager().getHighestPriorityAreaAtPosition(pos);
            
            if (area != null && !area.getToggleState("allowHunger")) {
                event.setCancelled(true);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Prevented hunger depletion for " + player.getName() + " in area " + area.getName());
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "food_level_change_handler");
        }
    }

}
