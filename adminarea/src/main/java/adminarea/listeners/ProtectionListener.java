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
                sendProtectionMessage(player, "messages.protection.breakDenied");
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
            sendProtectionMessage(player, "messages.protection.breakDenied");
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
            sendProtectionMessage(player, "messages.protection.buildDenied");
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
                sendProtectionMessage(player, "messages.protection.containerDenied");
            }
            return;
        }

        // Handle other interactions
        if (handleProtection(pos, player, "interact")) {
            event.setCancelled(true);
            sendProtectionMessage(player, "messages.protection.interactDenied");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Block block = event.getBlock();
            Position pos = new Position(block.x, block.y, block.z, block.level);
            if (handleProtection(pos, null, "allowRedstone")) {
                event.setCancelled();
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "redstone_check");
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
                    if (handleProtection(victim, player, "allowPvP")) {
                        event.setCancelled(true);
                        sendProtectionMessage(player, "messages.protection.pvp");
                        return;
                    }
                }
                
                // General entity damage check
                if (handleProtection(victim, player, "allowDamageEntities")) {
                    event.setCancelled(true);
                    sendProtectionMessage(player, "messages.protection.entityDamage");
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
            List<Block> blocksToRemove = new ArrayList<>();
            List<Block> currentBatch = new ArrayList<>();
            String worldName = event.getPosition().getLevel().getName();
            
            // Get all areas in the explosion radius first
            Map<String, Area> relevantAreas = new HashMap<>();
            for (Block block : event.getBlockList()) {
                Position pos = Position.fromObject(block, block.getLevel());
                List<Area> areas = plugin.getApplicableAreas(worldName, pos.x, pos.y, pos.z);
                for (Area area : areas) {
                    relevantAreas.put(area.getName(), area);
                }
            }
            
            // Process blocks in batches
            for (Block block : event.getBlockList()) {
                currentBatch.add(block);
                
                if (currentBatch.size() >= BATCH_SIZE) {
                    processBatchedBlocks(worldName, currentBatch, blocksToRemove, relevantAreas);
                    currentBatch.clear();
                }
            }
            
            // Process remaining blocks
            if (!currentBatch.isEmpty()) {
                processBatchedBlocks(worldName, currentBatch, blocksToRemove, relevantAreas);
            }
            
            event.getBlockList().removeAll(blocksToRemove);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "explosion_check");
        }
    }

    private void processBatchedBlocks(String worldName, List<Block> batch, List<Block> blocksToRemove, Map<String, Area> relevantAreas) {
        // Check protection for each block using the cached areas
        for (Block block : batch) {
            Position pos = Position.fromObject(block, block.getLevel());
            String cacheKey = buildCacheKey(pos, null, "explosions");
            Boolean cached = protectionCache.getIfPresent(cacheKey);
            
            if (cached != null) {
                if (cached) blocksToRemove.add(block);
                continue;
            }

            boolean isProtected = false;
            for (Area area : relevantAreas.values()) {
                if (area.contains(pos)) {
                    isProtected = !area.getToggleState("allowExplosions");
                    if (isProtected) break;
                }
            }

            if (isProtected) {
                blocksToRemove.add(block);
            }
            protectionCache.put(cacheKey, isProtected);
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
        Area area = plugin.getArea(areaName);
        if (area == null || !area.toDTO().showTitle()) return;

        Map<String, Object> titleConfig = plugin.getConfigManager().getTitleConfig("enter");
        String title = (String) titleConfig.get("main");
        String subtitle = (String) titleConfig.get("subtitle");

        // Replace placeholders
        title = title.replace("{area}", areaName);
        subtitle = subtitle.replace("{area}", areaName);

        player.sendTitle(
            title,
            subtitle,
            plugin.getConfigManager().getInt("title.enter.fadeIn", 20),
            plugin.getConfigManager().getInt("title.enter.stay", 40),
            plugin.getConfigManager().getInt("title.enter.fadeOut", 20)
        );
    }

    private void handleAreaLeave(Player player, String areaName) {
        Area area = plugin.getArea(areaName);
        if (area == null || !area.toDTO().showTitle()) return;

        Map<String, Object> titleConfig = plugin.getConfigManager().getTitleConfig("leave");
        String title = (String) titleConfig.get("main");
        String subtitle = (String) titleConfig.get("subtitle");

        // Replace placeholders
        title = title.replace("{area}", areaName);
        subtitle = subtitle.replace("{area}", areaName);

        player.sendTitle(
            title,
            subtitle,
            plugin.getConfigManager().getInt("title.leave.fadeIn", 20),
            plugin.getConfigManager().getInt("title.leave.stay", 40),
            plugin.getConfigManager().getInt("title.leave.fadeOut", 20)
        );
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
            sendProtectionMessage(player, "messages.protection.containerDenied");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(ItemFrameDropItemEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (handleProtection(event.getItemFrame().getLocation(), null, "allowHangingBreak")) {
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityTame(PlayerInteractEntityEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Entity entity = event.getEntity();
            // Check if entity is a tameable type
            if (isTameableEntity(entity)) {
                if (handleProtection(entity, event.getPlayer(), "allowTaming")) {
                    event.setCancelled(true);
                    sendProtectionMessage(event.getPlayer(), "messages.protection.taming");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "entity_tame_check");
        }
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

    @EventHandler
    public void onAreaDeleted(AreaDeletedEvent event) {
        Area area = event.getArea();
        if (area == null) {
            return;
        }

        // Clear protection cache for the area's bounds
        protectionCache.asMap().entrySet().removeIf(entry -> {
            String[] parts = entry.getKey().split(":");
            if (parts.length < 4) return false;
            String world = parts[0];
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            return world.equals(area.toDTO().world()) &&
                   x >= area.toDTO().bounds().xMin() && x <= area.toDTO().bounds().xMax() &&
                   y >= area.toDTO().bounds().yMin() && y <= area.toDTO().bounds().yMax() &&
                   z >= area.toDTO().bounds().zMin() && z <= area.toDTO().bounds().zMax();
        });

        // Clear temporary permissions for this area
        for (Set<String> perms : temporaryPermissions.values()) {
            perms.removeIf(perm -> perm.endsWith("." + area.getName()));
        }

        plugin.getLogger().debug("Cleared caches for deleted area: " + area.getName());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getReason() == CreatureSpawnEvent.SpawnReason.BREEDING) {
            if (handleProtection(event.getPosition(), null, "allowBreeding")) {
                event.setCancelled(true);
            }
        }
    }

    boolean handleProtection(Position pos, Player player, String permission) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Quick check for bypass
            if (player != null && plugin.isBypassing(player.getName())) {
                return false;
            }

            // Master check - if this returns false, skip all protection checks
            if (!plugin.getAreaManager().shouldProcessEvent(pos, false)) {
                return false;
            }

            // Get applicable areas for this position
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

            // If no areas but global protection is enabled, check if player has global bypass
            if (areas.isEmpty()) {
                return !hasGlobalBypass(player);
            }

            // Check highest priority area first
            Area highestPriorityArea = areas.get(0);
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
}
