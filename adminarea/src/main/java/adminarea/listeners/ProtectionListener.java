package adminarea.listeners;

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

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaDTO;
import adminarea.event.AreaPermissionUpdateEvent;
import adminarea.event.LuckPermsGroupChangeEvent;
import adminarea.event.PermissionChangeEvent;

/**
 * Handles all protection-related events and permission checks.
 */
public class ProtectionListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final Map<String, Set<String>> temporaryPermissions;
    private final Map<Position, Boolean> protectionCache;
    private final Map<String, Long> lastWarningTime;
    private static final long WARNING_COOLDOWN = 3000; // 3 seconds
    private static final int CACHE_SIZE = 1000;
    private static final long CACHE_EXPIRY = TimeUnit.MINUTES.toMillis(5);
    private final Cache<String, Boolean> permissionCache;
    private static final long PERMISSION_CACHE_DURATION = TimeUnit.MINUTES.toMillis(5);

    public ProtectionListener(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.temporaryPermissions = new ConcurrentHashMap<>();
        this.protectionCache = new LinkedHashMap<Position, Boolean>(CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Position, Boolean> eldest) {
                return size() > CACHE_SIZE;
            }
        };
        this.lastWarningTime = new ConcurrentHashMap<>();
        this.permissionCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (handleProtection(event.getBlock(), event.getPlayer(), "gui.permissions.toggles.allowBreak")) {
                event.setCancelled(true);
                // Get the area at the block's location for the message
                Area area = plugin.getHighestPriorityArea(
                    event.getBlock().getLevel().getName(),
                    event.getBlock().getX(),
                    event.getBlock().getY(),
                    event.getBlock().getZ()
                );
                
                HashMap<String, String> placeholders = new HashMap<>();
                if (area != null) {
                    placeholders.put("area", area.getName());
                }
                
                sendProtectionMessage(event.getPlayer(), "messages.blockBreak", placeholders);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "block_break_check");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getPlayer();
            Block block = event.getBlock();
            
            // Check bypass first
            if (player.hasPermission("adminarea.bypass") || plugin.isBypassing(player.getName())) {
                return;
            }

            // Handle protection check - Update permission node to match gui.permissions.toggles format
            if (handleProtection(block, player, "gui.permissions.toggles.build")) {
                event.setCancelled(true);
                
                // Get the area for the message
                Area area = plugin.getHighestPriorityArea(
                    block.getLevel().getName(),
                    block.getX(),
                    block.getY(),
                    block.getZ()
                );
                
                HashMap<String, String> placeholders = new HashMap<>();
                if (area != null) {
                    placeholders.put("area", area.getName());
                }
                
                sendProtectionMessage(player, "messages.blockPlace", placeholders);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "block_place_check");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getPlayer();
            Block block = event.getBlock();
            
            // Skip interaction check if a break/place event will handle it
            if (event.getAction() == PlayerInteractEvent.Action.LEFT_CLICK_BLOCK || 
                event.getAction() == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK && player.getInventory().getItemInHand().canBePlaced()) {
                return;
            }
            
            // Special handling for containers
            if (block != null && isContainer(block)) {
                if (handleProtection(block, player, "gui.permissions.toggles.allowContainer")) {
                    event.setCancelled(true);
                    sendProtectionMessage(player, "messages.protection.container", new HashMap<>());
                    return;
                }
            }

            // General interaction protection
            if (handleProtection(block, player, "gui.permissions.toggles.allowInteract")) {
                event.setCancelled(true);
                sendProtectionMessage(player, "messages.interact", new HashMap<>());
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "interact_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Area area = plugin.getHighestPriorityArea(event.getBlock().getLevel().getName(),
                event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
                
            if (area != null && !area.toDTO().settings().optBoolean("gui.permissions.toggles.allowRedstone", true)) {
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
                // PvP protection
                if (victim instanceof Player && handleProtection(victim, player, "allowPvP")) {
                    event.setCancelled(true);
                    sendProtectionMessage(player, "messages.pvp", new HashMap<>());
                    return;
                }
                
                // General entity protection
                if (handleProtection(victim, player, "allowEntityDamage")) {
                    event.setCancelled(true);
                    sendProtectionMessage(player, "messages.protectionDenied", new HashMap<>());
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
                    Area area = plugin.getHighestPriorityArea(block.getLevel().getName(),
                        block.getX(), block.getY(), block.getZ());
                    return area == null || area.toDTO().settings().optBoolean("allowExplosions", false);
                })
                .toList());
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "explosion_check");
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
        if (handleProtection(event.getBlock(), null, "allowLiquidFlow")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Position from = event.getFrom();
        Position to = event.getTo();
        
        // Get areas for both positions
        Area fromArea = plugin.getHighestPriorityArea(from.getLevel().getName(),
            from.getX(), from.getY(), from.getZ());
        Area toArea = plugin.getHighestPriorityArea(to.getLevel().getName(),
            to.getX(), to.getY(), to.getZ());
        
        // If areas are different, handle enter/leave messages
        if (fromArea != toArea) {
            // Handle leaving area
            if (fromArea != null) {
                AreaDTO fromAreaDTO = fromArea.toDTO();
                if (fromAreaDTO.showTitle() && fromAreaDTO.leaveMessage() != null) {
                    player.sendTitle(
                        "§e" + fromAreaDTO.name(),
                        fromAreaDTO.leaveMessage(),
                        20, // fadeIn
                        40, // stay
                        20  // fadeOut
                    );
                }
            }
            
            // Handle entering area
            if (toArea != null) {
                AreaDTO toAreaDTO = toArea.toDTO();
                if (toAreaDTO.showTitle() && toAreaDTO.enterMessage() != null) {
                    player.sendTitle(
                        "§e" + toAreaDTO.name(),
                        toAreaDTO.enterMessage(),
                        20, // fadeIn
                        40, // stay
                        20  // fadeOut
                    );
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (event.getPlayer() instanceof Player player) {
                if (handleProtection(player.getPosition(), player, "allowContainerAccess")) {
                    event.setCancelled(true);
                    sendProtectionMessage(player, "messages.protection.container", new HashMap<>());
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "inventory_check");
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
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getReason() == CreatureSpawnEvent.SpawnReason.BREEDING) {
            if (handleProtection(event.getPosition(), null, "gui.permissions.toggles.allowBreeding")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityTame(PlayerInteractEntityEvent event) {
        if (event.getEntity().isAlive() && handleProtection(event.getEntity(), event.getPlayer(), "gui.permissions.allowVehicleEnter")) {
            event.setCancelled(true);
            sendProtectionMessage(event.getPlayer(), "messages.protection.vehicleEnter");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDrop(PlayerDropItemEvent event) {
        if (handleProtection(event.getPlayer(), event.getPlayer(), "gui.permissions.toggles.allowItemDrop")) {
            event.setCancelled(true);
            sendProtectionMessage(event.getPlayer(), "messages.protection.itemDrop");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemPickup(InventoryPickupItemEvent event) {
        if (event.getInventory().getHolder() instanceof Player player &&
            handleProtection(event.getItem(), player, "gui.permissions.toggles.allowItemPickup")) {
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
        if (event.getEntity() instanceof Player player &&
            handleProtection(event.getVehicle(), player, "gui.permissions.toggles.allowVehicleEnter")) {
            event.setCancelled(true);
            sendProtectionMessage(player, "messages.protection.vehicleEnter");
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
            // Invalidate all cached permissions for this area
            String areaPrefix = event.getArea().getName() + ":";
            permissionCache.asMap().keySet().removeIf(key -> key.startsWith(areaPrefix));
            
            if (plugin.isDebugMode()) {
                plugin.getLogger().debug(String.format(
                    "Bulk permission update in area %s for category %s", 
                    event.getArea().getName(),
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

    boolean handleProtection(Position pos, Player player, String permission) {
        if (player != null && plugin.isBypassing(player.getName())) {
            plugin.debug("Protection bypassed by player: " + player.getName());
            return false;
        }

        String cacheKey = buildCacheKey(pos, player, permission);
        Boolean cached = permissionCache.getIfPresent(cacheKey);
        if (cached != null) {
            plugin.debug("Using cached protection result for " + cacheKey + ": " + cached);
            return cached;
        }

        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Area area = plugin.getHighestPriorityArea(pos.getLevel().getName(),
                pos.getX(), pos.getY(), pos.getZ());
            
            if (area == null) {
                plugin.debug("No area found at position " + pos);
                return false;
            }

            plugin.debug("Checking protection in area: " + area.getName());

            // Get current DTO
            AreaDTO currentDTO = area.toDTO();

            // Check toggle state first
            boolean toggleState = area.getToggleState(permission);
            if (!toggleState) {
                permissionCache.put(cacheKey, true);
                return true;
            }

            // Check player-specific permissions first
            if (player != null) {
                // Check temporary permissions
                if (hasTemporaryPermission(player.getName(), permission, area.getName())) {
                    permissionCache.put(cacheKey, false);
                    return false;
                }

                // Check LuckPerms group permissions
                if (plugin.getLuckPermsApi() != null && 
                    !plugin.hasGroupPermission(player, area, permission)) {
                    permissionCache.put(cacheKey, true);
                    return true;
                }
            }

            boolean result = !currentDTO.settings().optBoolean(permission);
            permissionCache.put(cacheKey, result);
            return result;
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

    private Boolean getCachedResult(Position pos, String permission) {
        String cacheKey = pos.getLevel().getName() + ":" + pos.getFloorX() + ":" + 
                         pos.getFloorY() + ":" + pos.getFloorZ() + ":" + permission;
        synchronized (protectionCache) {
            return protectionCache.get(pos);
        }
    }

    private void cacheProtectionResult(Position pos, String permission, boolean result) {
        synchronized (protectionCache) {
            protectionCache.put(pos, result);
        }
    }

    private boolean isContainer(Block block) {
        switch (block.getId()) {
            case BlockID.CHEST:
            case BlockID.TRAPPED_CHEST:
            case BlockID.BARREL:
            case BlockID.FURNACE:
            case BlockID.BLAST_FURNACE:
            case BlockID.SMOKER:
            case BlockID.BREWING_STAND_BLOCK:
            case BlockID.DISPENSER:
            case BlockID.DROPPER:
            case BlockID.HOPPER_BLOCK:
            case BlockID.SHULKER_BOX:
                return true;
            default:
                return false;
        }
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
                message = plugin.getLanguageManager().get("messages.protection.protectionDenied", placeholders);
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
        protectionCache.clear();
        temporaryPermissions.clear();
        lastWarningTime.clear();
        permissionCache.invalidateAll();
    }
}
